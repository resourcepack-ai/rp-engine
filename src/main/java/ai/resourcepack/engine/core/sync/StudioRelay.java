package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.MergeResult;
import ai.resourcepack.engine.core.command.EngineCommand;
import ai.resourcepack.engine.core.emote.EmoteStore;
import ai.resourcepack.engine.core.model.RigStore;
import ai.resourcepack.engine.core.skin.SkinApplier;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final StudioContent content;
    private final SkinApplier skins;
    private final Path output;
    /** Serves a freshly downloaded pack. */
    private final Consumer<BuiltPack> register;
    /** Puts it on one player, stacked over whatever the server gave them. */
    private final BiConsumer<Player, BuiltPack> deliver;
    /** Puts a push's named content into the registry. Main thread. */
    private Runnable registerContent = () -> { };

    public StudioRelay(Plugin plugin, SyncClient sync, SyncGroup group, RigStore rigs,
                       EmoteStore emotes, StudioContent content, SkinApplier skins, Path output,
                       Consumer<BuiltPack> register, BiConsumer<Player, BuiltPack> deliver) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.sync = sync;
        this.group = group;
        this.rigs = rigs;
        this.emotes = emotes;
        this.content = content;
        this.skins = skins;
        this.output = output;
        this.register = register;
        this.deliver = deliver;
    }

    /**
     * What to run on the main thread to register a push's named content.
     *
     * <p>Set after construction because it is the plugin's registry this
     * writes into, and the plugin builds this object before it has finished
     * building itself.
     */
    public void onContent(Runnable register) {
        this.registerContent = register == null ? () -> { } : register;
    }

    /** A pack: fetch it, serve it, put it on whoever is on the ref. */
    public void onPush(String code, String payload) {
        if (!known(code)) {
            sync.failed(code, "unknown-code");
            return;
        }
        // <strong>All four downloads at once, and that is about a clock rather
        // than about throughput.</strong> The far end holds the HTTP request
        // that started this push open until the ack below goes back, and
        // reports a failure to the person watching if it does not arrive in
        // time. Fetched one after another, the three small manifests put three
        // full round trips in front of the pack — which is itself tens of
        // megabytes once a pack carries emote rigs — and the sum ran past that
        // window on an ordinary connection. A push that had worked was then
        // reported as having timed out, and the retry it provoked raced the
        // first one.
        //
        // Nothing here shares state: three different stores, three different
        // URLs, and a pack that lands in a file of its own. The MERGES are
        // still done in order on this thread once everything is in hand, so
        // what is registered does not depend on which download finished first.
        CompletableFuture<Optional<String>> rigsJson =
                fetchAsync(StudioPush.rigsUrl(payload));
        CompletableFuture<Optional<String>> emotesJson =
                fetchAsync(StudioPush.emotesUrl(payload));
        CompletableFuture<Optional<String>> contentJson =
                fetchAsync(StudioPush.contentUrl(payload));

        StudioPush.Fetch fetched = StudioPush.fetch(payload, output);

        // The manifests first: a pack whose art arrives without its keyframes
        // is a pack somebody can wear and not emote in, and the two came down
        // the same push. Different slots of it — see StudioPush.
        joined(rigsJson).ifPresent(json -> merged("Rigs", rigs.updateFromJson(json), () -> rigs.save(log)));
        joined(emotesJson)
                .ifPresent(json -> merged("Emotes", emotes.updateFromJson(json), () -> emotes.save(log)));
        // What the pack holds that a command can name. Registered on the main
        // thread below with everything else that touches shared state.
        joined(contentJson)
                .ifPresent(json -> merged("Pushed content", content.updateFromJson(json),
                        () -> content.save(log)));

        if (fetched.pack().isEmpty()) {
            sync.failed(code, fetched.reason());
            return;
        }
        BuiltPack pack = fetched.pack().get();
        onMainThread(() -> {
            registerContent.run();
            register.accept(pack);
            int reached = eachRecipient(code, player -> {
                deliver.accept(player, pack);
                EngineCommand.say(player, "Studio pushed a pack.");
                return true;
            });
            answer(reached, () -> sync.applied(code), why -> sync.failed(code, why));
        });
    }

    /** A give-command, run as the console against whoever the ref names. */
    public void onGive(String code, String command) {
        if (!known(code)) {
            sync.giveFailed(code, "unknown-code");
            return;
        }
        onMainThread(() -> {
            Player player = addressee(code);
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
                        EngineCommand.say(player, line);
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
     * Starts one manifest download, or answers immediately when the push
     * carried no such slot.
     *
     * <p>On the common pool rather than a pool of our own: there are at most
     * three of these in flight per push, they are pure I/O, and a plugin that
     * spawns an executor for something this small is a thread nobody shuts
     * down. Nothing here touches Bukkit — the results are merged back on the
     * websocket thread and applied on the main one.
     */
    private static CompletableFuture<Optional<String>> fetchAsync(Optional<String> url) {
        if (url.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String address = url.get();
        return CompletableFuture.supplyAsync(() -> StudioPush.fetchText(address));
    }

    /**
     * What a manifest download came back with, or empty if it could not be
     * had.
     *
     * <p>A failed one costs its own manifest and nothing else, which is the
     * rule {@link StudioPush#fetchText} already follows for a bad response —
     * the pack is still worth delivering without one of the three, and the
     * next push replaces it.
     */
    private Optional<String> joined(CompletableFuture<Optional<String>> pending) {
        try {
            return pending.join();
        } catch (CompletionException | CancellationException e) {
            log.warning("A pushed manifest could not be fetched: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Whether a ref names anybody at all, which is the whole of
     * {@code unknown-code}.
     *
     * <p><strong>A uuid always does.</strong> It is not checked against the
     * claimed codes, because reaching a player who never typed one is the
     * point of it: studio addresses a party by uuid the moment there is more
     * than one person on a sync, and it addresses a notification or a skin by
     * uuid always. Answering {@code unknown-code} for somebody standing right
     * there is what this shape used to do.
     */
    private boolean known(String ref) {
        return SyncCodes.isUuid(ref) || sync.claimant(ref) != null;
    }

    /**
     * The one player a ref is aimed at — the uuid itself, or the claimer of a
     * code. Null if they are not here.
     */
    private Player addressee(String ref) {
        UUID direct = SyncCodes.uuidOf(ref);
        if (direct != null) {
            return plugin.getServer().getPlayer(direct);
        }
        String claimant = sync.claimant(ref);
        return claimant == null ? null : plugin.getServer().getPlayerExact(claimant);
    }

    /**
     * Runs {@code work} for every online recipient of a ref: the whole sync
     * group for a code, and exactly that player for a uuid.
     *
     * @return how many it counted, {@code work} deciding whether it counts
     */
    private int eachRecipient(String code, Predicate<Player> work) {
        UUID direct = SyncCodes.uuidOf(code);
        if (direct != null) {
            Player player = plugin.getServer().getPlayer(direct);
            return player != null && work.test(player) ? 1 : 0;
        }
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
