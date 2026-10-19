package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gets built packs to players: decides the link each player is sent, and
 * serves the pack behind it when this server is the one serving it.
 *
 * <p>There are four ways, {@link Mode}, chosen by {@code host.mode}. The
 * default uploads each pack to ResourcePack AI's pack host, because the two
 * that serve from this machine each need something a new server often lacks
 * (an open second port, or no proxy in front), and the only symptom of either
 * going wrong is "failed to download" on the player's screen. A pack whose
 * upload fails is served from the game port instead, if it can be.
 *
 * <p>What follows is about the own-port server, the one with the most to say.
 *
 * <p>Built in rather than left to the server owner, because a plugin that
 * builds a pack and then tells somebody to go and find hosting for it has not
 * finished the job. An owner who already has hosting can ignore this and
 * publish the zips themselves; the URL is the only thing the rest of the engine
 * needs.
 *
 * <p>A deliberately tiny HTTP/1.1 server: GET and HEAD of a handful of static
 * files, one request per connection. It used to be the JDK's own
 * {@code HttpServer} on a pool of four threads, and that server reads a
 * request's headers on the worker thread with no time limit, so four clients
 * that sent one byte and then nothing took the pack host away from everybody
 * else. Its only timeouts are JVM-wide system properties, which would also
 * have changed every other plugin's server in the same process. So the
 * connection handling is this class's own, and it is bounded at every step:
 * <ul>
 *   <li>a client has {@link Limits#headerMillis} to send its request once a
 *       worker picks it up;</li>
 *   <li>a response that makes no progress for {@link Limits#stallMillis} is
 *       cut off, and none may run longer than {@link Limits#responseMillis};</li>
 *   <li>one address may hold at most {@link Limits#perAddress} connections at
 *       once, waiting or being served. Loopback and private addresses are not
 *       counted, because a reverse proxy in front of the host makes every
 *       player arrive from one of those;</li>
 *   <li>the pool and the queue in front of it are both bounded, and a
 *       connection beyond them is answered 503 rather than held.</li>
 * </ul>
 *
 * <p><strong>The hash is in the URL.</strong> A client caches by hash, and
 * some caches and proxies cache by URL, so a rebuilt bundle gets a new path
 * and cannot be served a stale body by anything in between.
 */
public final class PackHost {

    /**
     * How much the host will give any one client.
     *
     * @param workers        connections served at once
     * @param queue          connections waiting for a worker
     * @param perAddress     connections one remote address may hold, waiting or served
     * @param queueMillis    how long a connection may wait for a worker
     * @param headerMillis   how long a client has to send its request once served
     * @param stallMillis    how long a response may go without the client taking any of it
     * @param responseMillis how long any one response may take in all
     * @param exemptPrivate  whether loopback and private addresses skip the per-address cap
     */
    record Limits(int workers, int queue, int perAddress, long queueMillis, long headerMillis,
                  long stallMillis, long responseMillis, boolean exemptPrivate) {

        /**
         * What a game server gets. Thirty-two downloads at once is far more
         * than a join wave needs (the queue holds the rest), six per address
         * leaves room for a household behind one NAT, and half an hour is a
         * large pack over a poor line.
         */
        static final Limits DEFAULT = new Limits(32, 1024, 6,
                TimeUnit.MINUTES.toMillis(5), TimeUnit.SECONDS.toMillis(10),
                TimeUnit.SECONDS.toMillis(30), TimeUnit.MINUTES.toMillis(30), true);
    }

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_DRAINED_BODY = 64 * 1024;
    private static final int CHUNK = 64 * 1024;

    /** How players download what this server builds. {@code host.mode} in config.yml. */
    public enum Mode {
        /** Uploaded to ResourcePack AI's pack host, which players download from. */
        UPLOAD("upload"),
        /** Served by this server on its Minecraft port. */
        SAME_PORT("same-port"),
        /** Served by this server on a port of its own, at an address the config gives. */
        OWN_PORT("own-port"),
        /** Not served: the owner hosts the zips somewhere themselves. */
        NONE("none");

        private final String key;

        Mode(String key) {
            this.key = key;
        }

        /** As written in config.yml. */
        public String key() {
            return key;
        }

        /** The mode {@code value} names, or empty when it names none. */
        public static Optional<Mode> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            String wanted = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Mode mode : values()) {
                if (mode.key.equals(wanted)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    /** One pack's way onto the pack host. */
    private static final class Upload {
        final BuiltPack pack;
        volatile String url;
        volatile boolean done;
        volatile boolean failed;
        private final List<Runnable> waiting = new ArrayList<>();

        Upload(BuiltPack pack) {
            this.pack = pack;
        }

        boolean pending() {
            return !done && !failed;
        }

        /** Books {@code then} for when this settles; false when it has already. */
        synchronized boolean await(Runnable then) {
            if (!pending()) {
                return false;
            }
            waiting.add(then);
            return true;
        }

        synchronized List<Runnable> settle() {
            List<Runnable> all = new ArrayList<>(waiting);
            waiting.clear();
            return all;
        }
    }

    private final Map<String, BuiltPack> byPath = new ConcurrentHashMap<>();
    private final Map<InetAddress, AtomicInteger> perAddress = new ConcurrentHashMap<>();
    private final Set<Connection> open = ConcurrentHashMap.newKeySet();
    private final String publicAddress;
    private final Limits limits;
    private final Mode mode;
    private final PackUploader uploader;
    private final Executor main;
    private final Logger logger;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    /** Messages said once a run, so a broken upload does not say so on every rebuild. */
    private final Set<String> said = ConcurrentHashMap.newKeySet();

    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private ScheduledExecutorService watchdog;
    private ExecutorService uploadThread;
    private SamePortServer samePort;
    private volatile Function<UUID, String> playerAddress = id -> null;
    /** False behind a proxy, where a link to the game port reaches the proxy and not this server. */
    private volatile boolean gamePortReachable = true;

    /**
     * @param publicAddress how a CLIENT reaches this host, scheme and all
     *                      ({@code http://play.example.com:8080}), which is
     *                      not something the server can work out for itself:
     *                      it may be behind a proxy, a NAT, or a hostname
     *                      only the outside world knows
     */
    public PackHost(String publicAddress) {
        this(publicAddress, Limits.DEFAULT);
    }

    PackHost(String publicAddress, Limits limits) {
        this(Mode.OWN_PORT, publicAddress, limits, null, Runnable::run, null);
    }

    PackHost(Mode mode, String publicAddress, Limits limits, PackUploader uploader, Executor main, Logger logger) {
        this.mode = mode;
        this.publicAddress = trimSlash(publicAddress == null ? "" : publicAddress);
        this.limits = limits;
        this.uploader = uploader;
        this.main = main;
        this.logger = logger;
        if (mode == Mode.UPLOAD) {
            uploadThread = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "RPEngine pack upload"));
        }
    }

    /**
     * A host in any mode.
     *
     * @param publicAddress the own-port address; unused by the others
     * @param uploader      where packs go in {@link Mode#UPLOAD}; unused by the others
     * @param main          where a finished upload's follow-up runs (the server thread)
     */
    public static PackHost create(Mode mode, String publicAddress, PackUploader uploader, Executor main,
                                  Logger logger) {
        return new PackHost(mode, publicAddress, Limits.DEFAULT, uploader, main, logger);
    }

    public Mode mode() {
        return mode;
    }

    /**
     * How a player reached this server, {@code host:port} as they typed it,
     * for links to the game port: such a link has to use the address the
     * player already reached it by, which is the one address known to work.
     */
    public void playerAddresses(Function<UUID, String> addresses) {
        this.playerAddress = addresses == null ? id -> null : addresses;
    }

    /** Says whether players can reach this server's game port directly; not behind a proxy. */
    public void gamePortReachable(boolean reachable) {
        this.gamePortReachable = reachable;
    }

    /**
     * Starts serving on the game port, for {@link Mode#SAME_PORT} or as the
     * fallback for an upload that failed.
     *
     * @return whether it is now serving there
     */
    public synchronized boolean startSamePort() {
        if (samePort == null) {
            samePort = new SamePortServer(byPath::get, logger == null ? Logger.getAnonymousLogger() : logger);
        }
        return samePort.start();
    }

    /**
     * Starts listening.
     *
     * @param port the port to bind, or 0 to be given a free one
     * @return the port actually bound
     */
    public synchronized int start(int port) throws IOException {
        if (listener != null) {
            return listener.getLocalPort();
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port), 128);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        listener = socket;
        AtomicInteger names = new AtomicInteger();
        workers = new ThreadPoolExecutor(limits.workers(), limits.workers(), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(limits.queue()),
                runnable -> daemon(runnable, "RPEngine pack host " + names.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "RPEngine pack host watchdog"));
        long tick = Math.max(10, Math.min(1000, limits.headerMillis() / 4));
        watchdog.scheduleWithFixedDelay(this::enforceDeadlines, tick, tick, TimeUnit.MILLISECONDS);
        ThreadPoolExecutor pool = workers;
        daemon(() -> acceptLoop(socket, pool), "RPEngine pack host listener").start();
        return socket.getLocalPort();
    }

    /** Stops listening and drops every registered pack. */
    public synchronized void stop() {
        if (samePort != null) {
            samePort.stop();
        }
        if (uploadThread != null) {
            uploadThread.shutdownNow();
        }
        uploads.clear();
        if (listener != null) {
            closeQuietly(listener);
            listener = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        for (Connection connection : open) {
            connection.close();
        }
        open.clear();
        perAddress.clear();
        byPath.clear();
    }

    /**
     * Whether this server's packs are being got to players at all: listening
     * on its own port or the game's, or uploading. False only when hosting is
     * off, or the one way it was told to use could not start.
     */
    public synchronized boolean running() {
        switch (mode) {
            case UPLOAD:
                return uploadThread != null && !uploadThread.isShutdown();
            case SAME_PORT:
                return samePort != null && samePort.running();
            case OWN_PORT:
                return listener != null;
            default:
                return false;
        }
    }

    /**
     * Makes {@code pack} downloadable and returns the URL to send a client.
     *
     * <p>Registering a bundle again replaces the previous build. The old path
     * is dropped rather than kept alive: a client that has not finished
     * downloading the old one gets a 404 and retries, which is a better
     * outcome than a server that accumulates every build it has ever made.
     */
    public String register(BuiltPack pack) {
        if (pack == null) {
            return "";
        }
        byPath.entrySet().removeIf(entry -> entry.getValue().bundle().equals(pack.bundle()));
        String path = pathFor(pack);
        byPath.put(path, pack);
        // A pack pushed from Studio is served by Studio already, at the link it
        // carries, so there is nothing to upload.
        if (mode == Mode.UPLOAD && pack.url().isEmpty()) {
            upload(pack);
        }
        return publicAddress + path;
    }

    /** Stops serving {@code bundle}, for a pack that belonged to a player who has left. */
    public void unregister(String bundle) {
        byPath.entrySet().removeIf(entry -> entry.getValue().bundle().equals(bundle));
        uploads.remove(bundle);
    }

    /** The link for a bundle, where it can be given without knowing who it is for. */
    public Optional<String> url(String bundle) {
        return url(bundle, null);
    }

    /**
     * The link to send {@code player} for {@code bundle}, or empty when there
     * is none to send them: nothing registered, hosting off, an upload still
     * running ({@link #pending}), or one that failed with nowhere to fall back.
     */
    public Optional<String> url(String bundle, UUID player) {
        String path = null;
        for (Map.Entry<String, BuiltPack> entry : byPath.entrySet()) {
            if (entry.getValue().bundle().equals(bundle)) {
                path = entry.getKey();
                break;
            }
        }
        if (path == null) {
            return Optional.empty();
        }
        switch (mode) {
            case OWN_PORT:
                return Optional.of(publicAddress + path);
            case SAME_PORT:
                return gamePortLink(path, player);
            case UPLOAD: {
                Upload upload = uploads.get(bundle);
                if (upload == null) {
                    return Optional.empty();
                }
                if (upload.done) {
                    return Optional.of(upload.url);
                }
                if (upload.failed && samePort != null && samePort.running()) {
                    return gamePortLink(path, player);
                }
                return Optional.empty();
            }
            default:
                return Optional.empty();
        }
    }


    /** Returns the locally hosted URL for a registered pack. */
    public synchronized Optional<String> localUrl(BuiltPack pack) {
        if (pack == null || mode != Mode.UPLOAD || listener == null) {
            return Optional.empty();
        }

        String path = pathFor(pack);
        if (!byPath.containsKey(path)) {
            return Optional.empty();
        }

        return Optional.of(publicAddress + path);
    }


    private Optional<String> gamePortLink(String path, UUID player) {
        String address = player == null ? null : playerAddress.apply(player);
        if (address == null || address.isEmpty()) {
            if (player != null) {
                sayOnce("Could not tell which address a player used to reach this server, so they cannot be sent a "
                        + "link to its game port. Set host.mode to upload or own-port in config.yml.");
            }
            return Optional.empty();
        }
        return Optional.of("http://" + address + path);
    }

    /** Whether {@code bundle} is on its way to the pack host and has no link yet. */
    public boolean pending(String bundle) {
        Upload upload = mode == Mode.UPLOAD ? uploads.get(bundle) : null;
        return upload != null && upload.pending();
    }

    /**
     * Runs {@code then} on the server thread once {@code bundle}'s upload has
     * settled one way or the other, or right now when none is running.
     */
    public void whenSettled(String bundle, Runnable then) {
        Upload upload = mode == Mode.UPLOAD ? uploads.get(bundle) : null;
        if (upload == null || !upload.await(then)) {
            then.run();
        }
    }

    /** Whether {@code url} is a pack host link rather than one to this server. */
    public boolean onPackHost(String url) {
        return uploader != null && url != null && url.startsWith(uploader.base() + "/");
    }

    private void upload(BuiltPack pack) {
        Upload previous = uploads.get(pack.bundle());
        if (previous != null && previous.pack.sha1().equals(pack.sha1()) && !previous.failed) {
            // The same bytes, already up or on the way.
            return;
        }
        Upload upload = new Upload(pack);
        uploads.put(pack.bundle(), upload);
        try {
            uploadThread.execute(() -> {
                try {
                    upload.url = uploader.ensure(pack);
                    upload.done = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    upload.failed = true;
                } catch (IOException | RuntimeException e) {
                    upload.failed = true;
                    main.execute(() -> fallBack(pack, e));
                } finally {
                    List<Runnable> waiting = upload.settle();
                    if (!waiting.isEmpty()) {
                        main.execute(() -> waiting.forEach(Runnable::run));
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // Stopping.
            upload.failed = true;
        }
    }

    /** An upload failed: serve that pack from the game port instead, and say so once. */
    private void fallBack(BuiltPack pack, Exception why) {
        if (uploadThread == null || uploadThread.isShutdown()) {
            return;
        }
        String reason = why.getMessage() == null ? why.toString() : why.getMessage();
        if (!gamePortReachable) {
            sayOnce("Could not upload " + pack.bundle() + " to the pack host (" + reason + "), and this server is "
                    + "behind a proxy, so it cannot be served on the game port instead. Players will not be sent it "
                    + "until an upload works; to serve it yourself, set host.mode to own-port in config.yml.");
        } else if (startSamePort()) {
            sayOnce("Could not upload " + pack.bundle() + " to the pack host (" + reason + "), so this server is "
                    + "serving it on its game port instead. Players cannot download it from there if this server "
                    + "is behind a proxy (Velocity, BungeeCord): then set host.mode to own-port in config.yml.");
        } else {
            sayOnce("Could not upload " + pack.bundle() + " to the pack host (" + reason + "), and could not serve "
                    + "it on the game port either, so players will not be sent it. Set host.mode to own-port in "
                    + "config.yml, with a port and an address players can reach.");
        }
    }

    private void sayOnce(String message) {
        if (logger != null && said.add(message)) {
            logger.warning(message);
        }
    }

    /**
     * Keeps every uploaded pack on the pack host, which forgets one nobody has
     * touched for a month, and tries again any upload that failed. Call it a
     * few times a day; the work happens off the server thread.
     */
    public void refresh() {
        if (mode != Mode.UPLOAD || uploadThread == null || uploadThread.isShutdown()) {
            return;
        }
        for (Upload upload : uploads.values()) {
            if (upload.failed) {
                // Players joining from now on get it from the pack host again.
                upload(upload.pack);
                continue;
            }
            if (!upload.done) {
                continue;
            }
            try {
                uploadThread.execute(() -> {
                    try {
                        uploader.ensure(upload.pack);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException | RuntimeException e) {
                        // Still there for weeks yet; tomorrow's try is soon enough.
                    }
                });
            } catch (RejectedExecutionException e) {
                return;
            }
        }
    }

    /** How many packs are being served. */
    public int size() {
        return byPath.size();
    }

    static String pathFor(BuiltPack pack) {
        return "/packs/" + pack.bundle() + "/" + pack.sha1() + ".zip";
    }

    /**
     * What is wrong with {@code address} as the way a player reaches a host
     * listening on {@code port}, in words for a server owner, or nothing.
     *
     * <p>Said at startup because the failure it causes is said nowhere else:
     * the client shows "failed to download" and the server hears only a status
     * code. The default address is loopback, which works for somebody playing
     * on the machine the server runs on and for nobody else, so a server that
     * was tested locally and then opened up fails for every player at once.
     * A port that differs is only a warning, because a proxy or a port forward
     * in front of the host makes that correct.
     */
    public static java.util.List<String> addressProblems(String address, int port) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        URI uri;
        try {
            uri = new URI(address == null ? "" : address.trim());
        } catch (URISyntaxException e) {
            problems.add("host.public-address \"" + address + "\" is not a URL. It should look like http://play.example.com:"
                    + port + ".");
            return problems;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) {
            problems.add("host.public-address \"" + address + "\" is not an http:// URL with a host in it. It should look like "
                    + "http://play.example.com:" + port + ".");
            return problems;
        }
        String host = uri.getHost();
        if (host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1")
                || host.equals("0.0.0.0")) {
            problems.add("host.public-address is " + address + ", which to a player means THEIR OWN computer, so only "
                    + "somebody playing on this machine can download packs. Set it to this server's public IP or "
                    + "domain, e.g. http://play.example.com:" + port + ".");
        }
        int said = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        if (port > 0 && said != port) {
            problems.add("host.public-address points at port " + said + " but the pack host listens on " + port
                    + " (host.port). Unless a proxy or port forward maps one to the other, players cannot download packs.");
        }
        return problems;
    }

    // ---- connections -------------------------------------------------------

    /** One accepted socket, and the time by which it must next have done something. */
    private static final class Connection {
        final Socket socket;
        final InetAddress address;
        final boolean counted;
        volatile long deadline;
        volatile long hardDeadline = Long.MAX_VALUE;

        Connection(Socket socket, InetAddress address, boolean counted, long deadline) {
            this.socket = socket;
            this.address = address;
            this.counted = counted;
            this.deadline = deadline;
        }

        void close() {
            closeQuietly(socket);
        }
    }

    private void acceptLoop(ServerSocket socket, ThreadPoolExecutor pool) {
        while (!socket.isClosed()) {
            Socket accepted;
            try {
                accepted = socket.accept();
            } catch (IOException e) {
                if (socket.isClosed()) {
                    return;
                }
                continue;
            }
            admit(accepted, pool);
        }
    }

    private void admit(Socket socket, ThreadPoolExecutor pool) {
        InetAddress address = socket.getInetAddress();
        boolean counted = !(limits.exemptPrivate() && isPrivate(address));
        if (counted && !claim(address)) {
            // A few bytes to a socket nothing has written to yet cannot block.
            refuse(socket, 503);
            return;
        }
        Connection connection = new Connection(socket, address, counted, now() + limits.queueMillis());
        open.add(connection);
        try {
            pool.execute(() -> serve(connection));
        } catch (RejectedExecutionException e) {
            refuse(socket, 503);
            finish(connection);
        }
    }

    /** Takes one of an address's connections, or says it has none left. */
    private boolean claim(InetAddress address) {
        AtomicInteger count = perAddress.computeIfAbsent(address, a -> new AtomicInteger());
        if (count.incrementAndGet() > limits.perAddress()) {
            release(address);
            return false;
        }
        return true;
    }

    private void release(InetAddress address) {
        perAddress.computeIfPresent(address, (a, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private void finish(Connection connection) {
        if (open.remove(connection) && connection.counted) {
            release(connection.address);
        }
        connection.close();
    }

    /** Closes every connection that has run out of time; its worker then fails out of its read or write. */
    private void enforceDeadlines() {
        long now = now();
        for (Connection connection : open) {
            if (now > connection.deadline || now > connection.hardDeadline) {
                connection.close();
            }
        }
    }

    private void serve(Connection connection) {
        Socket socket = connection.socket;
        try {
            if (socket.isClosed()) {
                return;
            }
            connection.deadline = now() + limits.headerMillis();
            socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, limits.headerMillis())));
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Request request = Request.read(in);
            if (request == null) {
                writeHead(out, 400, "Bad Request", null);
                return;
            }
            drain(in, request.contentLength);
            connection.deadline = now() + limits.stallMillis();
            connection.hardDeadline = now() + limits.responseMillis();
            respond(connection, request, out);
            socket.shutdownOutput();
        } catch (IOException e) {
            // A client that hung up, or ran out of time, is ordinary rather
            // than an incident. Never let it reach the server log.
        } finally {
            finish(connection);
        }
    }

    private void respond(Connection connection, Request request, OutputStream out) throws IOException {
        if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
            writeHead(out, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n");
            return;
        }
        BuiltPack pack = request.path == null ? null : byPath.get(request.path);
        if (pack == null || !Files.isReadable(pack.file())) {
            writeHead(out, 404, "Not Found", null);
            return;
        }
        long size = Files.size(pack.file());
        String headers = "Content-Type: application/zip\r\n"
                // The path already carries the hash, so the body at a given URL
                // can never change. Anything in between is free to keep it for ever.
                + "Cache-Control: public, max-age=31536000, immutable\r\n"
                + "ETag: \"" + pack.sha1() + "\"\r\n"
                + "Content-Length: " + size + "\r\n";
        out.write(("HTTP/1.1 200 OK\r\n" + headers + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        if ("HEAD".equals(request.method)) {
            out.flush();
            return;
        }
        byte[] buffer = new byte[CHUNK];
        try (InputStream file = Files.newInputStream(pack.file())) {
            long left = size;
            while (left > 0) {
                int read = file.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                left -= read;
                // Progress: the client is still taking it.
                connection.deadline = now() + limits.stallMillis();
            }
        }
        out.flush();
    }

    private static void writeHead(OutputStream out, int status, String reason, String extra) throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + (extra == null ? "" : extra)
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void refuse(Socket socket, int status) {
        try {
            writeHead(socket.getOutputStream(), status, "Service Unavailable", "Retry-After: 5\r\n");
        } catch (IOException ignored) {
            // Gone already.
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Reads and discards a small request body, so that closing the socket does
     * not reset it under a client that is still reading our answer.
     */
    private static void drain(InputStream in, long length) throws IOException {
        if (length <= 0 || length > MAX_DRAINED_BODY) {
            return;
        }
        in.skipNBytes(length);
    }

    /** The parts of a request line and its headers this host reads. */
    private static final class Request {
        final String method;
        final String path;
        final long contentLength;

        private Request(String method, String path, long contentLength) {
            this.method = method;
            this.path = path;
            this.contentLength = contentLength;
        }

        /** Reads one request's head, or null when it is not one. */
        static Request read(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int newlines = 0;
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return null;
                }
                head.write(b);
                if (head.size() > MAX_HEADER_BYTES) {
                    return null;
                }
                if (b == '\n') {
                    newlines++;
                    if (newlines == 2) {
                        break;
                    }
                } else if (b != '\r') {
                    newlines = 0;
                }
            }
            String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r?\n");
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length != 3 || !requestLine[2].startsWith("HTTP/")) {
                return null;
            }
            long contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    try {
                        contentLength = Long.parseLong(lines[i].substring(colon + 1).trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
            String path;
            try {
                path = new URI(requestLine[1]).getPath();
            } catch (URISyntaxException e) {
                path = null;
            }
            return new Request(requestLine[0].toUpperCase(Locale.ROOT), path, contentLength);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static boolean isPrivate(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        // IPv6 unique local addresses (fc00::/7), which isSiteLocalAddress does not cover.
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Already closed, or the peer is gone. Either way it is shut.
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    private static String trimSlash(String address) {
        return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
    }
}
