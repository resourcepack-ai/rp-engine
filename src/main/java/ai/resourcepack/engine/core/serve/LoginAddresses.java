package ai.resourcepack.engine.core.serve;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The address each player typed to reach this server, {@code host:port}.
 *
 * <p>For a link to the game port: the one address certain to reach this
 * server from a player's computer is the one that player just used, and the
 * game sends it in its handshake. Paper hands it over as
 * {@code Player#getVirtualHost}; Spigot only in {@link PlayerLoginEvent},
 * which is why that event is listened to only where the method is missing
 * (Paper warns about plugins that still listen to it).
 */
public final class LoginAddresses implements Listener, Function<UUID, String> {

    private static final Method VIRTUAL_HOST = virtualHostMethod();

    private final Map<UUID, String> recorded = new ConcurrentHashMap<>();
    private final Function<UUID, Player> online;
    private final int serverPort;

    /**
     * @param online     a UUID to the player online with it, or null
     * @param serverPort the game port, for a handshake that named none
     */
    public LoginAddresses(Function<UUID, Player> online, int serverPort) {
        this.online = online;
        this.serverPort = serverPort;
    }

    /** Whether {@link PlayerLoginEvent} has to be listened to, because the server has no getVirtualHost. */
    public static boolean needsLoginEvent() {
        return VIRTUAL_HOST == null;
    }

    @Override
    public String apply(UUID player) {
        String known = recorded.get(player);
        if (known != null || VIRTUAL_HOST == null) {
            return known;
        }
        Player online = this.online.apply(player);
        if (online == null) {
            return null;
        }
        try {
            Object value = VIRTUAL_HOST.invoke(online);
            if (value instanceof InetSocketAddress address) {
                return normalize(address.getHostString() + ":" + address.getPort(), serverPort);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Then nothing is known, and the caller says so.
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        String address = normalize(event.getHostname(), serverPort);
        if (address != null) {
            recorded.put(event.getPlayer().getUniqueId(), address);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        recorded.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Turns what the client sent into {@code host:port}, or null when there is
     * nothing usable. A handshake's host can carry a Forge marker after a NUL,
     * and a domain resolved through an SRV record a trailing dot; an IPv6
     * address needs brackets to be put in a URL.
     */
    static String normalize(String sent, int fallbackPort) {
        if (sent == null) {
            return null;
        }
        String value = sent;
        int nul = value.indexOf('\0');
        if (nul >= 0) {
            value = value.substring(0, nul);
        }
        value = value.trim();
        if (value.isEmpty()) {
            return null;
        }
        String host;
        int port = fallbackPort;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = value.substring(1, close);
            if (value.length() > close + 2 && value.charAt(close + 1) == ':') {
                port = parsePort(value.substring(close + 2), fallbackPort);
            }
        } else if (value.indexOf(':') != value.lastIndexOf(':')) {
            // Bare IPv6, no port.
            host = value;
        } else {
            int colon = value.lastIndexOf(':');
            host = colon < 0 ? value : value.substring(0, colon);
            if (colon >= 0) {
                port = parsePort(value.substring(colon + 1), fallbackPort);
            }
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            return null;
        }
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    private static int parsePort(String text, int fallback) {
        try {
            int port = Integer.parseInt(text.trim());
            return port > 0 && port < 65536 ? port : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Method virtualHostMethod() {
        try {
            return Player.class.getMethod("getVirtualHost");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
