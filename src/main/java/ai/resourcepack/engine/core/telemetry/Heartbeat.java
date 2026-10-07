package ai.resourcepack.engine.core.telemetry;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Tells resourcepack.ai that this server is running RP Engine, so we know how
 * many servers are, and which RP Engine addons they run.
 *
 * <p><b>What is sent is a random id and the names of the installed addons, and
 * nothing else.</b> The id is made up the first time the plugin starts and kept
 * in {@code server-id} in its data folder; it is not derived from anything about
 * the server, so it says nothing about the server. An addon is any enabled
 * plugin that declares RP Engine as a dependency, and only its name goes, as its
 * {@code plugin.yml} spells it. No address, server name, version, player or
 * content goes with them, and the answer is never read.
 * {@code telemetry.enabled: false} in {@code config.yml} sends nothing at all.
 *
 * <p>The list is read on every beat rather than once, because an addon can be
 * loaded or removed while the server runs.
 *
 * <p>Every {@link #INTERVAL_SECONDS} seconds, off the main thread, and silent:
 * a beat that fails is simply the next one's problem. The far end counts a
 * server as running while beats keep arriving, so stopping needs no goodbye.
 */
public final class Heartbeat {

    /** The far end's active window is three of these; change both together. */
    public static final long INTERVAL_SECONDS = 30;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Plugin plugin;
    private final URI endpoint;
    private final String serverId;
    // HTTP/1.1 for the reason DistributionClient gives: the HTTP/2 default
    // sends an h2c upgrade that a local Next dev server answers by hanging up.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private BukkitTask task;

    public Heartbeat(Plugin plugin, String studioUrl) {
        this.plugin = plugin;
        String base = studioUrl.endsWith("/") ? studioUrl.substring(0, studioUrl.length() - 1) : studioUrl;
        this.endpoint = URI.create(base + "/api/rp-engine/heartbeat");
        this.serverId = serverId(plugin.getDataFolder().toPath());
    }

    /** The beat's JSON, built by hand: the id is a UUID and needs nothing, the names are escaped here. */
    static String body(String serverId, List<String> addons) {
        StringBuilder json = new StringBuilder("{\"id\":\"").append(serverId).append("\",\"addons\":[");
        for (int i = 0; i < addons.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"');
            String name = addons.get(i);
            for (int c = 0; c < name.length(); c++) {
                char ch = name.charAt(c);
                if (ch == '"' || ch == '\\') {
                    json.append('\\').append(ch);
                } else if (ch >= 0x20) {
                    json.append(ch);
                }
            }
            json.append('"');
        }
        return json.append("]}").toString();
    }

    /** The enabled plugins that declare a dependency on this one, by name, sorted. */
    private List<String> addons() {
        String self = plugin.getName();
        TreeSet<String> names = new TreeSet<>();
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            if (other == plugin || !other.isEnabled()) {
                continue;
            }
            PluginDescriptionFile description = other.getDescription();
            if (description.getDepend().contains(self) || description.getSoftDepend().contains(self)) {
                names.add(description.getName());
            }
        }
        return new ArrayList<>(names);
    }

    public void start() {
        stop();
        long ticks = INTERVAL_SECONDS * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::beat, 20L, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void beat() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("content-type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body(serverId, addons())))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            // Offline, firewalled, studio down: none of it is the server
            // owner's concern, and a log line every thirty seconds would be.
        }
    }

    /**
     * This server's id, made on first use. A folder we cannot write to gets a
     * fresh id every start, which over-counts one server rather than failing.
     */
    static String serverId(Path dataFolder) {
        Path file = dataFolder.resolve("server-id");
        try {
            if (Files.isRegularFile(file)) {
                String saved = Files.readString(file, StandardCharsets.UTF_8).trim();
                return UUID.fromString(saved).toString();
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Unreadable or edited into something that is not a UUID: replace it.
        }
        String id = UUID.randomUUID().toString();
        try {
            Files.createDirectories(dataFolder);
            Files.writeString(file, id + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // See the javadoc: counted again next start, nothing worse.
        }
        return id;
    }
}
