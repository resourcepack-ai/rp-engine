package ai.resourcepack.engine.core.armor3d;

import ai.resourcepack.engine.core.distribution.ProtocolResolver;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.function.ToIntFunction;

/**
 * Which protocol a player's CLIENT speaks.
 *
 * <p>ViaVersion's answer where it is installed — it is the only thing that
 * knows, because a translated connection looks like the server's own version
 * to everything else — and the server's own protocol otherwise, since without
 * Via nobody can be on anything else. That last number comes from
 * {@code UnsafeValues#getProtocolVersion}, reached reflectively because it is
 * not on every server this engine runs on; -1 where even that is missing,
 * which {@link Armor3dSet#drawnBy} reads as "draws nothing itself" and so
 * shows the displays — the safe way to be wrong.
 */
public final class ClientProtocols {

    private ClientProtocols() {
    }

    public static ToIntFunction<Player> forServer(ProtocolResolver via) {
        int server = serverProtocol();
        return player -> {
            int theirs = via.protocolOf(player);
            return theirs > 0 ? theirs : server;
        };
    }

    private static int serverProtocol() {
        try {
            Object unsafe = Bukkit.getUnsafe();
            Method method = unsafe.getClass().getMethod("getProtocolVersion");
            Object result = method.invoke(unsafe);
            return result instanceof Integer ? (Integer) result : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return -1;
        }
    }
}
