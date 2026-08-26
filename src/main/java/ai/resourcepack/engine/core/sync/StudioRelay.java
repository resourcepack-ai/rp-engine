package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.MergeResult;
import ai.resourcepack.engine.core.emote.EmoteStore;
import ai.resourcepack.engine.core.model.RigStore;
import ai.resourcepack.engine.core.skin.SkinApplier;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * The four things Studio can ask a paired server to do: apply a pack, run a
 * give, put a skin on somebody, and say something in chat.
 *
 * <p>Each arrives on the websocket thread, so everything that touches a player
 * hops back to the main thread. Downloading deliberately does not: a pack is
 * megabytes, and blocking the server on it would be a lag spike every time
 * somebody clicks push in the panel.
 *
 * <p>All four answer the far end either way. A push that reaches nobody is a
 * failure with a reason, not a silence — the panel is a person waiting to see
 * whether the thing they clicked worked.
 */
public final class StudioRelay {

    private final Plugin plugin;
    private final Logger log;
    private final SyncClient sync;
    private final SyncGroup group;
    private final RigStore rigs;
    private final EmoteStore emotes;
    private final SkinApplier skins;
    private final Path output;
    /** Serves a freshly downloaded pack. */
    private final Consumer<BuiltPack> register;
    /** Puts it on one player, stacked over whatever the server gave them. */
    private final BiConsumer<Player, BuiltPack> deliver;

    public StudioRelay(Plugin plugin, SyncClient sync, SyncGroup group, RigStore rigs,
                       EmoteStore emotes, SkinApplier skins, Path output,
                       Consumer<BuiltPack> register, BiConsumer<Player, BuiltPack> deliver) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.sync = sync;
        this.group = group;
        this.rigs = rigs;
        this.emotes = emotes;
        this.skins = skins;
        this.output = output;
        this.register = register;
        this.deliver = deliver;
    }

    /** A pack: fetch it, serve it, put it on whoever is on the code. */
    public void onPush(String code, String payload) {
        if (sync.claimant(code) == null) {
            sync.failed(code, "unknown-code");
            return;
        }
        // The manifests first: a pack whose art arrives without its keyframes
        // is a pack somebody can wear and not emote in, and the two came down
        // the same push. Different slots of it — see StudioPush.
        StudioPush.rigsUrl(payload).flatMap(StudioPush::fetchText)
                .ifPresent(json -> merged("Rigs", rigs.updateFromJson(json), () -> rigs.save(log)));
        StudioPush.emotesUrl(payload).flatMap(StudioPush::fetchText)
                .ifPresent(json -> merged("Emotes", emotes.updateFromJson(json), () -> emotes.save(log)));

        StudioPush.Fetch fetched = StudioPush.fetch(payload, output);
        if (fetched.pack().isEmpty()) {
            sync.failed(code, fetched.reason());
            return;
        }
        BuiltPack pack = fetched.pack().get();
        onMainThread(() -> {
            register.accept(pack);
            int reached = eachRecipient(code, player -> {
                deliver.accept(player, pack);
                player.sendMessage("[RPEngine] Studio pushed a pack.");
                return true;
            });
            answer(reached, () -> sync.applied(code), why -> sync.failed(code, why));
        });
    }

    /** A give-command, run as the console against the player who claimed the code. */
    public void onGive(String code, String command) {
        String claimant = sync.claimant(code);
        if (claimant == null) {
            sync.giveFailed(code, "unknown-code");
            return;
        }
        onMainThread(() -> {
            Player player = plugin.getServer().getPlayerExact(claimant);
            if (player == null) {
                sync.giveFailed(code, "player-offline");
                return;
            }
            try {
                // "execute at" so that @p in the command resolves against the
                // player's own position rather than the console's, which is
                // nowhere.
                String resolved = command.startsWith("/") ? command.substring(1) : command;
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                        "execute at " + player.getName() + " run " + resolved);
                sync.given(code);
            } catch (RuntimeException e) {
                log.warning("Studio give failed: " + e.getMessage());
                sync.giveFailed(code, "exception");
            }
        });
    }

    /**
     * A signed skin: the player wears it.
     *
     * <p>The value and signature are Mojang's own, fetched and signed by
     * studio. Nothing here validates them beyond their shape — the game does
     * that when the profile is applied, and a second opinion invented here
     * would be a guess about somebody else's cryptography.
     */
    public void onSkin(String code, String payload) {
        String[] texture = payload.split(" ", 2);
        if (texture.length < 2 || texture[0].isEmpty() || texture[1].isEmpty()) {
            sync.skinFailed(code, "bad-payload");
            return;
        }
        onMainThread(() -> {
            int worn = eachRecipient(code, player -> skins.apply(player, texture[0], texture[1]).ok);
            answer(worn, () -> sync.skinned(code), why -> sync.skinFailed(code, why));
        });
    }

    /**
     * A line of chat — usually that a model somebody walked away from has
     * finished.
     *
     * <p>The payload is JSON because a notification is prose and its fields
     * contain spaces. The shape is the plugin's to own, which is why the
     * wording is here and not in the protocol.
     */
    public void onTell(String code, String json) {
        String title = string(json, "title");
        if (title == null) {
            sync.tellFailed(code, "bad-payload");
            return;
        }
        String body = string(json, "body");
        String url = string(json, "url");
        onMainThread(() -> {
            int told = eachRecipient(code, player -> {
                for (String line : List.of(title, body == null ? "" : body, url == null ? "" : url)) {
                    if (!line.isEmpty()) {
                        player.sendMessage("[RPEngine] " + line);
                    }
                }
                return true;
            });
            answer(told, () -> sync.told(code), why -> sync.tellFailed(code, why));
        });
    }

    private void onMainThread(Runnable work) {
        plugin.getServer().getScheduler().runTask(plugin, work);
    }

    /**
     * Runs {@code work} for every online recipient of a code.
     *
     * @return how many it counted, {@code work} deciding whether it counts
     */
    private int eachRecipient(String code, Predicate<Player> work) {
        int reached = 0;
        for (String name : group.recipients(code)) {
            Player player = plugin.getServer().getPlayerExact(name);
            if (player != null && work.test(player)) {
                reached++;
            }
        }
        return reached;
    }

    /** Nobody reached is a failure with a reason, never a silence. */
    private static void answer(int reached, Runnable ok, Consumer<String> failed) {
        if (reached == 0) {
            failed.accept("player-offline");
        } else {
            ok.run();
        }
    }

    private void merged(String what, MergeResult result, Runnable save) {
        if (result.ok()) {
            save.run();
            log.info(what + ": " + result.count() + " from " + result.packId() + ".");
        } else {
            log.warning(what + " manifest: " + result.error());
        }
    }

    /**
     * One string field out of a small, known JSON object.
     *
     * <p>Gson would do this properly and is already on the classpath, but the
     * payload is three fields written by us and read by us, and a parse that
     * throws on a field somebody adds later is worse here than one that simply
     * does not find it.
     */
    private static String string(String json, String field) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonElement value = parsed.getAsJsonObject().get(field);
            return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
