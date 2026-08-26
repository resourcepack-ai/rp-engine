package ai.resourcepack.engine.core.sync;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * The websocket to ResourcePack AI Studio, for live-testing a pack.
 *
 * <p><strong>The wire protocol is not ours.</strong> It is fixed by the
 * existing plugin — `the pairing service's own spec` documents it and says so in the first
 * paragraph — so everything here is an implementation of somebody else's
 * spec. Frames are plain text, space-delimited, and split on the first two
 * spaces only, because a URL or a failure reason contains spaces and
 * truncating either is how a push silently does nothing.
 *
 * <p>One socket per server, opened on the first {@code /link} and reused for
 * every code after it. Codes are not one to one with connections.
 *
 * <p>Studio being reachable is not something the engine depends on: this is a
 * convenience for whoever is building the pack, and a server with no pairing
 * behaves exactly as it did before this class existed.
 */
public final class SyncClient {

    /** How long a socket waits before deciding a silent connection is dead. */
    private static final int PING_SECONDS = 30;

    private final String url;
    private final Logger logger;
    private final BiConsumer<String, String> onApply;
    private final BiConsumer<String, String> onGive;

    /** Codes claimed on this connection, and who claimed each. */
    private final Map<String, String> claimed = new ConcurrentHashMap<>();

    private volatile WebSocketClient socket;

    /**
     * @param onApply called with the code and the url payload of an
     *                {@code APPLY}. The payload is "everything after the second
     *                space" and may be two space-joined urls, which is the
     *                caller's problem to split rather than ours to interpret
     * @param onGive  called with the code and the command of a {@code GIVE}
     */
    public SyncClient(String url, Logger logger,
                      BiConsumer<String, String> onApply, BiConsumer<String, String> onGive) {
        this.url = url;
        this.logger = logger;
        this.onApply = onApply;
        this.onGive = onGive;
    }

    /** Whether the socket is open. */
    public boolean connected() {
        WebSocketClient current = socket;
        return current != null && current.isOpen();
    }

    /** Who claimed {@code code}, or null. */
    public String claimant(String code) {
        return claimed.get(code);
    }

    /**
     * Claims {@code code} for {@code playerName}.
     *
     * <p>Opens the connection if it is not open. A code we never issued, or one
     * that has expired, is simply ignored by the far end — no {@code APPLY}
     * ever arrives for it — so nothing here has to validate one.
     *
     * @return false if the connection could not be opened
     */
    public synchronized boolean link(String code, String playerName) {
        if (code == null || playerName == null) {
            return false;
        }
        if (!connect()) {
            return false;
        }
        claimed.put(code, playerName);
        // "java" because this plugin does not serve Bedrock. Saying so is
        // better than omitting it: studio reads the platform back.
        send("LINKED " + code + " " + playerName + " java");
        return true;
    }

    /** Drops a code, telling the far end so studio stops offering to push to it. */
    public void unlink(String code) {
        if (code != null && claimed.remove(code) != null) {
            send("UNLINK " + code);
        }
    }

    /** Drops every code this player claimed. Called when they quit. */
    public void forget(String playerName) {
        for (Map.Entry<String, String> entry : claimed.entrySet()) {
            if (entry.getValue().equals(playerName)) {
                unlink(entry.getKey());
            }
        }
    }

    /**
     * Tells the far end everybody a push for {@code code} may land on.
     *
     * <p>Sent whole on every change, which is what the protocol asks for, and
     * {@code -} when it is only the claimer. Easy to forget on the removal
     * path, where the roster shrinking matters as much as it growing.
     */
    public void members(String code, java.util.List<String> entries) {
        send("MEMBERS " + code + " " + (entries.isEmpty() ? "-" : String.join(",", entries)));
    }

    /** Tells the far end a push landed. */
    public void applied(String code) {
        send("APPLIED " + code);
    }

    /** Tells the far end a push did not land, and why. */
    public void failed(String code, String reason) {
        send("FAILED " + code + " " + reason);
    }

    /** Tells the far end a give ran. */
    public void given(String code) {
        send("GIVEN " + code);
    }

    /** Tells the far end a give did not run, and why. */
    public void giveFailed(String code, String reason) {
        send("GIVE_FAILED " + code + " " + reason);
    }

    /** Closes the socket and forgets every code. */
    public synchronized void close() {
        WebSocketClient current = socket;
        socket = null;
        claimed.clear();
        if (current != null) {
            current.close();
        }
    }

    private synchronized boolean connect() {
        if (connected()) {
            return true;
        }
        try {
            WebSocketClient client = new WebSocketClient(new URI(url)) {

                @Override
                public void onOpen(ServerHandshake handshake) {
                    logger.info("Paired with studio.");
                }

                @Override
                public void onMessage(String frame) {
                    handle(frame);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    // Ordinary. A pairing socket is idle for long stretches and
                    // something in the middle will eventually close it; the next
                    // /link opens a new one.
                    logger.info("Studio pairing closed" + (reason.isEmpty() ? "." : ": " + reason));
                }

                @Override
                public void onError(Exception e) {
                    logger.warning("Studio pairing error: " + e.getMessage());
                }
            };
            client.setConnectionLostTimeout(PING_SECONDS);
            // Blocking, so /link can say whether it worked rather than
            // reporting success and failing somewhere the player cannot see.
            boolean opened = client.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!opened) {
                logger.warning("Could not reach studio at " + url + ".");
                return false;
            }
            socket = client;
            return true;
        } catch (URISyntaxException e) {
            logger.warning("The sync url " + url + " is not a url.");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * One frame.
     *
     * <p>Split on the first two spaces only. The third field is a URL or a
     * command and contains spaces of its own; splitting on all of them
     * truncates it, and the failure is a push that appears to succeed.
     */
    void handle(String frame) {
        if (frame == null || frame.isEmpty()) {
            return;
        }
        String[] parts = frame.split(" ", 3);
        String type = parts[0].toUpperCase(Locale.ROOT);
        if (parts.length < 3) {
            return;
        }
        String ref = parts[1];
        String payload = parts[2];

        switch (type) {
            case "APPLY":
                onApply.accept(ref, payload);
                return;
            case "GIVE":
                onGive.accept(ref, payload);
                return;
            default:
                // Everything else in the protocol is addressed at studio, or is
                // a message type this plugin has no use for. Ignored rather
                // than logged: an unknown frame is how a protocol grows.
        }
    }

    private void send(String frame) {
        WebSocketClient current = socket;
        if (current != null && current.isOpen()) {
            current.send(frame);
        }
    }
}
