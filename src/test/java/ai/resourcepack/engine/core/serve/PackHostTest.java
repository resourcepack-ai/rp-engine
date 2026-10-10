package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackHostTest {

    @TempDir
    Path dir;

    private PackHost host;
    private int port;

    @AfterEach
    void tearDown() {
        if (host != null) {
            host.stop();
        }
    }

    private PackHost started() throws IOException {
        host = new PackHost("http://localhost");
        port = host.start(0);
        return host;
    }

    private BuiltPack pack(String bundle, String sha1, String body) throws IOException {
        Path file = dir.resolve(bundle + ".zip");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return BuiltPack.of(bundle, file, sha1, Files.size(file), 1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesARegisteredPack() throws Exception {
        PackHost host = started();
        BuiltPack pack = pack("lobby", "abc123", "ZIPBYTES");
        host.register(pack);

        HttpResponse<String> response = get(PackHost.pathFor(pack));

        assertEquals(200, response.statusCode());
        assertEquals("ZIPBYTES", response.body());
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("\"abc123\"", response.headers().firstValue("ETag").orElseThrow());
    }

    @Test
    void theHashIsInTheUrl() throws Exception {
        PackHost host = started();

        // A rebuilt bundle gets a new path, so nothing in between can serve a
        // stale body from a URL-keyed cache.
        String before = host.register(pack("lobby", "aaa", "OLD"));
        String after = host.register(pack("lobby", "bbb", "NEW"));

        assertNotEquals(before, after);
        assertTrue(after.contains("bbb"));
        assertEquals("NEW", get(URI.create(after).getPath()).body());
    }

    @Test
    void rebuildingDropsTheOldPath() throws Exception {
        PackHost host = started();
        String before = host.register(pack("lobby", "aaa", "OLD"));
        host.register(pack("lobby", "bbb", "NEW"));

        assertEquals(1, host.size(), "one bundle, one path, not one per build ever made");
        assertEquals(404, get(URI.create(before).getPath()).statusCode());
    }

    @Test
    void theUrlIsBuiltFromThePublicAddress() throws IOException {
        // A server behind a proxy or a NAT cannot work its own address out, so
        // it is told rather than guessed.
        PackHost host = new PackHost("https://play.example.com:8080/");
        this.host = host;
        BuiltPack pack = pack("lobby", "abc", "X");

        assertEquals("https://play.example.com:8080/packs/lobby/abc.zip", host.register(pack));
        assertEquals("https://play.example.com:8080/packs/lobby/abc.zip", host.url("lobby").orElseThrow());
        assertTrue(host.url("nope").isEmpty());
    }

    @Test
    void anUnknownPathIs404() throws Exception {
        started();

        assertEquals(404, get("/packs/lobby/nothing.zip").statusCode());
        assertEquals(404, get("/").statusCode());
    }

    @Test
    void aPackWhoseFileWentAwayIs404RatherThanAnError() throws Exception {
        PackHost host = started();
        BuiltPack pack = pack("lobby", "abc", "X");
        host.register(pack);
        Files.delete(pack.file());

        assertEquals(404, get(PackHost.pathFor(pack)).statusCode());
    }

    @Test
    void headAnswersWithoutABody() throws Exception {
        PackHost host = started();
        BuiltPack pack = pack("lobby", "abc", "ZIPBYTES");
        host.register(pack);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + PackHost.pathFor(pack)))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
        assertEquals("8", response.headers().firstValue("Content-Length").orElseThrow());
    }

    @Test
    void otherMethodsAreRefused() throws Exception {
        started();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/packs/a/b.zip"))
                        .POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    @Test
    void stoppingDropsEverything() throws IOException {
        PackHost host = started();
        host.register(pack("lobby", "abc", "X"));

        host.stop();

        assertFalse(host.running());
        assertEquals(0, host.size());
    }

    @Test
    void startingTwiceKeepsTheSamePort() throws IOException {
        PackHost host = started();

        assertEquals(port, host.start(0));
        assertTrue(host.running());
    }

    @Test
    void registeringNothingIsHarmless() throws IOException {
        PackHost host = started();

        assertEquals("", host.register(null));
        assertEquals(0, host.size());
    }

    // ---- limits: a few slow clients must not take the host from everybody ----

    private PackHost startedWith(PackHost.Limits limits) throws IOException {
        host = new PackHost("http://localhost", limits);
        port = host.start(0);
        return host;
    }

    private static String readAll(Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        try (InputStream in = socket.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void clientsThatNeverFinishTheirRequestDoNotStarveTheRest() throws Exception {
        // Two workers, both taken by clients that send one byte and stop.
        PackHost host = startedWith(new PackHost.Limits(2, 8, 10, 5000, 300, 300, 5000, false));
        BuiltPack pack = pack("lobby", "abc", "ZIPBYTES");
        host.register(pack);

        List<Socket> slow = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                Socket socket = new Socket("localhost", port);
                socket.getOutputStream().write('G');
                socket.getOutputStream().flush();
                slow.add(socket);
            }

            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + PackHost.pathFor(pack)))
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ZIPBYTES", response.body());

            // And the slow ones were hung up on rather than waited for.
            for (Socket socket : slow) {
                assertFalse(readAll(socket).startsWith("HTTP/1.1 200"));
            }
        } finally {
            for (Socket socket : slow) {
                socket.close();
            }
        }
    }

    @Test
    void oneAddressCannotHoldEveryConnection() throws Exception {
        startedWith(new PackHost.Limits(4, 8, 2, 5000, 3000, 3000, 5000, false));

        try (Socket first = new Socket("localhost", port);
             Socket second = new Socket("localhost", port);
             Socket third = new Socket("localhost", port)) {
            assertTrue(readAll(third).startsWith("HTTP/1.1 503"));
        }
    }

    @Test
    void anAddressGetsItsConnectionsBackWhenTheyClose() throws Exception {
        PackHost host = startedWith(new PackHost.Limits(4, 8, 1, 5000, 3000, 3000, 5000, false));
        BuiltPack pack = pack("lobby", "abc", "ZIPBYTES");
        host.register(pack);

        for (int i = 0; i < 3; i++) {
            try (Socket socket = new Socket("localhost", port)) {
                socket.getOutputStream().write(("GET " + PackHost.pathFor(pack) + " HTTP/1.1\r\nHost: x\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                String answer = readAll(socket);
                assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
                assertTrue(answer.endsWith("ZIPBYTES"));
            }
            // The slot is given back as the server side finishes, a moment after the client sees the body.
            Thread.sleep(100);
        }
    }
}
