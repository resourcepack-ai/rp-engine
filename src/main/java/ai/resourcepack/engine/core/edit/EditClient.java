package ai.resourcepack.engine.core.edit;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Talking to studio's editor-session endpoints.
 *
 * <p>{@link HttpURLConnection} and nothing else, the same as
 * {@code StudioPush}: a plugin sold to strangers should not make somebody's
 * server carry an HTTP library for four requests.
 *
 * <p><strong>Every method here blocks and none of them may be called on the
 * main thread.</strong> That is not a style note — a request to a host that is
 * down takes the connect timeout below, and a server whose main thread stops
 * for fifteen seconds has stopped. {@link EditSessions} is what owns the
 * threading, and it is the only caller.
 */
final class EditClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * The most this will read from a response.
     *
     * <p>A pull carries a model and its textures, which is tens of kilobytes.
     * The ceiling is here because the body is read into memory before it is
     * parsed and the far end is, from this side, just a hostname somebody put
     * in a config file.
     */
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    private final Gson gson = new Gson();
    private final String baseUrl;
    private final String userAgent;

    EditClient(String baseUrl, String version) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.userAgent = "RPEngine/" + version;
    }

    /** {@code RPEngine/<version>}, which is also what the wire's client field is. */
    String client() {
        return userAgent;
    }

    EditWire.Opened open(EditWire.Open request) throws EditException {
        String body = send("POST", "/api/rpengine/session", null, gson.toJson(request));
        EditWire.Opened opened = parse(body, EditWire.Opened.class);
        if (opened == null || opened.id == null || opened.url == null || opened.pullToken == null) {
            throw new EditException("The editor did not answer with a session.");
        }
        return opened;
    }

    /** Empty when the session is gone — expired, closed, or never existed. */
    Optional<EditWire.Status> status(String id, String pullToken) {
        try {
            String body = send("GET", "/api/rpengine/session/" + id, pullToken, null);
            return Optional.ofNullable(parse(body, EditWire.Status.class));
        } catch (EditException e) {
            return Optional.empty();
        }
    }

    EditWire.Pull pull(String id, String pullToken) throws EditException {
        String body = send("GET", "/api/rpengine/session/" + id + "/files", pullToken, null);
        EditWire.Pull pull = parse(body, EditWire.Pull.class);
        if (pull == null || pull.files == null) {
            throw new EditException("The editor sent nothing to write.");
        }
        return pull;
    }

    /** Best effort: a session left open expires on its own within hours. */
    void close(String id, String pullToken) {
        try {
            send("DELETE", "/api/rpengine/session/" + id, pullToken, null);
        } catch (EditException ignored) {
            // Nothing to do about it and nobody to tell — the caller is
            // already tidying up.
        }
    }

    private <T> T parse(String body, Class<T> type) throws EditException {
        try {
            return gson.fromJson(body, type);
        } catch (JsonSyntaxException e) {
            throw new EditException("The editor answered with something this plugin cannot read.");
        }
    }

    private String send(String method, String path, String token, String body) throws EditException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }

            int code = connection.getResponseCode();
            // The failure body is read from the ERROR stream, which is a
            // separate stream and empty on the one getInputStream() throws
            // for. Reading the wrong one is how a route that answered with a
            // written sentence gets reported as "HTTP 400".
            String text = read(code / 100 == 2 ? connection.getInputStream() : connection.getErrorStream());
            if (code / 100 == 2) {
                return text;
            }
            throw new EditException(messageOf(text, code));
        } catch (IOException | IllegalArgumentException e) {
            throw new EditException("Could not reach the editor: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** The far end's own sentence, or a code when it did not write one. */
    private String messageOf(String body, int code) {
        try {
            EditWire.Failure failure = gson.fromJson(body, EditWire.Failure.class);
            if (failure != null && failure.error != null && !failure.error.isEmpty()) {
                return failure.error;
            }
        } catch (JsonSyntaxException ignored) {
            // A gateway's HTML error page, most likely. The code says more.
        }
        return "The editor refused (HTTP " + code + ").";
    }

    private String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int count;
        while ((count = in.read(buffer)) > 0) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("response too large");
            }
            out.write(buffer, 0, count);
        }
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
