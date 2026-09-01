package ai.resourcepack.engine.core.distribution;

import ai.resourcepack.engine.core.version.Compatibility;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Which Minecraft version a connecting player is really on.
 *
 * <p>The key fact this class is built around: <b>a plain Spigot or Paper
 * server only accepts clients of its own version</b>. Multi-version is not
 * something a server does by default, it is something ViaVersion does. So
 * there are exactly two cases, and the second is the common one:
 *
 * <ul>
 *   <li><b>ViaVersion present</b> — ask it, per player. It is the only thing
 *       that knows, because by the time the player object exists the protocol
 *       has already been translated to the server's own.</li>
 *   <li><b>ViaVersion absent</b> — everybody is on the server's version, so
 *       there is nothing to resolve.</li>
 * </ul>
 *
 * <p>Via is reached entirely by <b>reflection</b>, for the same reason
 * {@code SkinApplier} reaches Paper that way and {@code PresencePlugin} probes
 * for Geyser before constructing {@code GeyserBridge}: compiling against it
 * would stop this jar loading on the servers that do not have it, which is
 * most of them.
 *
 * <p><b>This class never turns a protocol number into a version name.</b> That
 * table lives in Studio's distribution protocol map and arrives in the
 * manifest, entry by entry. A copy here would be a second table in another
 * language, in a jar that updates whenever a server owner gets round to it —
 * so a new Minecraft release would need every user to upgrade rather than one
 * deploy of ours.
 */
public final class ProtocolResolver {

    /** Via's protocol number for a player, or -1 when Via isn't installed. */
    private final Method getPlayerVersion;
    private final Object viaApi;
    private final String serverVersion;

    public ProtocolResolver(Logger logger) {
        this.serverVersion = readServerVersion();

        Object api = null;
        Method method = null;
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
            try {
                Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                api = via.getMethod("getAPI").invoke(null);
                method = api.getClass().getMethod("getPlayerVersion", UUID.class);
                method.setAccessible(true);
            } catch (ReflectiveOperationException | LinkageError e) {
                // A Via too old or too new to have this shape is the same as
                // no Via: everybody is treated as the server's own version,
                // which is what a server without Via genuinely is. Logged
                // because it means cross-version players silently get the
                // wrong answer, and that is worth finding in a log.
                logger.warning("ViaVersion is installed but its API couldn't be read (" + e
                        + "). Cross-version players will be treated as " + this.serverVersion + ".");
                api = null;
                method = null;
            }
        }
        this.viaApi = api;
        this.getPlayerVersion = method;
    }

    public boolean hasVia() {
        return getPlayerVersion != null;
    }

    /** The server's own version string, e.g. {@code 1.21.8}. */
    public String serverVersion() {
        return serverVersion;
    }

    /**
     * The player's protocol number, or -1 when we have no better answer than
     * "the server's version", which the caller resolves by name instead.
     */
    public int protocolOf(Player player) {
        if (getPlayerVersion == null) {
            return -1;
        }
        try {
            Object result = getPlayerVersion.invoke(viaApi, player.getUniqueId());
            return result instanceof Integer ? (Integer) result : -1;
        } catch (ReflectiveOperationException | LinkageError e) {
            return -1;
        }
    }

    /**
     * Delegated so the version the engine gates features on and the version it
     * reports to studio's distribution manifest are one answer. They were the
     * same two lines in two files before the engine had features to gate.
     */
    private static String readServerVersion() {
        return Compatibility.readServerVersion();
    }
}
