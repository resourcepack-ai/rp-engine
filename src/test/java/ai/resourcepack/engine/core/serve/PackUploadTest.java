package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upload mode against a stand-in for the pack host that behaves like the real one. */
class PackUploadTest {

    @TempDir
    Path dir;

    private HttpServer fake;
    private String base;
    private final Map<String, byte[]> stored = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    /** Answers every request with this status instead, when set. */
    private final AtomicInteger forced = new AtomicInteger();
    private PackHost host;

    @BeforeEach
    void startFake() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/h/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path);
            byte[] body = exchange.getRequestBody().readAllBytes();
            int status;
            if (forced.get() != 0) {
                status = forced.get();
            } else if (path.endsWith("/touch")) {
                String sha1 = path.substring("/h/".length(), path.length() - "/touch".length());
                status = stored.containsKey(sha1) ? 200 : 404;
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                stored.put(path.substring("/h/".length(), path.length() - ".zip".length()), body);
                status = 201;
            } else {
                status = 405;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        fake.start();
        base = "http://127.0.0.1:" + fake.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (host != null) {
            host.stop();
        }
        fake.stop(0);
    }

    private BuiltPack pack(String bundle, String sha1) throws IOException {
        Path file = dir.resolve(bundle + "-" + sha1 + ".zip");
        Files.writeString(file, "zip of " + bundle + " " + sha1, StandardCharsets.UTF_8);
        return BuiltPack.of(bundle, file, sha1, Files.size(file), 1);
    }

    private PackHost uploading(long retryMillis) {
        host = new PackHost(PackHost.Mode.UPLOAD, "", PackHost.Limits.DEFAULT,
                new PackUploader(base, "RPEngine/test", retryMillis), Runnable::run, null);
        return host;
    }

    private static void settle(PackHost host, String bundle) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        host.whenSettled(bundle, done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS), "upload never settled");
    }

    @Test
    void aNewPackIsUploadedAndThenLinked() throws Exception {
        PackHost host = uploading(10);
        BuiltPack pack = pack("default", "a".repeat(40));
        host.register(pack);
        settle(host, "default");

        assertFalse(host.pending("default"));
        assertEquals(Optional.of(base + "/h/" + "a".repeat(40) + ".zip"), host.url("default", null));
        assertArrayEquals(Files.readAllBytes(pack.file()), stored.get("a".repeat(40)));
        assertEquals(List.of("POST /h/" + "a".repeat(40) + "/touch", "PUT /h/" + "a".repeat(40) + ".zip"), calls);
        assertTrue(host.onPackHost(host.url("default").orElseThrow()));
    }

    @Test
    void aPackTheHostHasAlreadyIsNotSentAgain() throws Exception {
        stored.put("b".repeat(40), new byte[0]);
        PackHost host = uploading(10);
        host.register(pack("default", "b".repeat(40)));
        settle(host, "default");

        assertEquals(List.of("POST /h/" + "b".repeat(40) + "/touch"), calls);
        assertTrue(host.url("default", null).isPresent());
    }

    @Test
    void registeringTheSameBytesAgainUploadsNothing() throws Exception {
        PackHost host = uploading(10);
        host.register(pack("default", "c".repeat(40)));
        settle(host, "default");
        int before = calls.size();
        host.register(pack("default", "c".repeat(40)));
        settle(host, "default");
        assertEquals(before, calls.size());
    }

    @Test
    void aFailedUploadHasNoLinkAndSaysSoInTheSettle() throws Exception {
        forced.set(400);
        PackHost host = uploading(10);
        // No game port in a test, so the fallback cannot start: no link at all.
        host.gamePortReachable(false);
        host.register(pack("default", "d".repeat(40)));
        settle(host, "default");
        assertFalse(host.pending("default"));
        assertEquals(Optional.empty(), host.url("default", null));
    }

    @Test
    void aBusyHostIsTriedAgain() throws Exception {
        forced.set(429);
        PackUploader uploader = new PackUploader(base, "RPEngine/test", 10);
        assertThrows(PackUploader.UploadException.class, () -> uploader.ensure(pack("default", "e".repeat(40))));
        assertEquals(3, calls.size());
    }

    @Test
    void aPushedPackIsNeverUploaded() throws Exception {
        PackHost host = uploading(10);
        Path file = dir.resolve("pushed.zip");
        Files.writeString(file, "pushed");
        host.register(BuiltPack.served("studio", file, "f".repeat(40), 6, 1, "https://studio.example/p.zip"));
        assertFalse(host.pending("studio"));
        assertTrue(calls.isEmpty());
    }

    @Test
    void modesParseAsWritten() {
        assertEquals(Optional.of(PackHost.Mode.UPLOAD), PackHost.Mode.parse("upload"));
        assertEquals(Optional.of(PackHost.Mode.SAME_PORT), PackHost.Mode.parse(" Same-Port "));
        assertEquals(Optional.of(PackHost.Mode.OWN_PORT), PackHost.Mode.parse("own_port"));
        assertEquals(Optional.of(PackHost.Mode.NONE), PackHost.Mode.parse("none"));
        assertEquals(Optional.empty(), PackHost.Mode.parse("cdn"));
    }

    @Test
    void theAddressAPlayerTypedBecomesHostAndPort() {
        assertEquals("play.example.com:25565", LoginAddresses.normalize("play.example.com:25565", 1));
        assertEquals("play.example.com:25570", LoginAddresses.normalize("play.example.com", 25570));
        assertEquals("play.example.com:25565", LoginAddresses.normalize("play.example.com.:25565", 1));
        assertEquals("play.example.com:25565", LoginAddresses.normalize("play.example.com\0FML3\0:25565", 25565));
        assertEquals("[2001:db8::1]:25565", LoginAddresses.normalize("[2001:db8::1]:25565", 1));
        assertEquals("[2001:db8::1]:25565", LoginAddresses.normalize("2001:db8::1", 25565));
        assertEquals(null, LoginAddresses.normalize("", 25565));
        assertEquals(null, LoginAddresses.normalize(null, 25565));
    }

    @Test
    void uploadModeCanAlsoServeThePackLocally() throws Exception {
        host = new PackHost(
                PackHost.Mode.UPLOAD,
                "http://127.0.0.1",
                PackHost.Limits.DEFAULT,
                new PackUploader(base, "RPEngine/test", 10),
                Runnable::run,
                null
        );

        int port = host.start(0);
        BuiltPack pack = pack("default", "a".repeat(40));

        host.register(pack);
        settle(host, "default");

        assertTrue(host.onPackHost(host.url("default").orElseThrow()));
        assertEquals(
                Optional.of("http://127.0.0.1" + PackHost.pathFor(pack)),
                host.localUrl(pack)
        );

        var response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(
                                "http://127.0.0.1:" + port + PackHost.pathFor(pack)
                        )
                ).build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray()
        );

        assertEquals(200, response.statusCode());
        assertArrayEquals(Files.readAllBytes(pack.file()), response.body());
    }
}
