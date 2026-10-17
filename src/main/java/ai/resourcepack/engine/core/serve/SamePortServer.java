package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.stream.ChunkedNioFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Serves packs on the Minecraft server's own port.
 *
 * <p>A player's game downloads a pack over HTTP, and a server that serves its
 * own packs normally needs a second port for that, opened and allocated by
 * whoever hosts it, plus the address typed into a config. This needs neither:
 * the first bytes of every new connection to the game port are looked at, and
 * a connection that opens with {@code GET } or {@code HEAD } is a download
 * rather than a player, so it is taken out of the game's pipeline and
 * answered here. Anything else is handed on untouched, byte for byte, before
 * the game has seen any of it. A Minecraft connection cannot open with those
 * bytes: its first byte is a packet length and its second a packet id of 0.
 *
 * <p>The game's network listener is not API, so it is found by what it holds
 * (a list of {@link ChannelFuture}s) rather than by name, which is what keeps
 * one jar working across every version this plugin supports. If it cannot be
 * found, {@link #start} says so and returns false, and nothing has changed.
 *
 * <p><strong>It cannot work behind a proxy.</strong> On a Velocity or
 * BungeeCord network the player is connected to the proxy, which only speaks
 * Minecraft and never forwards a download to the server behind it.
 */
public final class SamePortServer {

    private static final String ACCEPTOR = "rpengine_pack_acceptor";
    private static final String INIT = "rpengine_pack_init";
    private static final String SNIFFER = "rpengine_pack_sniffer";
    private static final String CHUNKED = "rpengine_pack_chunked";

    private static final byte[] GET = "GET ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEAD = "HEAD ".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final long HEADER_MILLIS = 10_000;
    private static final long RESPONSE_MILLIS = TimeUnit.MINUTES.toMillis(30);
    /** Downloads at once. The rest are told to come back, which the client does. */
    private static final int MAX_ACTIVE = 64;

    private final Function<String, BuiltPack> lookup;
    private final Logger logger;
    private final List<Channel> injected = new CopyOnWriteArrayList<>();
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean running;

    /**
     * @param lookup a request path to the pack served there, or null
     */
    public SamePortServer(Function<String, BuiltPack> lookup, Logger logger) {
        this.lookup = lookup;
        this.logger = logger;
    }

    /**
     * Starts answering downloads on the game port.
     *
     * @return whether it is now doing so; when not, the log says why
     */
    public synchronized boolean start() {
        if (running) {
            return true;
        }
        List<ChannelFuture> listeners;
        try {
            listeners = serverChannels();
        } catch (Throwable e) {
            logger.warning("Could not serve packs on the game port: this server's network listener was not found ("
                    + e + ").");
            return false;
        }
        if (listeners.isEmpty()) {
            logger.warning("Could not serve packs on the game port: the server is not listening yet.");
            return false;
        }
        for (ChannelFuture future : listeners) {
            Channel channel = future.channel();
            if (channel.pipeline().get(ACCEPTOR) == null) {
                channel.pipeline().addFirst(ACCEPTOR, new Acceptor());
            }
            injected.add(channel);
        }
        running = true;
        return true;
    }

    /** Stops looking at new connections. Downloads already running finish. */
    public synchronized void stop() {
        for (Channel channel : injected) {
            try {
                if (channel.pipeline().get(ACCEPTOR) != null) {
                    channel.pipeline().remove(ACCEPTOR);
                }
            } catch (RuntimeException ignored) {
                // The listener is gone already, with the server.
            }
        }
        injected.clear();
        running = false;
    }

    public boolean running() {
        return running;
    }

    // ---- finding the listener ----------------------------------------------

    /** The futures of the game's bound server sockets, read out of the server by type. */
    private static List<ChannelFuture> serverChannels() throws ReflectiveOperationException {
        Object craftServer = Bukkit.getServer();
        Object server = craftServer.getClass().getMethod("getServer").invoke(craftServer);
        for (Class<?> type = server.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                        || !field.getType().getName().startsWith("net.minecraft")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(server);
                List<ChannelFuture> futures = value == null ? null : futuresIn(value);
                if (futures != null) {
                    return futures;
                }
            }
        }
        throw new NoSuchFieldException("no list of ChannelFuture on the server");
    }

    private static List<ChannelFuture> futuresIn(Object candidate) throws IllegalAccessException {
        for (Class<?> type = candidate.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                List<?> list = (List<?>) field.get(candidate);
                if (list == null) {
                    continue;
                }
                List<ChannelFuture> futures = new ArrayList<>();
                synchronized (list) {
                    for (Object item : list) {
                        if (!(item instanceof ChannelFuture future)) {
                            futures = null;
                            break;
                        }
                        futures.add(future);
                    }
                }
                if (futures != null && !futures.isEmpty()) {
                    return futures;
                }
            }
        }
        return null;
    }

    // ---- connections -------------------------------------------------------

    /** On the listening socket: puts {@link Init} on every connection it accepts. */
    @ChannelHandler.Sharable
    private final class Acceptor extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel child) {
                try {
                    child.pipeline().addLast(INIT, new Init());
                } catch (RuntimeException e) {
                    // Never stand between a player and the game.
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Runs once the connection is registered and puts the sniffer at the very
     * front, ahead of everything the game (and any other plugin) adds.
     */
    private final class Init extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRegistered(ChannelHandlerContext ctx) {
            ChannelPipeline pipeline = ctx.pipeline();
            try {
                pipeline.addFirst(SNIFFER, new Sniffer());
            } catch (RuntimeException ignored) {
                // Already there, or the connection is gone.
            }
            pipeline.remove(this);
            pipeline.fireChannelRegistered();
        }
    }

    /** Decides from the first bytes, then either steps aside or answers the download. */
    private final class Sniffer extends ChannelInboundHandlerAdapter {
        private ByteBuf seen;
        private boolean http;
        private boolean answered;
        private boolean counted;
        private ScheduledFuture<?> deadline;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf in)) {
                if (http) {
                    ReferenceCountUtil.release(msg);
                } else {
                    ctx.fireChannelRead(msg);
                }
                return;
            }
            if (answered) {
                in.release();
                return;
            }
            if (seen == null) {
                seen = in;
            } else {
                ByteBuf joined = ctx.alloc().buffer(seen.readableBytes() + in.readableBytes());
                joined.writeBytes(seen).writeBytes(in);
                seen.release();
                in.release();
                seen = joined;
            }
            if (!http) {
                Boolean verdict = isHttp(seen);
                if (verdict == null) {
                    return;
                }
                if (!verdict) {
                    ByteBuf mine = seen;
                    seen = null;
                    ctx.pipeline().remove(this);
                    ctx.fireChannelRead(mine);
                    return;
                }
                becomeHttp(ctx);
            }
            readRequest(ctx);
        }

        /** True, false, or null for "not enough bytes to say yet". */
        private Boolean isHttp(ByteBuf buf) {
            boolean couldBeGet = startsWith(buf, GET);
            boolean couldBeHead = startsWith(buf, HEAD);
            if (!couldBeGet && !couldBeHead) {
                return false;
            }
            if ((couldBeGet && buf.readableBytes() >= GET.length) || (couldBeHead && buf.readableBytes() >= HEAD.length)) {
                return true;
            }
            return null;
        }

        /** Whether what has arrived so far agrees with {@code prefix}, as far as it goes. */
        private boolean startsWith(ByteBuf buf, byte[] prefix) {
            int n = Math.min(buf.readableBytes(), prefix.length);
            for (int i = 0; i < n; i++) {
                if (buf.getByte(buf.readerIndex() + i) != prefix[i]) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Takes the connection away from the game: everything else comes out
         * of the pipeline, including the game's read timeout, which would
         * otherwise cut off any download that took longer than it.
         */
        private void becomeHttp(ChannelHandlerContext ctx) {
            http = true;
            ChannelPipeline pipeline = ctx.pipeline();
            for (String name : new ArrayList<>(pipeline.names())) {
                ChannelHandler handler = pipeline.get(name);
                if (handler == null || handler == this) {
                    continue;
                }
                try {
                    pipeline.remove(name);
                } catch (RuntimeException ignored) {
                    // Removed by somebody else in the meantime.
                }
            }
            deadline = ctx.executor().schedule(() -> {
                if (!answered) {
                    ctx.close();
                }
            }, HEADER_MILLIS, TimeUnit.MILLISECONDS);
        }

        private void readRequest(ChannelHandlerContext ctx) {
            int end = headerEnd(seen);
            if (end < 0) {
                if (seen.readableBytes() > MAX_HEADER_BYTES) {
                    answer(ctx, 431, "Request Header Fields Too Large", null, null, false);
                }
                return;
            }
            String head = seen.toString(seen.readerIndex(), end, StandardCharsets.ISO_8859_1);
            String[] requestLine = head.split("\r?\n", 2)[0].split(" ");
            if (requestLine.length != 3) {
                answer(ctx, 400, "Bad Request", null, null, false);
                return;
            }
            String method = requestLine[0].toUpperCase(Locale.ROOT);
            String path;
            try {
                path = new URI(requestLine[1]).getPath();
            } catch (URISyntaxException e) {
                path = null;
            }
            BuiltPack pack = path == null ? null : lookup.apply(path);
            if (pack == null || !Files.isReadable(pack.file())) {
                answer(ctx, 404, "Not Found", null, null, false);
                return;
            }
            if (active.incrementAndGet() > MAX_ACTIVE) {
                active.decrementAndGet();
                answer(ctx, 503, "Service Unavailable", "Retry-After: 5\r\n", null, false);
                return;
            }
            counted = true;
            answer(ctx, 200, "OK", "Content-Type: application/zip\r\n"
                    + "Cache-Control: public, max-age=31536000, immutable\r\n"
                    + "ETag: \"" + pack.sha1() + "\"\r\n", pack, !"HEAD".equals(method));
        }

        /** Where the blank line ending the request head is, or -1. */
        private int headerEnd(ByteBuf buf) {
            int start = buf.readerIndex();
            int limit = buf.writerIndex();
            for (int i = start; i + 3 < limit; i++) {
                if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n'
                        && buf.getByte(i + 2) == '\r' && buf.getByte(i + 3) == '\n') {
                    return i - start;
                }
            }
            for (int i = start; i + 1 < limit; i++) {
                if (buf.getByte(i) == '\n' && buf.getByte(i + 1) == '\n') {
                    return i - start;
                }
            }
            return -1;
        }

        /**
         * Sends the whole answer and closes. {@code pack} is what the length
         * is of, and {@code body} whether its bytes follow (not for HEAD).
         */
        private void answer(ChannelHandlerContext ctx, int status, String reason, String extra,
                            BuiltPack pack, boolean body) {
            answered = true;
            if (seen != null) {
                seen.release();
                seen = null;
            }
            if (deadline != null) {
                deadline.cancel(false);
            }
            long length = 0;
            if (pack != null) {
                try {
                    length = Files.size(pack.file());
                } catch (java.io.IOException e) {
                    ctx.close();
                    return;
                }
            }
            String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + (extra == null ? "" : extra)
                    + "Content-Length: " + length + "\r\n"
                    + "Connection: close\r\n\r\n";
            ctx.write(Unpooled.copiedBuffer(headers, StandardCharsets.ISO_8859_1));
            ChannelFuture done;
            if (pack != null && body) {
                try {
                    // Writes the file a chunk at a time as the socket takes it,
                    // rather than all of it into memory at once.
                    ctx.pipeline().addFirst(CHUNKED, new ChunkedWriteHandler());
                    done = ctx.writeAndFlush(new ChunkedNioFile(pack.file().toFile()));
                } catch (java.io.IOException e) {
                    ctx.close();
                    return;
                }
                ScheduledFuture<?> cutoff = ctx.executor().schedule(() -> {
                    ctx.close();
                }, RESPONSE_MILLIS, TimeUnit.MILLISECONDS);
                done.addListener(future -> cutoff.cancel(false));
            } else {
                done = ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }
            done.addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (seen != null) {
                seen.release();
                seen = null;
            }
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (counted) {
                counted = false;
                active.decrementAndGet();
            }
            if (!http) {
                ctx.fireChannelInactive();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (http) {
                // A client that hung up mid-download is ordinary, not an incident.
                ctx.close();
            } else {
                ctx.fireExceptionCaught(cause);
            }
        }
    }
}
