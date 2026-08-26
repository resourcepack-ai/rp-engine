package ai.resourcepack.engine.core.bedrock;

import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AnimateEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.predicate.item.ItemMatchPredicate;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.session.GeyserSession;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Bedrock half of the plugin, active only when Geyser runs on this
 * server. Two jobs:
 *
 * <b>Pack delivery.</b> A Bedrock client negotiates its pack list once
 * during login, so "apply" means: stash the built .mcpack under the player's
 * xuid, then transfer the client back to the address it joined from. On the
 * way back in, the {@link SessionLoadResourcePacksEvent} subscription serves
 * that file to exactly that session. Pending packs are real files
 * (bedrock-packs/&lt;xuid&gt;.mcpack), so one survives a restart between the
 * push and the next join; a newer push overwrites it.
 *
 * <b>In-world rendering.</b> Geyser doesn't translate ItemDisplay rigs, so
 * the converted pack defines every model as a client entity under a fixed
 * slot-pool identifier ({@code rpai:model_<n>}) carrying native Bedrock
 * keyframes. This registers the pool at boot
 * ({@link GeyserDefineEntitiesEvent}, fired on ServerLoadEvent, after every
 * onEnable), spawns a client-side entity per placed rig per session, and
 * fires AnimateEntityPacket on triggers.
 *
 * Everything Geyser-typed stays in this class, which PresencePlugin only
 * constructs after a Class.forName probe. Spawn/animate reach GeyserSession
 * internals since the packet surface has no public API yet, and every such
 * touch is fenced: a Geyser update degrades to "models invisible again",
 * never to broken pack pushes.
 */
public final class GeyserBridge implements ai.resourcepack.engine.core.distribution.BedrockSupport {

    private final JavaPlugin plugin;

    /**
     * Told before a transfer, so the quit it causes is not read as a quit.
     *
     * <p>Serving a Bedrock pack means transferring the player and having them
     * reconnect, which reaches the server as a disconnect. Without this the
     * quit handler drops their sync pairing mid-apply and the panel goes back
     * to asking for a code after every push.
     */
    private final java.util.function.Consumer<UUID> expectReconnect;
    private final File packsDir;
    private final EventRegistrar registrar;
    private final NamespacedKey modelKey;
    private final NamespacedKey yawKey;

    // Registration happens once, at Geyser's registry build during boot, so
    // model-specific names are unknowable. A fixed pool of generic ids is
    // registered instead ("rpai:model_<n>" entities, "rpai_slot_<n>" item
    // markers) and each session's served pack decides what its slots look
    // like, so new models ride in on the pack-apply reconnect. Slot numbers
    // are the panel's per-model cmd integers, and freed numbers get reused,
    // so this caps concurrently live models per pack rather than lifetime
    // creations. Mirrors BEDROCK_SLOT_POOL in Studio's Bedrock geometry builder.
    private static final int SLOT_POOL = 200;

    /** One entry of a pack's rpai_items.json. */
    private static final class ItemInfo {
        String model;
        Integer slot;
        String displayName;
    }

    private static final class ItemManifest {
        java.util.List<ItemInfo> items;
    }

    // xuid -> (modelId -> slot), parsed from that player's stashed pack, so
    // what we spawn always matches what their client can render.
    private final Map<String, Map<String, Integer>> slotsByXuid = new ConcurrentHashMap<>();

    // playerId -> (hitboxId -> per-session geyser entity id). Main thread only.
    private final Map<UUID, Map<UUID, Long>> spawned = new HashMap<>();

    public GeyserBridge(JavaPlugin plugin, java.util.function.Consumer<UUID> expectReconnect) {
        this.plugin = plugin;
        this.expectReconnect = expectReconnect == null ? id -> { } : expectReconnect;
        this.packsDir = new File(plugin.getDataFolder(), "bedrock-packs");
        this.registrar = EventRegistrar.of(this);
        this.modelKey = new NamespacedKey(plugin, "model-id");
        this.yawKey = new NamespacedKey(plugin, "rig-yaw");
        if (!packsDir.isDirectory() && !packsDir.mkdirs()) {
            plugin.getLogger().warning("Couldn't create " + packsDir + " - Bedrock pack pushes won't persist");
        }
        harvestIdentifiers();
        GeyserApi.api().eventBus().subscribe(registrar, SessionLoadResourcePacksEvent.class, this::onSessionLoadPacks);
        GeyserApi.api().eventBus().subscribe(registrar, GeyserDefineEntitiesEvent.class, this::onDefineEntities);
        GeyserApi.api().eventBus().subscribe(registrar, GeyserDefineCustomItemsEvent.class, this::onDefineCustomItems);
        plugin.getLogger().info("Geyser detected - Bedrock pack pushes enabled");
    }

    public void shutdown() {
        try {
            GeyserApi.api().eventBus().unregisterAll(registrar);
        } catch (Exception ignored) {
            // Geyser may already be down during server shutdown.
        }
    }

    /** Floodgate identities put the xuid in the UUID's low bits with zero high bits. */
    static boolean isBedrockUuid(UUID id) {
        return id != null && id.getMostSignificantBits() == 0;
    }

    @Override
    public boolean available() {
        // The bridge is only ever constructed once Geyser has been found on
        // the server, so its existence IS the answer. Asking GeyserApi again
        // here would be asking a class that may not be loadable.
        return true;
    }

    /**
     * Serves a Bedrock pack, and waits for nobody.
     *
     * <p>The transfer-and-reconnect this does is slow and the caller is a join
     * handler, so the boolean says only that it was ASKED for — the callback
     * form below is what knows whether it landed.
     */
    @Override
    public boolean applyPack(Player target, String bedrockUrl) {
        if (!available() || !isBedrock(target.getUniqueId())) {
            return false;
        }
        applyPack(target, bedrockUrl, ok -> { });
        return true;
    }

    @Override
    public boolean isBedrock(UUID id) {
        return isBedrockPlayer(id);
    }

    public boolean isBedrockPlayer(UUID id) {
        return isBedrockUuid(id) || GeyserApi.api().connectionByUuid(id) != null;
    }

    private File packFile(String xuid) {
        // xuids are numeric; the replace is belt-and-braces against a
        // hostile value ending up in a filename.
        return new File(packsDir, xuid.replaceAll("[^0-9]", "") + ".mcpack");
    }

    // Mirrors sanitizeBedrockName in Studio's Bedrock geometry builder - the two
    // must agree or animation/entity ids won't match the served pack.
    static String sanitize(String name) {
        String cleaned = name.toLowerCase()
            .replaceAll("[\\s-]+", "_")
            .replaceAll("[^a-z0-9_]", "")
            .replaceAll("_{2,}", "_")
            .replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    // --- Pack delivery -------------------------------------------------------

    private void onSessionLoadPacks(SessionLoadResourcePacksEvent event) {
        String xuid = event.connection().xuid();
        if (xuid == null || xuid.isEmpty()) return;
        File file = packFile(xuid);
        if (!file.isFile()) return;
        try {
            event.register(ResourcePack.create(PackCodec.path(file.toPath())));
        } catch (Exception e) {
            plugin.getLogger().warning("Couldn't serve Bedrock pack to " + event.connection().bedrockUsername() + ": " + e.getMessage());
        }
    }

    /**
     * Downloads the pushed .mcpack off-thread, then reconnects the player so
     * the join path picks it up. The completion callback runs on the main
     * thread with success/failure for the APPLIED/FAILED protocol reply.
     */
    public void applyPack(Player target, String bedrockUrl, Consumer<Boolean> done) {
        UUID id = target.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File written = null;
            try {
                GeyserConnection connection = GeyserApi.api().connectionByUuid(id);
                String xuid = connection != null ? connection.xuid() : xuidFromUuid(id);
                if (xuid != null) {
                    byte[] bytes = fetch(bedrockUrl);
                    File file = packFile(xuid);
                    Files.write(file.toPath(), bytes);
                    written = file;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Couldn't download Bedrock pack: " + e.getMessage());
            }
            File packFile = written;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (packFile == null) {
                    done.accept(false);
                    return;
                }
                // New models render as entities only once their identifiers
                // reach a session's login NBT; harvesting now means future
                // restarts pick them up, and the warning says when one is
                // actually needed.
                harvestIdentifiersFrom(packFile);
                boolean transferred = false;
                // The transfer registers as a quit; without this the quit
                // handler unlinks the pairing mid-apply and the panel drops
                // back to asking for /link after every push.
                expectReconnect.accept(id);
                GeyserConnection connection = GeyserApi.api().connectionByUuid(id);
                if (connection != null) {
                    try {
                        // Back to whatever address the client joined on: no
                        // config, and it survives proxies and SRV records.
                        transferred = connection.transfer(connection.joinAddress(), connection.joinPort());
                    } catch (Exception e) {
                        plugin.getLogger().warning("Bedrock transfer failed: " + e.getMessage());
                    }
                }
                if (!transferred && target.isOnline()) {
                    // Transfer unavailable. The pack is staged either way, so
                    // a manual rejoin still applies it.
                    target.kickPlayer("Your resource pack updated - rejoin to load it!");
                    transferred = true;
                }
                done.accept(transferred);
            });
        });
    }

    private static String xuidFromUuid(UUID id) {
        return isBedrockUuid(id) ? Long.toUnsignedString(id.getLeastSignificantBits()) : null;
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    // --- Entity identifier registration --------------------------------------

    /** Loads slot maps for every stashed pack (plugin start / restart). */
    private void harvestIdentifiers() {
        File[] packs = packsDir.listFiles((dir, name) -> name.endsWith(".mcpack"));
        if (packs != null) {
            for (File pack : packs) harvestIdentifiersFrom(pack);
        }
    }

    private void harvestIdentifiersFrom(File pack) {
        // File name is <xuid>.mcpack - the slots inside belong to that viewer.
        String xuid = pack.getName().substring(0, pack.getName().length() - ".mcpack".length());
        Map<String, Integer> slots = new HashMap<>();
        try (ZipFile zip = new ZipFile(pack)) {
            ZipEntry entry = zip.getEntry("rpai_items.json");
            if (entry == null) return;
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                ItemManifest manifest = new com.google.gson.Gson().fromJson(reader, ItemManifest.class);
                if (manifest != null && manifest.items != null) {
                    for (ItemInfo item : manifest.items) {
                        if (item != null && item.model != null && item.slot != null) slots.put(item.model, item.slot);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Couldn't read slot manifest from " + pack.getName() + ": " + e.getMessage());
            return;
        }
        slotsByXuid.put(xuid, slots);
    }

    private void onDefineCustomItems(GeyserDefineCustomItemsEvent event) {
        int count = 0;
        for (int slot = 1; slot <= SLOT_POOL; slot++) {
            try {
                // Matches the "rpai_slot_<n>" marker the panel bakes into a
                // given item's second custom_model_data string. The icon
                // resolves against whatever pack the session carries and the
                // name comes from the Java item_name component, so one
                // generic definition serves every model in the slot.
                event.register(
                    Identifier.of("minecraft", "paper"),
                    CustomItemDefinition.builder(
                            Identifier.of("rpai", "item_" + slot),
                            Identifier.of("minecraft", "paper"))
                        .displayName("Model")
                        .predicate(ItemMatchPredicate.customModelData(1, "rpai_slot_" + slot))
                        .bedrockOptions(CustomItemBedrockOptions.builder().allowOffhand(true).icon("rpai_item_" + slot))
                        .build());
                count++;
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("Couldn't register Bedrock item slot " + slot + ": " + e);
            }
        }
        if (count > 0) plugin.getLogger().info("Registered " + count + " model item slots with Geyser");
    }

    private void onDefineEntities(GeyserDefineEntitiesEvent event) {
        int count = 0;
        for (int slot = 1; slot <= SLOT_POOL; slot++) {
            try {
                event.register(CustomEntityDefinition.of("rpai:model_" + slot));
                count++;
            } catch (Exception e) {
                plugin.getLogger().warning("Couldn't register Bedrock entity slot " + slot + ": " + e.getMessage());
            }
        }
        if (count > 0) plugin.getLogger().info("Registered " + count + " model entity slots with Geyser");
    }

    // --- In-world rendering --------------------------------------------------

    /** Spawns every placed rig in the player's world for their Bedrock session. Call delayed after join. */
    public void syncPlayerView(Player player) {
        GeyserSession session = sessionOf(player.getUniqueId());
        if (session == null) return;
        for (Interaction hitbox : player.getWorld().getEntitiesByClass(Interaction.class)) {
            String modelId = hitbox.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
            if (modelId != null) spawnFor(session, player.getUniqueId(), hitbox, modelId);
        }
    }

    /** A rig was just placed - show it to every Bedrock player in that world. */
    public void onRigPlaced(Interaction hitbox, String modelId) {
        for (Player player : hitbox.getWorld().getPlayers()) {
            GeyserSession session = sessionOf(player.getUniqueId());
            if (session != null) spawnFor(session, player.getUniqueId(), hitbox, modelId);
        }
    }

    public void onRigRemoved(UUID hitboxId) {
        spawned.forEach((playerId, entities) -> {
            Long geyserId = entities.remove(hitboxId);
            if (geyserId == null) return;
            GeyserSession session = sessionOf(playerId);
            if (session == null) return;
            try {
                RemoveEntityPacket packet = new RemoveEntityPacket();
                packet.setUniqueEntityId(geyserId);
                session.sendUpstreamPacket(packet);
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("Couldn't remove Bedrock entity: " + e);
            }
        });
    }

    /** A trigger fired - play the same animation the pack defines, client-side. */
    public void playAnimation(UUID hitboxId, String modelId, String animationName) {
        String animationId = "animation.rpai." + sanitize(modelId) + "." + sanitize(animationName);
        spawned.forEach((playerId, entities) -> {
            Long geyserId = entities.get(hitboxId);
            if (geyserId == null) return;
            GeyserSession session = sessionOf(playerId);
            if (session == null) return;
            try {
                AnimateEntityPacket packet = new AnimateEntityPacket();
                packet.setAnimation(animationId);
                // The client-side controller /playanimation uses: play it, then
                // return to the default state when it reports finished.
                packet.setController("__runtime_controller");
                packet.setNextState("default");
                packet.setStopExpression("query.any_animation_finished");
                packet.setStopExpressionVersion(16777216);
                packet.setBlendOutTime(0f);
                packet.getRuntimeEntityIds().add(geyserId.longValue());
                session.sendUpstreamPacket(packet);
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("Couldn't animate Bedrock entity: " + e);
            }
        });
    }

    public void forgetPlayer(UUID playerId) {
        spawned.remove(playerId);
    }

    /**
     * Stops serving this player their staged pack. A Bedrock client only
     * loads packs while joining, so the closest thing to taking one back is
     * deleting the file the join path would hand them.
     */
    public boolean dropPack(UUID playerId) {
        try {
            GeyserConnection connection = GeyserApi.api().connectionByUuid(playerId);
            String xuid = connection == null ? null : connection.xuid();
            if (xuid == null || xuid.isEmpty()) return false;
            File file = packFile(xuid);
            return file.isFile() && file.delete();
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    private GeyserSession sessionOf(UUID playerId) {
        try {
            GeyserConnection connection = GeyserApi.api().connectionByUuid(playerId);
            return connection instanceof GeyserSession ? (GeyserSession) connection : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private void spawnFor(GeyserSession session, UUID playerId, Interaction hitbox, String modelId) {
        // Resolved through this viewer's own pack, so a viewer whose pack
        // lacks the model correctly sees nothing.
        Map<String, Integer> slots = slotsByXuid.get(session.xuid());
        Integer slot = slots != null ? slots.get(modelId) : null;
        if (slot == null) return;
        String identifier = "rpai:model_" + slot;
        Map<UUID, Long> entities = spawned.computeIfAbsent(playerId, k -> new HashMap<>());
        if (entities.containsKey(hitbox.getUniqueId())) return;
        try {
            long geyserId = session.getEntityCache().getNextEntityId().incrementAndGet();
            float yaw = rigYaw(hitbox);
            AddEntityPacket packet = new AddEntityPacket();
            packet.setIdentifier(identifier);
            packet.setUniqueEntityId(geyserId);
            packet.setRuntimeEntityId(geyserId);
            // The hitbox anchors at the block space's bottom center - the
            // same origin the converted geometry is authored around.
            packet.setPosition(Vector3f.from(hitbox.getLocation().getX(), hitbox.getLocation().getY(), hitbox.getLocation().getZ()));
            packet.setMotion(Vector3f.ZERO);
            packet.setRotation(Vector2f.from(0f, yaw));
            packet.setHeadRotation(yaw);
            packet.setBodyRotation(yaw);
            session.sendUpstreamPacket(packet);
            entities.put(hitbox.getUniqueId(), geyserId);
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("Couldn't spawn Bedrock entity " + identifier + ": " + e);
        }
    }

    /** Placement yaw, recovered from the rig's display entities (survives restarts). */
    private float rigYaw(Interaction hitbox) {
        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        String joined = pdc.get(new NamespacedKey(plugin, "display-uuids"), PersistentDataType.STRING);
        if (joined == null) joined = pdc.get(new NamespacedKey(plugin, "display-uuid"), PersistentDataType.STRING);
        if (joined == null) return 0f;
        for (String raw : joined.split(",")) {
            try {
                Entity display = Bukkit.getEntity(UUID.fromString(raw));
                if (!(display instanceof ItemDisplay)) continue;
                Float stored = display.getPersistentDataContainer().get(yawKey, PersistentDataType.FLOAT);
                return stored != null ? stored : display.getLocation().getYaw();
            } catch (IllegalArgumentException ignored) {
                // Malformed id - try the next one.
            }
        }
        return 0f;
    }
}
