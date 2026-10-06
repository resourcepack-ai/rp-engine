package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves built packs over HTTP so a client can download one.
 *
 * <p>Built in rather than left to the server owner, because a plugin that
 * builds a pack and then tells somebody to go and find hosting for it has not
 * finished the job. An owner who already has hosting can ignore this and
 * publish the zips themselves; the URL is the only thing the rest of the engine
 * needs.
 *
 * <p>Uses the JDK's own HTTP server. That is a real constraint accepted
 * deliberately: it is not a good general-purpose web server, but this serves
 * a handful of static files to the same few clients, and the alternative is
 * shading Netty into a plugin jar that already runs inside a Netty server.
 *
 * <p><strong>The hash is in the URL.</strong> A client caches by hash, and
 * some caches and proxies cache by URL, so a rebuilt bundle gets a new path
 * and cannot be served a stale body by anything in between.
 */
public final class PackHost {

    private final Map<String, BuiltPack> byPath = new ConcurrentHashMap<>();
    private final String publicAddress;

    private HttpServer server;
    private ExecutorService workers;

    /**
     * @param publicAddress how a CLIENT reaches this host, scheme and all
     *                      ({@code http://play.example.com:8080}), which is
     *                      not something the server can work out for itself:
     *                      it may be behind a proxy, a NAT, or a hostname
     *                      only the outside world knows
     */
    public PackHost(String publicAddress) {
        this.publicAddress = trimSlash(publicAddress == null ? "" : publicAddress);
    }

    /**
     * Starts listening.
     *
     * @param port the port to bind, or 0 to be given a free one
     * @return the port actually bound
     */
    public synchronized int start(int port) throws IOException {
        if (server != null) {
            return server.getAddress().getPort();
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        // A small fixed pool, not a cached one: this serves a few megabytes to
        // a few players and an unbounded pool would let a hostile client open
        // as many threads as it liked inside somebody's game server.
        workers = Executors.newFixedThreadPool(4);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    /** Stops listening and drops every registered pack. */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        byPath.clear();
    }

    /** Whether it is currently listening. */
    public synchronized boolean running() {
        return server != null;
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

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                respondEmpty(exchange, 405);
                return;
            }
            BuiltPack pack = byPath.get(exchange.getRequestURI().getPath());
            if (pack == null || !Files.isReadable(pack.file())) {
                respondEmpty(exchange, 404);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            // The path already carries the hash, so the body at a given URL can
            // never change. Anything in between is free to keep it for ever.
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.getResponseHeaders().set("ETag", '"' + pack.sha1() + '"');

            long size = Files.size(pack.file());
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, size);
            try (InputStream in = Files.newInputStream(pack.file());
                 OutputStream out = exchange.getResponseBody()) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            // A client that hung up mid-download is ordinary, not an incident.
            // Never let it reach the server log as a stack trace.
            respondQuietly(exchange);
        } finally {
            exchange.close();
        }
    }

    private static void respondEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static void respondQuietly(HttpExchange exchange) {
        try {
            exchange.sendResponseHeaders(500, -1);
        } catch (IOException ignored) {
            // The socket is already gone. Nothing to say and nobody to say it to.
        }
    }

    private static String trimSlash(String address) {
        return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
    }
}
