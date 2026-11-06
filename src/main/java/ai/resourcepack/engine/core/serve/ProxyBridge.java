package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * {@code proxy.enabled}: the packs go to a player through RPE Proxy, the
 * Velocity plugin, which sends each pack once per network join rather than
 * once per server, and by default serves it from the proxy's own port.
 *
 * <p>Everything crosses on the {@code rpengine:proxy} plugin channel through
 * the player's own connection, so there is nothing to configure between the
 * two: this server says which packs the player should hold, the proxy asks for
 * the bytes of any it has not seen, and passes back what the player's game
 * said. A player's connection is known to run through RPE Proxy when the
 * proxy has added the channel to the ones their game listens on, which it does
 * for every player it carries; a player without it is sent packs by this
 * server, as without a proxy.
 *
 * <p>The messages are RPE Proxy's {@code Wire} class, written out again here:
 * the two have to agree byte for byte, and {@code ProxyWireTest} holds both
 * to the same recorded messages.
 */
public final class ProxyBridge implements PluginMessageListener {

    public static final String CHANNEL = "rpengine:proxy";

    static final int VERSION = 2;
    static final byte STACK = 1;
    static final byte NEED = 2;
    static final byte CHUNK = 3;
    static final byte STATUS = 4;
    private static final int CHUNK_SIZE = 256 * 1024;

    private final Plugin plugin;
    private final PackLoads loads;
    private final Logger logger;
    /** SHA-1 -> the file, for every pack this server has offered the proxy. */
    private final Map<String, Path> files = new ConcurrentHashMap<>();
    /** A served file's SHA-1 -> the SHA-1 of what was built, before PackSquash. See {@link #identity}. */
    private volatile Function<String, String> identity = Function.identity();

    public ProxyBridge(Plugin plugin, PackLoads loads, Logger logger) {
        this.plugin = plugin;
        this.loads = loads;
        this.logger = logger;
    }

    /**
     * What a served pack IS, for the proxy to compare across servers: the
     * SHA-1 of the build before PackSquash rewrote it. PackSquash's output
     * differs run to run, so two servers squashing the same content serve
     * different files, and without this a server switch would send the pack
     * again.
     */
    public void identity(Function<String, String> sourceOf) {
        this.identity = sourceOf == null ? Function.identity() : sourceOf;
    }

    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    /** Whether {@code player}'s connection runs through RPE Proxy. */
    public boolean carries(Player player) {
        return player != null && player.getListeningPluginChannels().contains(CHANNEL);
    }

    /**
     * Tells the proxy the packs {@code player} should hold, bottom first.
     *
     * @param url where each would have been sent from here, or empty: the
     *            proxy uses it only when it relays rather than serves
     */
    public void sendStack(Player player, List<BuiltPack> packs, Function<BuiltPack, String> url,
                          String prompt, boolean force) {
        if (files.size() > 256) {
            files.clear();
        }
        List<Entry> entries = new ArrayList<>(packs.size());
        for (BuiltPack pack : packs) {
            if (pack.file() != null) {
                files.put(pack.sha1(), pack.file());
            }
            entries.add(new Entry(pack.uuid(), pack.sha1(), identity.apply(pack.sha1()), url.apply(pack), pack.size()));
        }
        byte[] message = stackMessage(prompt, force, entries);
        player.sendPluginMessage(plugin, CHANNEL, message);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message.length < 2 || message[0] != VERSION) {
            return;
        }
        try {
            switch (message[1]) {
                case NEED -> sendPack(player, readNeed(message));
                case STATUS -> {
                    Status status = readStatus(message);
                    if (status.status().equals("SUCCESSFULLY_LOADED")) {
                        loads.confirmed(player.getUniqueId(), status.pack());
                    } else if (status.status().equals("DECLINED")) {
                        loads.declined(player.getUniqueId(), status.pack());
                    }
                }
                default -> {
                }
            }
        } catch (IOException e) {
            logger.warning("Unreadable message from the proxy: " + e.getMessage());
        }
    }

    /**
     * Sends the proxy a pack's bytes, in chunks, through {@code player}'s
     * connection. Only a pack this server offered: the request comes through
     * a connection, and it is not a way to read any file by naming its hash.
     */
    private void sendPack(Player player, String sha1) throws IOException {
        Path file = files.get(sha1);
        if (file == null || !Files.isRegularFile(file)) {
            logger.warning("The proxy asked for pack " + sha1.substring(0, Math.min(8, sha1.length()))
                    + ", which this server no longer has. It is offered again on the next send.");
            return;
        }
        long size = Files.size(file);
        int total = (int) Math.max(1, (size + CHUNK_SIZE - 1) / CHUNK_SIZE);
        try (InputStream in = Files.newInputStream(file)) {
            for (int index = 0; index < total; index++) {
                byte[] bytes = in.readNBytes(CHUNK_SIZE);
                player.sendPluginMessage(plugin, CHANNEL, chunkMessage(sha1, index, total, bytes));
            }
        }
    }

    // ---- the messages ------------------------------------------------------
    //
    // Static, so a test can hold them to the bytes RPE Proxy writes and reads:
    // src/test/resources/proxy-wire/messages.txt is the same file in both
    // projects, and each one's test encodes and decodes every message in it.

    /** One pack in a stack: what the proxy is told about it. */
    record Entry(UUID id, String sha1, String source, String url, long size) {
    }

    /** What the player's game said about a pack, as the proxy passes it back. */
    record Status(UUID pack, String status) {
    }

    static byte[] stackMessage(String prompt, boolean force, List<Entry> packs) {
        return write(out -> {
            out.writeByte(STACK);
            out.writeUTF(prompt == null ? "" : prompt);
            out.writeBoolean(force);
            out.writeInt(packs.size());
            for (Entry pack : packs) {
                out.writeLong(pack.id().getMostSignificantBits());
                out.writeLong(pack.id().getLeastSignificantBits());
                out.writeUTF(pack.sha1());
                out.writeUTF(pack.source());
                out.writeUTF(pack.url() == null ? "" : pack.url());
                out.writeLong(pack.size());
            }
        });
    }

    static byte[] chunkMessage(String sha1, int index, int total, byte[] bytes) {
        return write(out -> {
            out.writeByte(CHUNK);
            out.writeUTF(sha1);
            out.writeInt(index);
            out.writeInt(total);
            out.writeInt(bytes.length);
            out.write(bytes);
        });
    }

    static String readNeed(byte[] message) throws IOException {
        return body(message).readUTF();
    }

    static Status readStatus(byte[] message) throws IOException {
        DataInputStream in = body(message);
        return new Status(new UUID(in.readLong(), in.readLong()), in.readUTF());
    }

    private static DataInputStream body(byte[] message) {
        // Past the version and the type.
        return new DataInputStream(new ByteArrayInputStream(message, 2, message.length - 2));
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
