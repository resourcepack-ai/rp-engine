package ai.resourcepack.engine.core.sync;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A push does not hold the socket it arrived on.
 *
 * <p>Every frame from Studio is handled on the websocket's one read thread, and
 * a push used to download its pack right there. On a server where several
 * people sync at once, one person's tens of megabytes held up everybody
 * else's push, give and skin until it finished — long enough for the far end
 * to report theirs as timed out, and for the socket's own keep-alive to go
 * unanswered.
 */
class StudioRelayTest {

    @TempDir
    Path dir;

    private HttpServer slow;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch requested = new CountDownLatch(2);

    @AfterEach
    void stop() {
        release.countDown();
        if (slow != null) {
            slow.stop(0);
        }
    }

    @Test
    void aSlowDownloadDoesNotHoldTheNextFrame() throws Exception {
        slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        slow.createContext("/", exchange -> {
            requested.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        slow.start();
        String url = "http://127.0.0.1:" + slow.getAddress().getPort() + "/pack.zip";

        StudioRelay relay = new StudioRelay(plugin(), new SyncClient("wss://example.invalid/connect", "",
                Logger.getLogger("test"), (a, b) -> { }, (a, b) -> { }, (a, b) -> { }, (a, b) -> { }),
                new SyncGroup(), null, null, null, null, dir, pack -> { }, (player, pack) -> { });
        try {
            long started = System.nanoTime();
            // Two players, two pushes, one socket thread: this one.
            relay.onPush("069a79f4a3df4229adc07f26f6c2ec3a", url);
            relay.onPush("0a1b2c3d4e5f60718293a4b5c6d7e8f9", url);
            long tookMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(tookMs < 1_000, "onPush held the calling thread for " + tookMs + "ms");
            // And the two downloads are in flight together, not one behind the other.
            assertTrue(requested.await(5, TimeUnit.SECONDS), "the second push waited for the first");
        } finally {
            release.countDown();
            relay.close();
        }
    }

    /** Enough of a plugin to construct the relay: a logger, and nothing that schedules. */
    private static Plugin plugin() {
        Logger log = Logger.getLogger("relay-test");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLogger")) {
                        return log;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    @SuppressWarnings("unused")
    private static void ignore(IOException e) {
    }
}
