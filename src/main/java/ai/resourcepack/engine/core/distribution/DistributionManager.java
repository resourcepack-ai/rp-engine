package ai.resourcepack.engine.core.distribution;

import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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

    public DistributionManager(JavaPlugin plugin, BedrockSupport bedrock,
                               ProtocolResolver protocols, String apiBase) {
        this.bedrock = bedrock == null ? BedrockSupport.NONE : bedrock;
        this.plugin = plugin;
        this.protocols = protocols;
        this.client = new DistributionClient(apiBase, plugin.getDescription().getVersion());
        this.token = readToken();
    }

    public boolean isBound() {
        return token != null && !token.isEmpty();
    }

    /** Start the refresh and report loops. Safe to call with no binding. */
    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushReports,
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
    public void claim(org.bukkit.command.CommandSender sender, String code) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                DistributionClient.ClaimResult result = client.claim(
                        code, protocols.serverVersion(), protocols.hasVia(), bedrock.available());
                this.token = result.token;
                writeToken(result.token);
                this.manifest = Manifest.parse(result.manifest);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Binding now normally happens BEFORE anything is
                    // published (the studio's Serve-a-pack modal binds
                    // first), so "not serving yet" is the expected state and
                    // must not read as a warning. One message per situation,
                    // not a success line with caveats stacked under it.
                    // result.name is this SERVER's name on the panel, not the
                    // pack's — don't print it as if it were a pack.
                    if (manifest.enabled && !manifest.entries.isEmpty()) {
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
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(e.getMessage()));
            }
        });
    }

    /** Forget the binding. The studio side is unbound from the tab. */
    public void unbind(org.bukkit.command.CommandSender sender) {
        this.token = null;
        this.manifest = Manifest.empty();
        writeToken(null);
        sender.sendMessage("This server is no longer serving a pack. "
                + "Players keep what they already downloaded until they rejoin.");
    }

    /** Re-read the manifest, e.g. after a publish. */
    public void refreshAsync(Runnable then) {
        if (!isBound()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Manifest fresh = Manifest.parse(client.manifest(token, protocols.serverVersion()));
                boolean changed = !fresh.releaseId.equals(manifest.releaseId);
                this.manifest = fresh;
                if (changed) {
                    // A new release is the whole point of live republish:
                    // everybody standing here gets it without rejoining.
                    Bukkit.getScheduler().runTask(plugin, this::applyToEveryoneOnline);
                }
                if (then != null) {
                    Bukkit.getScheduler().runTask(plugin, then);
                }
            } catch (DistributionClient.DistributionException e) {
                // A failed refresh keeps the manifest we already have. That is
                // the entire reason it is cached: we can be unreachable and
                // this server keeps serving the last thing it was told.
                plugin.getLogger().warning("Couldn't refresh the distribution manifest: " + e.getMessage());
            }
        });
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> serve(event.getPlayer()), 20L);
    }

    private void applyToEveryoneOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            serve(player);
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
        try {
            player.addResourcePack(DISTRIBUTION_PACK_ID, entry.url, hexToBytes(entry.sha1), null, false);
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
        if (awaiting.get(id) == null) {
            return;
        }
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED:
                awaiting.remove(id);
                outcome(id, "accepted");
                return;
            case DECLINED:
                awaiting.remove(id);
                outcome(id, "declined");
                return;
            case FAILED_DOWNLOAD:
            case INVALID_URL:
            case FAILED_RELOAD:
                awaiting.remove(id);
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
        if (report != null) {
            // The join was already counted when the pack went out; this is the
            // same join reaching its conclusion, not a second one.
            report.outcome = outcome;
            report.joins = Math.max(0, report.joins);
        }
    }

    private void flushReports() {
        if (!isBound() || pending.isEmpty()) {
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

        private Manifest(boolean enabled, String releaseId, List<Entry> entries, JsonArray raw) {
            this.enabled = enabled;
            this.releaseId = releaseId;
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
            return new Manifest(false, "", new ArrayList<>(), new JsonArray());
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
            return new Manifest(enabled, releaseId, entries, raw);
        }

        Entry byProtocol(int protocol) {
            return byProtocol.get(protocol);
        }

        Entry byVersion(String version) {
            return byVersion.get(version);
        }
    }
}
