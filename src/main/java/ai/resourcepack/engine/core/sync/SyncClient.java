package ai.resourcepack.engine.core.sync;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * The websocket to ResourcePack AI Studio, for live-testing a pack.
 *
 * <p><strong>The wire protocol is not ours.</strong> It is fixed by the
 * pairing service on the other end, which predates this plugin, so everything
 * here is an implementation of somebody else's spec rather than a design.
 * Frames are plain text, space-delimited, and split on the first two
 * spaces only, because a URL or a failure reason contains spaces and
 * truncating either is how a push silently does nothing.
 *
 * <p>One socket per server, opened on the first {@code /link} and reused for
 * every code after it. Codes are not one to one with connections.
 *
 * <p><strong>A claim outlives the socket, the session and the server.</strong>
 * The far end forgets which socket held a code the moment that socket goes,
 * and a socket goes all the time: the pairing service is redeployed, something
 * in the middle closes an idle connection, the server restarts. Each of those
 * used to cost the player their sync, and they had to fetch a new code and
 * type it again. Now the far end keeps a lost pairing claimable for a while by
 * the player who held it, and this side claims it back: it reopens the socket
 * whenever it holds a code ({@link #scheduleReconnect}), re-sends
 * {@code LINKED} for every claimer online when it does ({@link #relink}, from
 * the plugin's {@link #whenOpen} hook) and for a claimer as they rejoin, and
 * keeps the claims in a file so a restart picks them up. A player quitting is
 * not the end of their sync any more; only {@link #unlink} (a stop, or a new
 * code replacing the old) is.
 *
 * <p>Studio being reachable is not something the engine depends on: this is a
 * convenience for whoever is building the pack, and a server with no pairing
 * behaves exactly as it did before this class existed.
 */
public final class SyncClient {

    /** How long a socket waits before deciding a silent connection is dead. */
    private static final int PING_SECONDS = 30;

    /** The first wait before a trusted server tries to reopen a lost socket. */
    private static final int RECONNECT_FIRST_SECONDS = 5;

    /** The longest that wait grows to, doubling each failure. */
    private static final int RECONNECT_MAX_SECONDS = 300;

    /**
     * How long a claim nothing has ever arrived for is offered back after a
     * lost socket: a day, which is also how long the far end keeps a lost
     * pairing claimable.
     *
     * <p>A code becomes {@link Claim#confirmed} the first time a push, give,
     * skin or notification arrives for it, which proves the far end issued it
     * and linked it here. Until then it may be a typo, and the far end counts
     * every unknown code against this server's address; offering one for ever
     * on every reconnect and every join would spend that budget on nothing.
     */
    static final long UNCONFIRMED_RELINK_MS = 24L * 60 * 60 * 1000;

    /** The far end's word that a code is over for good (see {@link #handle}). */
    static final String RELINK_REFUSED = "RELINK_REFUSED";

    /** A code somebody claimed here: who, whether the far end has used it, and when. */
    record Claim(String name, boolean confirmed, long claimedAt) {

        /** Whether it is worth offering back after a lost socket. */
        boolean offered(long now) {
            return confirmed || now - claimedAt < UNCONFIRMED_RELINK_MS;
        }
    }

    private final String url;
    private final String serverToken;
    private final Logger logger;
    private final BiConsumer<String, String> onApply;
    private final BiConsumer<String, String> onGive;
    private final BiConsumer<String, String> onSkin;
    private final BiConsumer<String, String> onTell;

    /** Codes claimed on this server, and who claimed each. Kept in {@link #claimsFile}. */
    private final Map<String, Claim> claimed = new ConcurrentHashMap<>();

    /**
     * Codes already said ({@code LINKED}) down the CURRENT socket. Emptied
     * whenever a socket opens or is lost, because the far end keys a pairing
     * to the socket that claimed it, so a new socket has said nothing yet.
     */
    private final Set<String> announced = ConcurrentHashMap.newKeySet();

    /** Where the claims are kept between runs, or null to keep them in memory only. */
    private final Path claimsFile;

    /** Serialises writes of {@link #claimsFile}. */
    private final Object saveLock = new Object();

    /** Told a code and its claimer when the far end says it is over for good. */
    private volatile BiConsumer<String, String> onDropped = (code, name) -> { };

    private volatile WebSocketClient socket;

    /**
     * Runs each time the socket comes up, first open included. Set by the
     * plugin so a trusted server can re-announce everybody online.
     */
    private volatile Runnable whenOpen;

    /** Set by {@link #close()}; a closed client never reopens itself. */
    private volatile boolean stopped;

    /**
     * The reconnect loop, for a trusted server. One daemon thread, so an
     * attempt blocking for its ten seconds never stalls the server.
     */
    private final ScheduledExecutorService reconnects = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "rpengine-sync-reconnect");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Guards the two fields below, and is deliberately NOT {@code this}.
     *
     * <p>{@link #connect()} holds this object's monitor for the whole blocking
     * handshake, and when that handshake fails the library's read thread
     * calls {@code onClose} — which lands in {@link #lost} — before
     * {@code connectBlocking} returns. Take the monitor there and the two
     * threads wait on each other for ever: the handshake for its read thread
     * to finish, the read thread for the handshake to let go. A test found
     * it; a server with studio down would have hung its main thread.
     */
    private final Object reconnectLock = new Object();
    private boolean reconnectScheduled;
    private int reconnectDelaySeconds = RECONNECT_FIRST_SECONDS;

    /**
     * @param onApply called with the code and the url payload of an
     *                {@code APPLY}. The payload is "everything after the second
     *                space" and may be two space-joined urls, which is the
     *                caller's problem to split rather than ours to interpret
     * @param onGive  called with the code and the command of a {@code GIVE}
     */
    public SyncClient(String url, String serverToken, Logger logger,
                      BiConsumer<String, String> onApply, BiConsumer<String, String> onGive,
                      BiConsumer<String, String> onSkin, BiConsumer<String, String> onTell) {
        this(url, serverToken, logger, onApply, onGive, onSkin, onTell, null);
    }

    /**
     * As above, keeping the claims in {@code claimsFile} and reading back any a
     * previous run left there.
     */
    public SyncClient(String url, String serverToken, Logger logger,
                      BiConsumer<String, String> onApply, BiConsumer<String, String> onGive,
                      BiConsumer<String, String> onSkin, BiConsumer<String, String> onTell,
                      Path claimsFile) {
        this.claimsFile = claimsFile;
        this.url = url;
        this.serverToken = serverToken == null ? "" : serverToken;
        this.logger = logger;
        this.onApply = onApply;
        this.onGive = onGive;
        this.onSkin = onSkin;
        this.onTell = onTell;
        load();
    }

    /**
     * Opens the socket now, without claiming anything.
     *
     * <p>For a trusted server, which holds it open permanently because it
     * announces who is online rather than waiting for somebody to type a code.
     * Everybody else connects lazily on their first claim.
     *
     * <p>"Permanently" is a promise this class keeps, not the network: studio's
     * end is redeployed, idle sockets get closed by whatever sits between, and
     * a trusted server whose socket has quietly gone announces nobody — every
     * permalinked player on it is then "not in game" to studio however many
     * times they rejoin. So a failed open, and every later close, schedules a
     * retry with backoff, and {@link #whenOpen} lets the plugin re-announce
     * everybody once it lands.
     *
     * @return whether it opened
     */
    public boolean open() {
        if (connect()) {
            return true;
        }
        if (wantsSocket()) {
            scheduleReconnect();
        }
        return false;
    }

    /**
     * Opens the socket soon, off the calling thread, if any code is claimed.
     *
     * <p>For startup: claims a previous run left behind are only worth anything
     * once the socket is up to relink them, and blocking the server's start
     * for a handshake to a service that may be down is not worth it.
     */
    public void resume() {
        if (connected() || !wantsSocket()) {
            return;
        }
        synchronized (reconnectLock) {
            if (stopped || reconnectScheduled) {
                return;
            }
            reconnectScheduled = true;
            try {
                reconnects.schedule(this::retryConnect, 0, TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                reconnectScheduled = false;
            }
        }
    }

    /** Registers what to do when the far end says a claimed code is over for good. */
    public void onDropped(BiConsumer<String, String> hook) {
        this.onDropped = hook == null ? (code, name) -> { } : hook;
    }

    /**
     * Whether a socket is worth keeping open: on a trusted server always, and
     * anywhere else while somebody here holds a code.
     */
    private boolean wantsSocket() {
        return announcesPresence() || !claimed.isEmpty();
    }

    /**
     * Registers what to do each time the socket comes up, first open included.
     *
     * <p>Called on whichever thread opened the socket, which is never the
     * server's main thread guaranteed — the reconnect loop has one of its own —
     * so anything that touches the server schedules itself back onto it.
     */
    public void whenOpen(Runnable hook) {
        this.whenOpen = hook;
    }

    /**
     * Whether this server announces who is online.
     *
     * <p>Only a server holding the token does. Everything below is refused by
     * the far end from any other socket, so sending it would be noise rather
     * than a leak — but not sending it is honest and cheaper.
     */
    public boolean announcesPresence() {
        return !serverToken.isEmpty();
    }

    /**
     * Says a player is here, so studio can push to them without a code.
     *
     * <p>The cape hash is a fourth field on a message that had three, and the
     * far end reads the first three positionally — so an older sync ignores it
     * and an older plugin simply sends nothing there. See {@link PlayerCape}
     * for why studio cannot work this out for itself. {@code -} for a player
     * with no cape.
     *
     * <p>{@code joinedAtMillis} is a fifth field, positional and last like the
     * fourth, so an older sync ignores it. It is when this player's session
     * actually began: a player announced again after a reconnect has been
     * standing there the whole time, still wearing whatever studio last
     * pushed, and without it studio would date their session from the
     * re-announcement and push the same pack all over again. Zero or negative
     * means "now".
     *
     * @param since when they joined, as epoch milliseconds, or 0 for now
     */
    public void present(java.util.UUID playerId, String name, boolean bedrock, String capeHash, long since) {
        if (!announcesPresence()) {
            return;
        }
        if (!connected()) {
            // A closed socket drops the frame either way; what matters is that
            // somebody noticed. The reconnect re-announces them on arrival.
            scheduleReconnect();
            return;
        }
        send("PRESENT " + hex(playerId) + " " + name + " " + (bedrock ? "bedrock" : "java")
            + " " + (capeHash == null || capeHash.isEmpty() ? PlayerCape.NONE : capeHash)
            + " " + (since > 0 ? since : System.currentTimeMillis()));
    }

    /** Says a player has gone. */
    public void gone(java.util.UUID playerId) {
        if (announcesPresence()) {
            send("GONE " + hex(playerId));
        }
    }

    /** A uuid the way this protocol writes one: 32 hex, no dashes. */
    private static String hex(java.util.UUID id) {
        return id.toString().replace("-", "");
    }

    /** Whether the socket is open. */
    public boolean connected() {
        WebSocketClient current = socket;
        return current != null && current.isOpen();
    }

    /** Who claimed {@code code}, or null. */
    public String claimant(String code) {
        Claim claim = code == null ? null : claimed.get(code);
        return claim == null ? null : claim.name();
    }

    /** Every code {@code playerName} holds here, which is one unless something went wrong. */
    public List<String> codesOf(String playerName) {
        List<String> codes = new ArrayList<>();
        if (playerName == null) {
            return codes;
        }
        for (Map.Entry<String, Claim> entry : claimed.entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(playerName)) {
                codes.add(entry.getKey());
            }
        }
        return codes;
    }

    /** Whether anybody here holds a code. */
    public boolean hasClaims() {
        return !claimed.isEmpty();
    }

    /**
     * Claims back, down the current socket, every code {@code playerName}
     * holds that it has not said yet.
     *
     * <p>Called for everybody online when a socket opens and for a player as
     * they join. A code already said on this socket is skipped, so a join that
     * follows no drop sends nothing. With no socket it sends nothing either
     * and makes sure one is on its way; that open relinks whoever is online
     * then.
     *
     * <p>The far end accepts it only from the player who held the code, and
     * only for a while after the pairing was lost; past that it answers
     * {@code RELINK_REFUSED} and the claim is dropped ({@link #handle}).
     *
     * @return the codes it sent, so the caller can report their rosters again
     *         (the far end drops a lost pairing's roster with it)
     */
    public List<String> relink(String playerName, boolean bedrock) {
        List<String> sent = new ArrayList<>();
        if (playerName == null || claimed.isEmpty()) {
            return sent;
        }
        if (!connected()) {
            scheduleReconnect();
            return sent;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Claim> entry : claimed.entrySet()) {
            Claim claim = entry.getValue();
            if (!claim.name().equalsIgnoreCase(playerName) || !claim.offered(now)) {
                continue;
            }
            if (announced.add(entry.getKey())) {
                send("LINKED " + entry.getKey() + " " + playerName + " " + (bedrock ? "bedrock" : "java"));
                sent.add(entry.getKey());
            }
        }
        return sent;
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
        return link(code, playerName, false);
    }

    /**
     * As above, saying which edition the claimer plays. Studio reads it back to
     * decide whether a push needs a Bedrock pack built at all: it skips the
     * build only on a positive "java", so saying "java" for a Geyser player
     * (which this used to, for everybody) sends them a Java pack their client
     * cannot load and nothing else.
     */
    public synchronized boolean link(String code, String playerName, boolean bedrock) {
        if (code == null || playerName == null) {
            return false;
        }
        if (!connect()) {
            return false;
        }
        // A player holds one sync, so a new code ends the one before it, at the
        // far end too: left claimed, it would be offered back after every
        // reconnect for a pairing nobody is looking at.
        for (String old : codesOf(playerName)) {
            if (!old.equals(code)) {
                unlink(old);
            }
        }
        Claim was = claimed.get(code);
        claimed.put(code, new Claim(playerName, was != null && was.confirmed(),
                was != null ? was.claimedAt() : System.currentTimeMillis()));
        announced.add(code);
        send("LINKED " + code + " " + playerName + " " + (bedrock ? "bedrock" : "java"));
        save();
        return true;
    }

    /**
     * Drops a code, telling the far end so studio stops offering to push to it.
     * Final at the far end: unlike a lost socket, nothing claims it back.
     */
    public void unlink(String code) {
        if (code != null && claimed.remove(code) != null) {
            announced.remove(code);
            send("UNLINK " + code);
            save();
        }
    }

    /**
     * Drops every code this player claimed. For {@code /rp sync stop}, never
     * for a quit: a player who leaves keeps their claim, a push to them in the
     * meantime fails as {@code player-offline} (which is true), and it is
     * relinked when they come back.
     */
    public void forget(String playerName) {
        for (String code : codesOf(playerName)) {
            unlink(code);
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

    /** Tells the far end a skin was worn. */
    public void skinned(String code) {
        send("SKINNED " + code);
    }

    /** Tells the far end a skin was not worn, and why. */
    public void skinFailed(String code, String reason) {
        send("SKIN_FAILED " + code + " " + reason);
    }

    /** Tells the far end a notification was shown. */
    public void told(String code) {
        send("TOLD " + code);
    }

    /** Tells the far end a notification was not shown, and why. */
    public void tellFailed(String code, String reason) {
        send("TELL_FAILED " + code + " " + reason);
    }

    /** Closes the socket and forgets every code. Final: nothing reopens after this. */
    public synchronized void close() {
        stopped = true;
        reconnects.shutdownNow();
        WebSocketClient current = socket;
        socket = null;
        // Memory only: the file is what the next run relinks from, so a
        // restart is not the end of anybody's sync.
        claimed.clear();
        announced.clear();
        if (current != null) {
            current.close();
        }
    }

    /**
     * A socket that closed under us, as opposed to one {@link #close()} shut.
     *
     * <p>Only the socket we are currently holding counts: a stale close from
     * one already replaced must not knock out its successor, and a deliberate
     * close has already cleared the field, so it schedules nothing.
     */
    private void lost(WebSocketClient which) {
        // No monitor here — see reconnectLock. `socket` is volatile and only
        // ever assigned a freshly opened client, so a close arriving for any
        // other object is stale by construction, whatever order it lands in.
        if (socket != which) {
            return;
        }
        socket = null;
        announced.clear();
        if (wantsSocket()) {
            scheduleReconnect();
        }
    }

    /**
     * Arranges one reconnect attempt, if one is not already pending.
     *
     * <p>A trusted server always does, and any other server while somebody
     * here holds a code, so their pairing is claimed back rather than lost
     * (see the class comment). A server nobody has synced on stays lazy and
     * opens on the first {@code /rp sync}. Failures back off from five seconds
     * to five minutes, so a studio that is down for an hour is asked a dozen
     * times rather than a thousand.
     */
    private void scheduleReconnect() {
        synchronized (reconnectLock) {
            if (stopped || reconnectScheduled || !wantsSocket()) {
                return;
            }
            reconnectScheduled = true;
            int delay = reconnectDelaySeconds;
            reconnectDelaySeconds = Math.min(reconnectDelaySeconds * 2, RECONNECT_MAX_SECONDS);
            try {
                reconnects.schedule(this::retryConnect, delay, TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // close() raced us; nothing to reopen.
                reconnectScheduled = false;
            }
        }
    }

    private void retryConnect() {
        synchronized (reconnectLock) {
            reconnectScheduled = false;
        }
        if (stopped) {
            return;
        }
        if (connect()) {
            logger.info("Reconnected to studio.");
        } else {
            scheduleReconnect();
        }
    }

    private synchronized boolean connect() {
        if (connected()) {
            return true;
        }
        try {
            // The token, when there is one, is what makes this a TRUSTED
            // server: it may then announce who is online, so studio can push
            // to a player by uuid without anybody typing a code. A server
            // without one is an ordinary pairing client and announces nothing.
            Map<String, String> headers = new java.util.HashMap<>();
            if (!serverToken.isEmpty()) {
                headers.put("X-Server-Token", serverToken);
            }
            WebSocketClient client = new WebSocketClient(new URI(url), new org.java_websocket.drafts.Draft_6455(),
                    headers) {

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
                    // something in the middle will eventually close it; `lost`
                    // reopens it while anybody here holds a code (or always, on
                    // a trusted server), and the open relinks them.
                    logger.info("Studio pairing closed" + (reason.isEmpty() ? "." : ": " + reason));
                    lost(this);
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
            // A new socket has claimed nothing yet, whatever the last one did.
            announced.clear();
            synchronized (reconnectLock) {
                reconnectDelaySeconds = RECONNECT_FIRST_SECONDS;
            }
            Runnable hook = whenOpen;
            if (hook != null) {
                hook.run();
            }
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

        if (type.equals(RELINK_REFUSED)) {
            // The far end's word that this code is over for good: ended on
            // purpose in Studio, or lost too long ago to claim back. Sent only
            // to a socket that named the code AND its claimer, so it is not
            // something a stranger can say about somebody else's code.
            Claim gone = claimed.remove(ref);
            announced.remove(ref);
            if (gone != null) {
                save();
                onDropped.accept(ref, gone.name());
            }
            return;
        }
        confirm(ref);

        switch (type) {
            case "APPLY":
                onApply.accept(ref, payload);
                return;
            case "GIVE":
                onGive.accept(ref, payload);
                return;
            case "SKIN":
                // "<value> <signature>", positional like the rest. Safe to
                // split on a space because both halves are base64 and the
                // relay refuses any that carry whitespace.
                onSkin.accept(ref, payload);
                return;
            case "TELL":
                // JSON rather than positional fields: a notification is prose
                // and both its fields contain spaces. The plugin owns the
                // shape; this only carries it.
                onTell.accept(ref, payload);
                return;
            default:
                // Everything else in the protocol is addressed at studio, or is
                // a message type this plugin has no use for. Ignored rather
                // than logged: an unknown frame is how a protocol grows.
        }
    }

    /**
     * Notes that the far end has used {@code ref}, which proves it issued the
     * code and linked it here (see {@link #UNCONFIRMED_RELINK_MS}).
     */
    private void confirm(String ref) {
        Claim claim = claimed.get(ref);
        if (claim != null && !claim.confirmed()
                && claimed.replace(ref, claim, new Claim(claim.name(), true, claim.claimedAt()))) {
            save();
        }
    }

    /**
     * Reads back the claims a previous run kept: one per line, the code, the
     * name, 1 or 0 for confirmed and when it was claimed, tab-separated. A
     * line that does not parse is skipped, and an unconfirmed claim past its
     * day is dropped, since it would not be offered anyway.
     */
    private void load() {
        if (claimsFile == null || !Files.isRegularFile(claimsFile)) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            for (String line : Files.readAllLines(claimsFile, StandardCharsets.UTF_8)) {
                String[] fields = line.split("\t");
                if (fields.length < 4 || !SyncCodes.isCode(fields[0]) || !fields[1].matches("[A-Za-z0-9_.*-]{1,32}")) {
                    continue;
                }
                long claimedAt;
                try {
                    claimedAt = Long.parseLong(fields[3].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                Claim claim = new Claim(fields[1], fields[2].equals("1"), claimedAt);
                if (claim.offered(now)) {
                    claimed.put(fields[0], claim);
                }
            }
        } catch (IOException e) {
            logger.warning("Could not read the Studio sync claims in " + claimsFile + ": " + e.getMessage());
        }
    }

    /** Writes the claims down, replacing the file whole so a crash mid-write cannot leave half of it. */
    private void save() {
        if (claimsFile == null || stopped) {
            return;
        }
        synchronized (saveLock) {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, Claim> entry : claimed.entrySet()) {
                Claim claim = entry.getValue();
                out.append(entry.getKey()).append('\t').append(claim.name()).append('\t')
                        .append(claim.confirmed() ? '1' : '0').append('\t').append(claim.claimedAt()).append('\n');
            }
            try {
                Path parent = claimsFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path temp = claimsFile.resolveSibling(claimsFile.getFileName() + ".tmp");
                Files.writeString(temp, out, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, claimsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, claimsFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.warning("Could not save the Studio sync claims to " + claimsFile + ": " + e.getMessage());
            }
        }
    }

    private void send(String frame) {
        WebSocketClient current = socket;
        if (current != null && current.isOpen()) {
            current.send(frame);
        }
    }
}
