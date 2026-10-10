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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves built packs over HTTP so a client can download one.
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

    private final Map<String, BuiltPack> byPath = new ConcurrentHashMap<>();
    private final Map<InetAddress, AtomicInteger> perAddress = new ConcurrentHashMap<>();
    private final Set<Connection> open = ConcurrentHashMap.newKeySet();
    private final String publicAddress;
    private final Limits limits;

    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private ScheduledExecutorService watchdog;

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
        this.publicAddress = trimSlash(publicAddress == null ? "" : publicAddress);
        this.limits = limits;
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

    /** Whether it is currently listening. */
    public synchronized boolean running() {
        return listener != null;
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
        return publicAddress + path;
    }

    /** Stops serving {@code bundle}, for a pack that belonged to a player who has left. */
    public void unregister(String bundle) {
        byPath.entrySet().removeIf(entry -> entry.getValue().bundle().equals(bundle));
    }

    /** The URL for a bundle, if one is registered. */
    public Optional<String> url(String bundle) {
        for (Map.Entry<String, BuiltPack> entry : byPath.entrySet()) {
            if (entry.getValue().bundle().equals(bundle)) {
                return Optional.of(publicAddress + entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** How many packs are being served. */
    public int size() {
        return byPath.size();
    }

    static String pathFor(BuiltPack pack) {
        return "/packs/" + pack.bundle() + "/" + pack.sha1() + ".zip";
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
