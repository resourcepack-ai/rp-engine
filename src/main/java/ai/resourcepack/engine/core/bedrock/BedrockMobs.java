package ai.resourcepack.engine.core.bedrock;

import ai.resourcepack.common.scheduler.Tasks;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AnimateEntityPacket;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.session.GeyserSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Mobs wearing a model, as Bedrock players see them.
 *
 * <p>On Java a worn model is display entities riding an invisible vanilla body
 * (see {@code BoundModels}). Geyser draws no display entity, so to a Bedrock
 * player the mob was simply not there: an invisible polar bear, a name in the
 * air at most. Here the BODY is drawn as the model instead. When Geyser spawns
 * a mob that wears one of the server pack's models for a session, its Bedrock
 * definition is swapped ({@link ServerSpawnEntityEvent}) for
 * {@code rpengine:mob_<slot>}, the client entity {@link BedrockContent} built
 * from the same model. Geyser then moves, turns, hurts and kills it exactly as
 * it would the vanilla mob, hitbox and all, so it is hit where it stands and
 * nothing here sends a movement packet.
 *
 * <p>Three things Geyser cannot know, done here:
 * <ul>
 *   <li><strong>It must not be invisible.</strong> The vanilla body is, on
 *       purpose, and Geyser copies that flag every time the Java flags byte
 *       changes. Each tick, on the session's own thread, the flag is taken
 *       back off any entity drawn this way (internal API, fenced).</li>
 *   <li><strong>Its size</strong> is the model's, not the body's scale
 *       attribute, which a custom mob uses to size its hitbox.</li>
 *   <li><strong>Its animation</strong> is the one the Java rig plays, sent as
 *       it changes and again when a viewer first sees the mob.</li>
 * </ul>
 *
 * <p>Only models in the server's own Bedrock pack cross: a Studio mob's model
 * lives in Studio's pack, which defines no mob entity, and stays as it was.
 */
final class BedrockMobs {

    /** What one host wears. */
    private static final class Worn {
        final String modelId;
        final float scale;
        volatile String animation;
        volatile boolean ends;

        Worn(String modelId, float scale) {
            this.modelId = modelId;
            this.scale = scale;
        }
    }

    private final JavaPlugin plugin;
    /** The server build a session was served, by xuid. */
    private final Function<String, BedrockContent.Result> contentOf;
    private final NamespacedKey boundKey;
    private final NamespacedKey modelKey;
    private final NamespacedKey scaleKey;

    /** Every host wearing a model, by its uuid. */
    private final Map<UUID, Worn> worn = new ConcurrentHashMap<>();
    /** viewer -> host -> the geyser id it is drawn as for them. */
    private final Map<UUID, Map<UUID, Long>> drawn = new ConcurrentHashMap<>();
    /** The registered definitions, by slot; Geyser matches the instance it was given. */
    private final CustomEntityDefinition[] definitions = new CustomEntityDefinition[BedrockContent.MODEL_POOL + 1];

    private Tasks.Handle tick;

    BedrockMobs(JavaPlugin plugin, Function<String, BedrockContent.Result> contentOf) {
        this.plugin = plugin;
        this.contentOf = contentOf;
        this.boundKey = new NamespacedKey(plugin, "rig-bound");
        this.modelKey = new NamespacedKey(plugin, "model-id");
        this.scaleKey = new NamespacedKey(plugin, "rig-scale");
        this.tick = Tasks.globalTimer(plugin, this::reveal, 1, 1);
    }

    void shutdown() {
        if (tick != null) {
            tick.cancel();
            tick = null;
        }
    }

    /** The mob pool, registered beside the placed-model pools at Geyser's boot. */
    int define(GeyserDefineEntitiesEvent event) {
        int count = 0;
        for (int slot = 1; slot <= BedrockContent.MODEL_POOL; slot++) {
            try {
                CustomEntityDefinition definition = CustomEntityDefinition.of(BedrockContent.mobIdentifier(slot));
                event.register(definition);
                definitions[slot] = definition;
                count++;
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("Couldn't register Bedrock mob slot " + slot + ": " + e);
            }
        }
        return count;
    }

    // --- What the rig code says -------------------------------------------------

    void worn(Entity host, String modelId, float scale) {
        if (host == null || modelId == null) return;
        worn.put(host.getUniqueId(), new Worn(modelId, scale > 0 ? scale : 1f));
        // Somebody already looking at it holds it as its vanilla self: a
        // bind on a mob that was standing there. Sent again, it is redrawn.
        redrawForViewers(host, false);
    }

    void unworn(Entity host) {
        if (host == null || worn.remove(host.getUniqueId()) == null) return;
        redrawForViewers(host, true);
    }

    void animation(Entity host, String animation, boolean ends) {
        if (host == null) return;
        Worn known = worn.get(host.getUniqueId());
        if (known == null) {
            // Loaded wearing it, so nothing said "worn": its first animation does.
            Worn adopted = adopt(host);
            if (adopted == null) return;
            adopted.animation = animation;
            adopted.ends = ends;
            redrawForViewers(host, false);
            return;
        }
        Worn w = known;
        w.animation = animation;
        w.ends = ends;
        drawn.forEach((viewer, hosts) -> {
            Long geyserId = hosts.get(host.getUniqueId());
            GeyserSession session = geyserId == null ? null : sessionOf(viewer);
            if (session != null) session.executeInEventLoop(() -> animate(session, geyserId, w));
        });
    }

    void forget(UUID viewer) {
        drawn.remove(viewer);
    }

    /**
     * Notices worn models near a Bedrock viewer that the rig code never
     * reported: ones that were already wearing them when they loaded (a
     * restart, a chunk coming back). On the viewer's thread, every second.
     */
    void scan(Player viewer, double range) {
        for (Entity nearby : viewer.getWorld().getNearbyEntities(viewer.getLocation(), range, range, range,
                e -> e instanceof ItemDisplay)) {
            PersistentDataContainer pdc = nearby.getPersistentDataContainer();
            if (!pdc.has(boundKey, PersistentDataType.STRING)) continue;
            Entity host = nearby.getVehicle();
            if (host == null || worn.containsKey(host.getUniqueId())) continue;
            if (adopt(host) != null) redraw(viewer, host);
        }
    }

    /** Registers what {@code host} is found wearing, read off its parts; null when it wears nothing of ours. */
    private Worn adopt(Entity host) {
        for (Entity rider : host.getPassengers()) {
            if (!(rider instanceof ItemDisplay)) continue;
            PersistentDataContainer pdc = rider.getPersistentDataContainer();
            if (!pdc.has(boundKey, PersistentDataType.STRING)) continue;
            String modelId = pdc.get(modelKey, PersistentDataType.STRING);
            if (modelId == null) continue;
            Float scale = pdc.get(scaleKey, PersistentDataType.FLOAT);
            Worn w = new Worn(modelId, scale == null || scale <= 0 ? 1f : scale);
            Worn raced = worn.putIfAbsent(host.getUniqueId(), w);
            return raced != null ? raced : w;
        }
        return null;
    }

    // --- Geyser's half -------------------------------------------------------------

    /** Geyser is about to spawn a Java entity for one session: if it wears a model, it is drawn as it. */
    void onSpawn(ServerSpawnEntityEvent event) {
        Worn w = worn.get(event.uuid());
        if (w == null) return;
        GeyserConnection connection = event.connection();
        BedrockContent.Result content = connection.xuid() == null ? null : contentOf.apply(connection.xuid());
        Integer slot = content == null ? null : content.modelSlots().get(w.modelId);
        if (slot == null || slot < 1 || slot >= definitions.length || definitions[slot] == null) return;
        if (!(connection instanceof GeyserSession session)) return;
        UUID viewer = connection.javaUuid();
        UUID host = event.uuid();
        try {
            event.definition(definitions[slot]);
            event.preSpawnConsumer(entity -> {
                try {
                    // The model's size, not the body's: the body's scale
                    // attribute is there to fit its hitbox to the model.
                    entity.override(GeyserEntityDataTypes.SCALE, w.scale);
                } catch (Exception | LinkageError e) {
                    plugin.getLogger().fine("Couldn't size Bedrock mob: " + e);
                }
                long geyserId = entity.geyserId();
                drawn.computeIfAbsent(viewer, k -> new ConcurrentHashMap<>()).put(host, geyserId);
                // After the spawn and the metadata that follows it: visible, and posed.
                session.scheduleInEventLoop(() -> {
                    show(session, geyserId);
                    animate(session, geyserId, w);
                }, 100, TimeUnit.MILLISECONDS);
            });
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("Couldn't draw a mob's model for Bedrock: " + e);
        }
    }

    /** Takes the invisible flag back off every drawn mob, once a tick, on each session's thread. */
    private void reveal() {
        drawn.forEach((viewer, hosts) -> {
            if (hosts.isEmpty()) return;
            GeyserSession session = sessionOf(viewer);
            if (session == null) {
                drawn.remove(viewer);
                return;
            }
            session.executeInEventLoop(() -> hosts.entrySet().removeIf(entry -> !show(session, entry.getValue())));
        });
    }

    /** Clears the flag on one entity; false when the session no longer has it. Session thread only. */
    private boolean show(GeyserSession session, long geyserId) {
        try {
            org.geysermc.geyser.entity.type.Entity entity = session.getEntityCache().getEntityByGeyserId(geyserId);
            if (entity == null) return false;
            if (entity.getFlag(EntityFlag.INVISIBLE)) {
                entity.setFlag(EntityFlag.INVISIBLE, false);
                entity.updateBedrockMetadata();
            }
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** Plays what the host's rig is playing, on the session's thread. */
    private void animate(GeyserSession session, long geyserId, Worn w) {
        String key = BedrockContent.modelKey(w.modelId);
        String animation = w.animation;
        try {
            AnimateEntityPacket packet = new AnimateEntityPacket();
            packet.setAnimation(animation == null ? BedrockContent.restAnimation(key)
                    : BedrockGeometry.animationId(key, animation));
            // The controller /playanimation uses, so each one replaces the
            // last. A one-shot goes back to rest when it ends, as on Java; a
            // loop runs until the next, and a held one keeps its last frame.
            packet.setController("__runtime_controller");
            packet.setNextState("default");
            packet.setStopExpression(animation == null || w.ends ? "query.any_animation_finished" : "0");
            packet.setStopExpressionVersion(16777216);
            packet.setBlendOutTime(0f);
            packet.getRuntimeEntityIds().add(geyserId);
            session.sendUpstreamPacket(packet);
        } catch (Exception | LinkageError e) {
            plugin.getLogger().fine("Couldn't animate a Bedrock mob: " + e);
        }
    }

    // --- Redrawing -----------------------------------------------------------------

    /**
     * Has every Bedrock player who can see {@code host} sent it again, so
     * Geyser spawns it afresh and asks {@link #onSpawn} what to draw.
     * {@code onlyDrawn} limits it to viewers drawing it as a model now.
     */
    private void redrawForViewers(Entity host, boolean onlyDrawn) {
        for (Player viewer : host.getWorld().getPlayers()) {
            boolean ours = drawn.getOrDefault(viewer.getUniqueId(), Map.of()).containsKey(host.getUniqueId());
            if (onlyDrawn && !ours) continue;
            if (!onlyDrawn && ours) continue;
            GeyserSession session = sessionOf(viewer.getUniqueId());
            if (session == null) continue;
            if (!onlyDrawn && !holds(session, host.getUniqueId())) continue;
            if (onlyDrawn) drawn.get(viewer.getUniqueId()).remove(host.getUniqueId());
            redraw(viewer, host);
        }
    }

    /** Whether the session has a Geyser entity for this Java uuid at all. */
    private static boolean holds(GeyserSession session, UUID uuid) {
        try {
            return session.getEntityCache().getEntityByUuid(uuid) != null;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    private void redraw(Player viewer, Entity host) {
        Tasks.on(plugin, viewer, () -> {
            if (!viewer.isOnline() || !host.isValid()) return;
            viewer.hideEntity(plugin, host);
            Tasks.onLater(plugin, viewer, () -> {
                if (viewer.isOnline() && host.isValid()) viewer.showEntity(plugin, host);
            }, 2);
        });
    }

    private static GeyserSession sessionOf(UUID playerId) {
        try {
            GeyserConnection connection = GeyserApi.api().connectionByUuid(playerId);
            return connection instanceof GeyserSession ? (GeyserSession) connection : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }
}
