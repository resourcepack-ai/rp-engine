package ai.resourcepack.engine.core.distribution;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The HTTP half of distribution: three calls against studio.
 *
 * <p>Plain {@code java.net.http}, no dependency. The websocket to
 * {@code sync} is a live relay and stays what it is; this is request/response
 * bookkeeping that has no business on that socket, and putting it there would
 * push a per-server firehose through one global Durable Object.
 *
 * <p><b>Every method here blocks and must be called off the main thread.</b>
 * A server that stalls its tick loop waiting on our API is a server whose
 * owner uninstalls the plugin, and rightly.
 */
public final class DistributionClient {

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    // HTTP/1.1 forced: the client's HTTP/2 default sends an `Upgrade: h2c`
    // header on plain-http requests, and Node-based servers with a websocket
    // upgrade listener (Next's dev server) answer that by destroying the
    // socket — "header parser received no bytes" against a local studio.
    // Over https nothing is lost; three bookkeeping calls a minute don't
    // need multiplexing.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String apiBase;
    private final String pluginVersion;

    public DistributionClient(String apiBase, String pluginVersion) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.pluginVersion = pluginVersion;
    }

    /** What the server was told when it claimed a code. */
    public static final class ClaimResult {
        public final String token;
        public final String name;
        public final JsonObject manifest;

        ClaimResult(String token, String name, JsonObject manifest) {
            this.token = token;
            this.name = name;
            this.manifest = manifest;
        }
    }

    /**
     * Claim an 8-digit code from the Distribution tab.
     *
     * @throws DistributionException with a message meant to be shown in chat.
     */
    public ClaimResult claim(String code, String serverVersion, boolean hasVia, boolean hasGeyser)
            throws DistributionException {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("pluginVersion", pluginVersion);
        body.addProperty("serverVersion", serverVersion);
        body.addProperty("hasVia", hasVia);
        body.addProperty("hasGeyser", hasGeyser);

        JsonObject response = send(HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/api/distribution/claim"))
                .header("content-type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))));

        return new ClaimResult(
                response.get("token").getAsString(),
                response.has("name") ? response.get("name").getAsString() : "Minecraft server",
                response.getAsJsonObject("manifest"));
    }

    /** The current manifest for the bound pack. */
    public JsonObject manifest(String token, String serverVersion) throws DistributionException {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/api/distribution/manifest"))
                .header("x-distribution-token", token)
                .header("x-plugin-version", pluginVersion)
                .header("x-server-version", serverVersion)
                .timeout(TIMEOUT)
                .GET());
    }

    /**
     * A batch of play data, answered with the live release id.
     *
     * <p>That answer is how a republish reaches players without anybody
     * rejoining: this call already happens every minute, so it carries the
     * signal for free rather than needing a push channel of its own.
     */
    public JsonObject report(String token, String json) throws DistributionException {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/api/distribution/report"))
                .header("x-distribution-token", token)
                .header("content-type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private JsonObject send(HttpRequest.Builder builder) throws DistributionException {
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject parsed = response.body() == null || response.body().isEmpty()
                    ? new JsonObject()
                    : GSON.fromJson(response.body(), JsonObject.class);

            if (response.statusCode() >= 400) {
                // Studio writes these messages for a person to read, so they
                // are passed through rather than replaced with a status code.
                String message = parsed != null && parsed.has("error")
                        ? parsed.get("error").getAsString()
                        : "The studio returned " + response.statusCode() + ".";
                throw new DistributionException(message);
            }
            return parsed == null ? new JsonObject() : parsed;
        } catch (DistributionException e) {
            throw e;
        } catch (Exception e) {
            // Network faults, timeouts and malformed JSON collapse into one
            // message: from a server owner's side they are all "it didn't
            // work", and the detail is in the log.
            throw new DistributionException("Couldn't reach the studio (" + e.getClass().getSimpleName() + ").");
        }
    }

    /** A failure worth showing whoever ran the command. */
    public static final class DistributionException extends Exception {
        public DistributionException(String message) {
            super(message);
        }
    }
}
