package ai.resourcepack.engine.core.distribution;

import ai.resourcepack.common.scheduler.Tasks;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ai.resourcepack.engine.core.serve.PackSending;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.CommandSender;

/**
 * Serving a published pack to everybody who joins this server.
 *
 * <p>Distinct from {@code /link}, which pushes one pack to one player for
 * testing. This is the pack the server actually runs: bound once with
 * {@code /rpai distribute <code>}, and from then on every joining player gets the
 * artifact built for the version they are on.
 *
 * <h2>Three things that are deliberate</h2>
 *
 * <p><b>The manifest is cached and joins are answered locally.</b> Nothing
 * about a player joining involves a call to studio. That is the difference
 * between "the studio is down and nobody can join this server" and "the studio
 * is down and everything keeps working", and it is why the manifest carries
 * every version at once instead of offering a per-player lookup.
 *
 * <p><b>Reports are batched, never per event.</b> A minute's worth of counters
 * goes up in one request. They are deltas and studio increments rather than
 * sets, so a restart mid-window loses that window instead of resetting
 * somebody's history.
 *
 * <p><b>The pack is applied under its own id</b>, not the one
 * {@code PresencePlugin} pushes /link packs under. A player testing a pack in
 * the studio while standing on a server that distributes another one should
 * end up holding both, and removing one must not remove the other.
 */
public final class DistributionManager implements Listener {

    private static final Gson GSON = new Gson();

    /** Fixed, so exactly this pack can be removed and no other. */
    private static final UUID DISTRIBUTION_PACK_ID =
            UUID.fromString("6b1e0d64-9d9a-4f1a-bd5e-3a3d1d1c9f01");

    /** Where the token lives. Not config.yml — see the class comment on why. */
    private static final String STATE_FILE = "distribution.yml";

    private static final long REPORT_INTERVAL_TICKS = 20L * 60;

    /**
     * How stale the manifest may get when there is no report to carry the
     * answer: a minute with somebody online, five with nobody. See flushReports.
     */
    private static final long IDLE_REFRESH_ONLINE_MS = 60_000L;
    private static final long IDLE_REFRESH_EMPTY_MS = 5 * 60_000L;

    /** When the manifest was last asked for, so an idle server still notices a publish. */
    private volatile long lastRefresh;

    private final JavaPlugin plugin;
    private final BedrockSupport bedrock;
    private final ProtocolResolver protocols;
    private final DistributionClient client;

    private volatile String token;
    private volatile Manifest manifest = Manifest.empty();

    /** Pending report deltas, keyed by player. Written from the main thread. */
    private final Map<UUID, PendingReport> pending = new ConcurrentHashMap<>();

    /** Players we handed a distribution pack to and are awaiting a reply from. */
    private final Map<UUID, Boolean> awaiting = new ConcurrentHashMap<>();

    /**
     * Players whose client said it loaded the published pack.
     *
     * <p>Kept because <b>a published pack is a Studio pack too</b>, and the
     * things that need one — a shader overlay's glyph, most obviously — have
     * to be drawn for these players as much as for the one who ran the sync.
     * Nothing else here would say so: distribution sends its pack directly
     * rather than through the bundle machinery, so the engine's record of who
     * holds what has never heard of these players.
     *
     * <p>Only SUCCESSFULLY_LOADED goes in. A client that declined or failed the
     * download is one with no glyph, which is exactly the case this exists to
     * keep an overlay away from.
     */
    private final Map<UUID, Boolean> loaded = new ConcurrentHashMap<>();

    /**
     * Which Studio pack this server publishes, as the manifest names it; null
     * when it does not say (a Studio older than the field), or nothing is bound.
     *
     * <p>What lets a pushed screen be drawn for somebody holding the published
     * pack only when it came from that same pack — rather than whatever was
     * pushed last, which is a different pack whenever somebody tests one while
     * the server publishes another.
     */
    public String publishedPackId() {
        Manifest current = manifest;
        // Mirrored, the pack is folder content and everybody's already.
        return isBound() && !mirroring() && current.enabled && !current.packId.isEmpty() ? current.packId : null;
    }

    /** The Studio pack this server is bound to, by its slug, whatever the mode; null when none. */
    public String boundPackId() {
        Manifest current = manifest;
        return isBound() && !current.packId.isEmpty() ? current.packId : null;
    }

    /**
     * Where the published pack is kept as content, or null when it is served
     * straight from the CDN ({@code distribution.mode: cdn}). See
     * {@link StudioPackMirror}.
     */
    private volatile StudioPackMirror mirror;

    /** Rebuilds the engine's pack after the mirror changed. Run on the global thread. */
    private volatile Runnable afterMirror;

    /** Whether a pack id is one of the engine's own, sent to that player. */
    private volatile java.util.function.BiPredicate<Player, UUID> enginePack;

    /** One download at a time: a refresh and a report can both notice a new release. */
    private final Object mirrorLock = new Object();

    /** The server version last warned about having no artifact of its own, so it is said once. */
    private volatile String warnedVersion = "";

    /**
     * Keep the published pack in {@code content/} and serve it inside the
     * engine's own pack, rather than as a second pack from the CDN. Called
     * once, before {@link #start()}.
     */
    public void mirrorInto(StudioPackMirror into, Runnable rebuild,
                           java.util.function.BiPredicate<Player, UUID> ownPack) {
        this.mirror = into;
        this.afterMirror = rebuild;
        this.enginePack = ownPack;
    }

    private boolean mirroring() {
        return mirror != null;
    }

    /** Whether this player is holding the published pack. See {@link #loaded}. */
    public boolean serving(UUID player) {
        return player != null && loaded.containsKey(player);
    }

    /** Forgets a player who left. Called from the plugin's quit handler. */
    public void forget(UUID player) {
        if (player != null) {
            awaiting.remove(player);
            loaded.remove(player);
            earlyOutcome.remove(player);
        }
    }

    /** Mirrored: the engine pack's answer for a player whose join serve() has not counted yet. */
    private final Map<UUID, String> earlyOutcome = new ConcurrentHashMap<>();

    /**
     * Who is told the published pack's content manifest: the plugin, which
     * reads it as it reads a push. Called on the main thread.
     *
     * <p>Without it a published pack's shader objects, screens and dialogs
     * reached the engine only if somebody also pressed Sync on that pack, and
     * the zips the players were served carried pictures nobody could show.
     */
    private volatile java.util.function.Consumer<String> contentListener;

    /** The release whose content manifest was last asked for, so it is asked once. */
    private volatile String contentRelease = "";

    public void onContent(java.util.function.Consumer<String> listener) {
        this.contentListener = listener;
    }

    /** How a pack reaches a player here; see {@link PackSending}. */
    private final PackSending sending;

    public DistributionManager(JavaPlugin plugin, BedrockSupport bedrock,
                               ProtocolResolver protocols, String apiBase,
                               PackSending sending) {
        this.bedrock = bedrock == null ? BedrockSupport.NONE : bedrock;
        this.plugin = plugin;
        this.protocols = protocols;
        this.sending = sending;
        this.client = new DistributionClient(apiBase, plugin.getDescription().getVersion());
        this.token = readToken();
    }

    public boolean isBound() {
        return token != null && !token.isEmpty();
    }

    /** Start the refresh and report loops. Safe to call with no binding. */
    public void start() {
        Tasks.asyncTimer(plugin, this::flushReports,
                REPORT_INTERVAL_TICKS, REPORT_INTERVAL_TICKS);
        if (isBound()) {
            refreshAsync(null);
        }
    }

    /** Called on shutdown so the last minute of play data isn't dropped. */
    public void shutdown() {
        if (isBound()) {
            flushReports();
        }
    }

    // -----------------------------------------------------------------------
    // Binding
    // -----------------------------------------------------------------------

    /**
     * {@code /rpai distribute <code>}. Runs the claim off the main thread and
     * reports back in chat.
     */
    public void claim(CommandSender sender, String code) {
        Tasks.async(plugin, () -> {
            try {
                DistributionClient.ClaimResult result = client.claim(
                        code, protocols.serverVersion(), protocols.hasVia(), bedrock.available());
                this.token = result.token;
                writeToken(result.token);
                this.manifest = Manifest.parse(result.manifest);
                contentRelease = "";
                String folder = null;
                if (mirroring()) {
                    syncMirror(manifest);
                    folder = manifest.releaseId.equals(mirror.releaseId()) ? mirror.folder() : null;
                } else if (!manifest.releaseId.isEmpty()) {
                    fetchContent(manifest.releaseId);
                }
                String into = folder;

                Tasks.global(plugin, () -> {
                    // Binding now normally happens BEFORE anything is
                    // published (the studio's Serve-a-pack modal binds
                    // first), so "not serving yet" is the expected state and
                    // must not read as a warning. One message per situation,
                    // not a success line with caveats stacked under it.
                    // result.name is this SERVER's name on the panel, not the
                    // pack's — don't print it as if it were a pack.
                    if (manifest.enabled && !manifest.entries.isEmpty() && into != null) {
                        sender.sendMessage("Server bound. The pack is in content/" + into
                                + "/ and is served to everyone who joins.");
                    } else if (manifest.enabled && !manifest.entries.isEmpty() && mirroring()) {
                        sender.sendMessage("Server bound, but the pack could not be put in content/ yet. "
                                + "The console says why; it is tried again every minute.");
                    } else if (manifest.enabled && !manifest.entries.isEmpty()) {
                        sender.sendMessage("Server bound. "
                                + "Serving the pack to everyone who joins.");
                    } else {
                        sender.sendMessage(""
                                + "Server bound, ready for distribution. "
                                + "Head back to the studio to finish setting up.");
                    }
                    // Anybody already standing here gets it now rather than on
                    // their next join: somebody binding a server mid-session
                    // means "serve this to my players", and the players are
                    // right there.
                    applyToEveryoneOnline();
                });
            } catch (DistributionClient.DistributionException e) {
                Tasks.global(plugin, () ->
                        sender.sendMessage(e.getMessage()));
            }
        });
    }

    /**
     * Forget the binding. The studio side is unbound from the tab. A
     * mirrored pack's folder goes too, unless {@code keep}, which leaves it as
     * the owner's own content.
     */
    public void unbind(CommandSender sender, boolean keep) {
        this.token = null;
        this.manifest = Manifest.empty();
        contentRelease = "";
        writeToken(null);
        StudioPackMirror into = mirror;
        if (into == null) {
            sender.sendMessage("This server is no longer serving a pack. "
                    + "Players keep what they already downloaded until they rejoin.");
            return;
        }
        Tasks.async(plugin, () -> {
            String folder;
            boolean had;
            synchronized (mirrorLock) {
                folder = into.folder();
                try {
                    had = into.remove(keep);
                } catch (IOException e) {
                    Tasks.global(plugin, () -> sender.sendMessage("This server is no longer serving a pack, but content/"
                            + folder + "/ could not be cleared: " + e.getMessage()));
                    return;
                }
            }
            Tasks.global(plugin, () -> {
                if (!had) {
                    sender.sendMessage("This server is no longer serving a pack.");
                } else if (keep) {
                    sender.sendMessage("This server is no longer bound. content/" + folder
                            + "/ is yours now and will not be updated.");
                } else {
                    sender.sendMessage("This server is no longer serving a pack, and content/" + folder
                            + "/ is gone. Anything changed in it by hand is in " + StudioPackMirror.BACKUPS + "/.");
                }
                Runnable rebuild = afterMirror;
                if (had && !keep && rebuild != null) {
                    rebuild.run();
                }
            });
        });
    }

    /** Re-read the manifest, e.g. after a publish. */
    public void refreshAsync(Runnable then) {
        if (!isBound()) {
            return;
        }
        lastRefresh = System.currentTimeMillis();
        Tasks.async(plugin, () -> {
            try {
                Manifest fresh = Manifest.parse(client.manifest(token, protocols.serverVersion()));
                boolean changed = !fresh.releaseId.equals(manifest.releaseId);
                this.manifest = fresh;
                if (mirroring()) {
                    syncMirror(fresh);
                } else if (!fresh.releaseId.isEmpty() && !fresh.releaseId.equals(contentRelease)) {
                    fetchContent(fresh.releaseId);
                }
                if (changed) {
                    // A new release is the whole point of live republish:
                    // everybody standing here gets it without rejoining.
                    Tasks.global(plugin, this::applyToEveryoneOnline);
                }
                if (then != null) {
                    Tasks.global(plugin, then);
                }
            } catch (DistributionClient.DistributionException e) {
                // A failed refresh keeps the manifest we already have. That is
                // the entire reason it is cached: we can be unreachable and
                // this server keeps serving the last thing it was told.
                plugin.getLogger().warning("Couldn't refresh the distribution manifest: " + e.getMessage());
            }
        });
    }

    /**
     * Brings the mirrored folder in line with {@code current}: the live
     * release unpacked into it, or the folder gone when nothing is served any
     * more. Off the main thread; a failure is tried again at the next report.
     */
    private void syncMirror(Manifest current) {
        StudioPackMirror into = mirror;
        if (into == null || !isBound()) {
            return;
        }
        synchronized (mirrorLock) {
            try {
                if (!current.enabled || current.releaseId.isEmpty()) {
                    if (into.remove(false)) {
                        plugin.getLogger().info("The published pack is not being served any more, "
                                + "so its folder was taken out of content/.");
                        mirrored();
                    }
                    return;
                }
                if (current.releaseId.equals(into.releaseId())) {
                    return;
                }
                Manifest.Entry entry = mirrorEntry(current);
                if (entry == null) {
                    plugin.getLogger().warning("The published pack has no Java version, so nothing was put in content/.");
                    return;
                }
                java.nio.file.Path zip = plugin.getDataFolder().toPath().resolve("distribution-download.zip");
                client.download(entry.url, entry.sha1, zip);
                String content = client.content(token);
                StudioPackMirror.Installed done = into.install(zip, current.packId, current.releaseId, content);
                java.nio.file.Files.deleteIfExists(zip);
                plugin.getLogger().info("The published pack (built for Minecraft " + entry.version
                        + ") is in content/" + done.folder() + "/.");
                if (done.keptEdits() > 0) {
                    plugin.getLogger().warning(done.keptEdits() + " file(s) changed by hand in content/" + done.folder()
                            + "/ were replaced by the new version. Copies are in "
                            + plugin.getDataFolder().toPath().relativize(done.backup()) + ".");
                }
                mirrored();
            } catch (DistributionClient.DistributionException | IOException e) {
                plugin.getLogger().warning("Couldn't put the published pack in content/: " + e.getMessage());
            }
        }
    }

    /** After the folder changed: the published content is the folder's now, and the engine's pack is rebuilt. */
    private void mirrored() {
        java.util.function.Consumer<String> listener = contentListener;
        Runnable rebuild = afterMirror;
        Tasks.global(plugin, () -> {
            if (listener != null) {
                listener.accept(null);
            }
            if (rebuild != null) {
                rebuild.run();
            }
        });
    }

    /**
     * Which of the release's artifacts goes in content/: the one built for
     * this server's version, else the newest at or below it, else the oldest
     * above. The engine's pack is what players get, so it is the server's
     * version that decides, not any one player's.
     */
    private Manifest.Entry mirrorEntry(Manifest current) {
        String server = protocols.serverVersion();
        Manifest.Entry exact = current.byVersion(server);
        if (exact != null) {
            return exact;
        }
        int[] want = versionParts(server);
        if (want == null) {
            want = new int[]{Integer.MAX_VALUE};
        }
        Manifest.Entry below = null;
        Manifest.Entry above = null;
        int[] belowAt = null;
        int[] aboveAt = null;
        for (Manifest.Entry entry : current.entries) {
            int[] at = versionParts(entry.version);
            if (at == null) {
                continue;
            }
            if (compareVersions(at, want) <= 0) {
                if (below == null || compareVersions(at, belowAt) > 0) {
                    below = entry;
                    belowAt = at;
                }
            } else if (above == null || compareVersions(at, aboveAt) < 0) {
                above = entry;
                aboveAt = at;
            }
        }
        Manifest.Entry chosen = below != null ? below : above;
        if (chosen != null && !server.equals(warnedVersion)) {
            warnedVersion = server;
            plugin.getLogger().warning("The pack was not published for Minecraft " + server
                    + ", so the version built for " + chosen.version + " is used. Tick " + server
                    + " when publishing in Studio for an exact match.");
        }
        return chosen;
    }

    /** "1.21.4" as {1, 21, 4}; null for anything that is not dotted numbers ("bedrock"). */
    static int[] versionParts(String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }
        String[] parts = version.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** On the refresh's own thread; a failure is tried again at the next refresh. */
    private void fetchContent(String releaseId) {
        String json;
        try {
            json = client.content(token);
        } catch (DistributionClient.DistributionException e) {
            plugin.getLogger().warning("Couldn't fetch the published pack's content: " + e.getMessage());
            return;
        }
        contentRelease = releaseId;
        java.util.function.Consumer<String> listener = contentListener;
        // Null too: a release with no content must stop the last one's being drawn.
        if (listener != null) {
            Tasks.global(plugin, () -> listener.accept(json));
        }
    }

    // -----------------------------------------------------------------------
    // Serving
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!isBound() || !manifest.enabled) {
            return;
        }
        // A tick late, deliberately: a client that is handed a pack in the
        // same tick it joins can miss it, and Geyser's session isn't settled
        // either.
        Tasks.onLater(plugin, event.getPlayer(), () -> serve(event.getPlayer()), 20L);
    }

    private void applyToEveryoneOnline() {
        // Each on their own thread, which on Folia is their region's.
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Mirrored, a Java player is re-sent the engine's own pack by its
            // rebuild; only Bedrock still takes the published one.
            if (mirroring() && !bedrock.isBedrock(player.getUniqueId())) {
                continue;
            }
            Tasks.now(plugin, player, () -> serve(player));
        }
    }

    private void serve(Player player) {
        if (!player.isOnline() || !isBound() || !manifest.enabled) {
            return;
        }

        boolean isBedrock = bedrock.isBedrock(player.getUniqueId());
        int protocol = protocols.protocolOf(player);
        Manifest.Entry entry = isBedrock
                ? manifest.byVersion("bedrock")
                : resolveJavaEntry(protocol);

        if (entry == null) {
            // Nothing published for what they are running. Recorded rather
            // than ignored: "23 players joined on a version you don't
            // support" is the single most actionable line on the dashboard,
            // and it can only exist if this case reports itself.
            record(player, null, protocol, "unsupported");
            return;
        }

        record(player, entry.version, protocol, "pending");

        if (isBedrock) {
            bedrock.applyPack(player, entry.url);
            return;
        }
        if (mirroring()) {
            // The pack is inside the engine's own, which the engine sends as
            // the player joins, so its answer has usually come in already.
            String answered = earlyOutcome.remove(player.getUniqueId());
            if (answered != null) {
                outcome(player.getUniqueId(), answered);
            } else {
                awaiting.put(player.getUniqueId(), Boolean.TRUE);
            }
            return;
        }
        try {
            sending.send(player, DISTRIBUTION_PACK_ID, entry.url, hexToBytes(entry.sha1), null, false);
            awaiting.put(player.getUniqueId(), Boolean.TRUE);
        } catch (Exception e) {
            record(player, entry.version, protocol, "failed");
            plugin.getLogger().warning("Couldn't serve the pack to " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * The artifact for this player's protocol.
     *
     * <p>Falls back to the server's own version <b>only when Via isn't telling
     * us anything</b>, which is exactly the case where every client really is
     * on the server's version. It never falls back to a nearby version: an
     * item model definition handed to a 1.21.3 client is a pack that loads and
     * renders nothing, with no error anywhere.
     */
    private Manifest.Entry resolveJavaEntry(int protocol) {
        if (protocol > 0) {
            return manifest.byProtocol(protocol);
        }
        return manifest.byVersion(protocols.serverVersion());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // Mirrored, the engine's pack is answered before serve() runs a
        // second after the join, so nothing can be waiting for it yet.
        if (!isBound() || !manifest.enabled || (awaiting.get(id) == null && !mirroring())) {
            return;
        }
        // Only an answer about THIS pack. A player here is usually sent the
        // server's own bundle too, and every status used to count: the bundle
        // loading recorded the published pack as loaded (which is what lets a
        // pushed overlay be drawn for them), and declining the bundle reported
        // the published one as declined to the dashboard.
        UUID pack = sending.statusId(event);
        if (mirroring()) {
            // Mirrored, the published pack is inside the engine's own, so
            // that is the answer that counts.
            java.util.function.BiPredicate<Player, UUID> own = enginePack;
            if (pack != null && (own == null || !own.test(event.getPlayer(), pack))) {
                return;
            }
        } else if (pack != null && !pack.equals(DISTRIBUTION_PACK_ID)) {
            return;
        }
        // Switched on the NAME rather than the enum constant, which looks
        // like a step backwards and is not. INVALID_URL and FAILED_RELOAD
        // arrived in 1.20.3, and a switch over an enum compiles to a lookup
        // table built in a static initialiser that names every constant — so
        // naming one this server does not have does not fail at this line, it
        // fails when the class is first touched, taking the whole listener
        // with it. A string cannot do that.
        switch (event.getStatus().name()) {
            case "SUCCESSFULLY_LOADED":
                awaiting.remove(id);
                loaded.put(id, Boolean.TRUE);
                outcome(id, "accepted");
                return;
            case "DECLINED":
                awaiting.remove(id);
                loaded.remove(id);
                outcome(id, "declined");
                return;
            case "FAILED_DOWNLOAD":
            case "INVALID_URL":
            case "FAILED_RELOAD":
                awaiting.remove(id);
                loaded.remove(id);
                outcome(id, "failed");
                return;
            default:
                // ACCEPTED and DOWNLOADED are progress, not an answer.
        }
    }

    // -----------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------

    private void record(Player player, String version, int protocol, String outcome) {
        pending.compute(player.getUniqueId(), (id, existing) -> {
            PendingReport report = existing == null ? new PendingReport() : existing;
            report.ign = player.getName();
            report.platform = bedrock.isBedrock(id) ? "bedrock" : "java";
            report.version = version;
            report.protocol = protocol;
            report.outcome = outcome;
            report.joins++;
            return report;
        });
    }

    private void outcome(UUID id, String outcome) {
        PendingReport report = pending.get(id);
        if (report == null && mirroring()) {
            // The join has not been counted yet; serve() takes this up.
            earlyOutcome.put(id, outcome);
            return;
        }
        if (report != null) {
            // The join was already counted when the pack went out; this is the
            // same join reaching its conclusion, not a second one.
            report.outcome = outcome;
            report.joins = Math.max(0, report.joins);
        }
    }

    private void flushReports() {
        // A mirror that failed (the host unreachable, a disk error) is tried
        // again here, on the minute, rather than only on the next republish.
        Manifest held = manifest;
        StudioPackMirror into = mirror;
        if (into != null && isBound() && held.enabled && !held.releaseId.isEmpty()
                && !held.releaseId.equals(into.releaseId())) {
            syncMirror(held);
        }
        if (!isBound()) {
            return;
        }
        if (pending.isEmpty()) {
            // The report's answer is how a publish usually reaches us, and a
            // minute with no join sends no report. Without this a server bound
            // before its first publish (the studio's own order) never heard of
            // it: it records nobody while nothing is enabled, so nothing was
            // ever reported, until a restart. Likewise a republish with nobody
            // joining reached nobody standing here.
            long idle = Bukkit.getOnlinePlayers().isEmpty() ? IDLE_REFRESH_EMPTY_MS : IDLE_REFRESH_ONLINE_MS;
            if (System.currentTimeMillis() - lastRefresh >= idle) {
                refreshAsync(null);
            }
            return;
        }

        JsonArray players = new JsonArray();
        List<UUID> taken = new ArrayList<>(pending.keySet());
        for (UUID id : taken) {
            PendingReport report = pending.remove(id);
            if (report == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", id.toString().replace("-", ""));
            entry.addProperty("ign", report.ign);
            entry.addProperty("platform", report.platform);
            if (report.version != null) {
                entry.addProperty("version", report.version);
            }
            if (report.protocol > 0) {
                entry.addProperty("protocol", report.protocol);
            }
            entry.addProperty("outcome", report.outcome);
            entry.addProperty("joins", report.joins);
            players.add(entry);
        }
        if (players.isEmpty()) {
            return;
        }

        JsonObject body = new JsonObject();
        body.add("players", players);
        body.addProperty("pluginVersion", plugin.getDescription().getVersion());
        body.addProperty("serverVersion", protocols.serverVersion());
        body.addProperty("hasVia", protocols.hasVia());
        body.addProperty("hasGeyser", bedrock.available());

        try {
            JsonObject answer = client.report(token, GSON.toJson(body));
            // The report's answer carries the live release id, so a republish
            // reaches everybody standing here within a minute without a push
            // channel and without a poll of its own. Only a CHANGE costs a
            // request: the manifest is refetched when the id differs from the
            // one we hold, and never otherwise.
            String liveRelease = answer.has("releaseId") && !answer.get("releaseId").isJsonNull()
                    ? answer.get("releaseId").getAsString()
                    : "";
            boolean enabledNow = answer.has("enabled") && answer.get("enabled").getAsBoolean();
            if (!liveRelease.equals(manifest.releaseId) || enabledNow != manifest.enabled) {
                refreshAsync(null);
            }
        } catch (DistributionClient.DistributionException e) {
            // Dropped rather than retried. These are analytics, the next flush
            // is a minute away, and a queue that grows while we are
            // unreachable is a memory leak on somebody else's server.
            plugin.getLogger().fine("Couldn't send distribution report: " + e.getMessage());
        }
    }

    private static final class PendingReport {
        String ign;
        String platform;
        String version;
        int protocol = -1;
        String outcome = "pending";
        int joins;
    }

    // -----------------------------------------------------------------------
    // Token storage
    // -----------------------------------------------------------------------

    /**
     * The token lives in its own file rather than in {@code config.yml}, and
     * that is not tidiness: people paste their configs into help threads, and
     * this one credential is what lets a machine claim to be their server.
     */
    private String readToken() {
        File file = new File(plugin.getDataFolder(), STATE_FILE);
        if (!file.exists()) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(file).getString("token", null);
    }

    private void writeToken(String value) {
        File file = new File(plugin.getDataFolder(), STATE_FILE);
        YamlConfiguration config = new YamlConfiguration();
        if (value != null) {
            config.set("token", value);
        }
        try {
            plugin.getDataFolder().mkdirs();
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Couldn't save the distribution token - this server will need "
                    + "/rpai distribute again after a restart: " + e.getMessage());
        }
    }

    /** The client wants the pack hash as bytes; studio sends it as hex. */
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // The manifest, as this side sees it
    // -----------------------------------------------------------------------

    /** Immutable snapshot, replaced wholesale so readers never see a half-update. */
    static final class Manifest {
        final boolean enabled;
        final String releaseId;
        /** The Studio pack this is, as a pushed content manifest names it. Empty when not said. */
        final String packId;
        final List<Entry> entries;
        private final Map<Integer, Entry> byProtocol = new HashMap<>();
        private final Map<String, Entry> byVersion = new HashMap<>();

        static final class Entry {
            final String version;
            final String url;
            final String sha1;

            Entry(String version, String url, String sha1) {
                this.version = version;
                this.url = url;
                this.sha1 = sha1;
            }
        }

        private Manifest(boolean enabled, String releaseId, String packId, List<Entry> entries, JsonArray raw) {
            this.enabled = enabled;
            this.releaseId = releaseId;
            this.packId = packId == null ? "" : packId;
            this.entries = entries;
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                byVersion.put(entry.version, entry);
                JsonElement protocols = raw.get(i).getAsJsonObject().get("protocols");
                if (protocols != null && protocols.isJsonArray()) {
                    for (JsonElement protocol : protocols.getAsJsonArray()) {
                        byProtocol.put(protocol.getAsInt(), entry);
                    }
                }
            }
        }

        static Manifest empty() {
            return new Manifest(false, "", "", new ArrayList<>(), new JsonArray());
        }

        static Manifest parse(JsonObject json) {
            if (json == null || !json.has("entries")) {
                return empty();
            }
            JsonArray raw = json.getAsJsonArray("entries");
            List<Entry> entries = new ArrayList<>();
            for (JsonElement element : raw) {
                JsonObject entry = element.getAsJsonObject();
                entries.add(new Entry(
                        entry.get("version").getAsString(),
                        entry.get("url").getAsString(),
                        entry.has("sha1") ? entry.get("sha1").getAsString() : null));
            }
            String releaseId = json.has("releaseId") && !json.get("releaseId").isJsonNull()
                    ? json.get("releaseId").getAsString()
                    : "";
            boolean enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
            String packId = json.has("packId") && json.get("packId").isJsonPrimitive()
                    ? json.get("packId").getAsString()
                    : "";
            return new Manifest(enabled, releaseId, packId, entries, raw);
        }

        Entry byProtocol(int protocol) {
            return byProtocol.get(protocol);
        }

        Entry byVersion(String version) {
            return byVersion.get(version);
        }
    }
}
