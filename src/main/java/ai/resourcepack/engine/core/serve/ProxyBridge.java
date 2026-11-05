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
 * the two have to agree byte for byte.
 */
public final class ProxyBridge implements PluginMessageListener {

    public static final String CHANNEL = "rpengine:proxy";

    private static final int VERSION = 2;
    private static final byte STACK = 1;
    private static final byte NEED = 2;
    private static final byte CHUNK = 3;
    private static final byte STATUS = 4;
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
        byte[] message = write(out -> {
            out.writeByte(STACK);
            out.writeUTF(prompt == null ? "" : prompt);
            out.writeBoolean(force);
            out.writeInt(packs.size());
            for (BuiltPack pack : packs) {
                if (pack.file() != null) {
                    files.put(pack.sha1(), pack.file());
                }
                out.writeLong(pack.uuid().getMostSignificantBits());
                out.writeLong(pack.uuid().getLeastSignificantBits());
                out.writeUTF(pack.sha1());
                out.writeUTF(identity.apply(pack.sha1()));
                String link = url.apply(pack);
                out.writeUTF(link == null ? "" : link);
                out.writeLong(pack.size());
            }
        });
        player.sendPluginMessage(plugin, CHANNEL, message);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message.length < 2 || message[0] != VERSION) {
            return;
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(message, 2, message.length - 2));
        try {
            switch (message[1]) {
                case NEED -> sendPack(player, in.readUTF());
                case STATUS -> {
                    UUID pack = new UUID(in.readLong(), in.readLong());
                    String status = in.readUTF();
                    if (status.equals("SUCCESSFULLY_LOADED")) {
                        loads.confirmed(player.getUniqueId(), pack);
                    } else if (status.equals("DECLINED")) {
                        loads.declined(player.getUniqueId(), pack);
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
                int i = index;
                player.sendPluginMessage(plugin, CHANNEL, write(out -> {
                    out.writeByte(CHUNK);
                    out.writeUTF(sha1);
                    out.writeInt(i);
                    out.writeInt(total);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }));
            }
        }
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
