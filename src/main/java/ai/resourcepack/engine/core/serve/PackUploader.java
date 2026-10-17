package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Puts a built pack on ResourcePack AI's pack host, so players download it
 * from there rather than from this server.
 *
 * <p>The host keys a pack by its SHA-1, so asking for one is cheap: a
 * {@code touch} says whether it is there already (and keeps it there), and
 * only a pack it has never seen is sent. A server restarted with the same
 * content uploads nothing.
 *
 * <p>Blocking, and deliberately so: {@link PackHost} runs it on a thread of
 * its own and never on the main one.
 */
public final class PackUploader {

    /** What the host takes at most. A bigger pack has to be served by the server itself. */
    public static final long MAX_BYTES = 95L * 1024 * 1024;

    private static final int ATTEMPTS = 3;

    /** Why an upload failed, in words for a server owner. */
    public static final class UploadException extends IOException {
        public UploadException(String message) {
            super(message);
        }
    }

    private final HttpClient http;
    private final String base;
    private final String agent;
    private final long retryMillis;

    /**
     * @param base  the pack host, {@code https://packs.resourcepack.ai}
     * @param agent the User-Agent to send, which names the plugin and version
     */
    public PackUploader(String base, String agent) {
        this(base, agent, 30_000L);
    }

    PackUploader(String base, String agent, long retryMillis) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.agent = agent;
        this.retryMillis = retryMillis;
    }

    /** The pack host this uploads to, with no trailing slash. */
    public String base() {
        return base;
    }

    /** The link a player is sent for {@code pack}. The host answers with the same one. */
    public String linkFor(BuiltPack pack) {
        return base + "/h/" + pack.sha1() + ".zip";
    }

    /**
     * Makes sure the host has {@code pack}, uploading it if not, and returns
     * the link players download it from.
     */
    public String ensure(BuiltPack pack) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            int touched = touch(pack);
            if (touched == 200) {
                return linkFor(pack);
            }
            if (touched == 404) {
                int uploaded = upload(pack);
                if (uploaded == 201 || uploaded == 200) {
                    return linkFor(pack);
                }
                if (uploaded != 429 && uploaded < 500) {
                    throw new UploadException("the pack host refused " + pack.bundle() + " (HTTP " + uploaded + ")");
                }
            } else if (touched != 429 && touched < 500) {
                throw new UploadException("the pack host answered HTTP " + touched + " for " + pack.bundle());
            }
            // Busy (429) or broken (5xx): worth another go, a little later.
            if (attempt >= ATTEMPTS) {
                throw new UploadException("the pack host is busy or down (HTTP " + touched + ")");
            }
            Thread.sleep(retryMillis);
        }
    }

    private int touch(BuiltPack pack) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/h/" + pack.sha1() + "/touch"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", agent)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int upload(BuiltPack pack) throws IOException, InterruptedException {
        long size = Files.size(pack.file());
        if (size > MAX_BYTES) {
            throw new UploadException(pack.bundle() + " is " + (size / 1024 / 1024) + " MB, and the pack host takes at most "
                    + (MAX_BYTES / 1024 / 1024) + " MB");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(linkFor(pack)))
                // Generous: a big pack over a slow uplink.
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", agent)
                .header("Content-Type", "application/zip")
                .PUT(HttpRequest.BodyPublishers.ofFile(pack.file()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 || response.statusCode() == 413) {
            throw new UploadException("the pack host refused " + pack.bundle() + ": " + response.body().trim());
        }
        return response.statusCode();
    }
}
