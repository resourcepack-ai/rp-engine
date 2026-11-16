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
 * Via nobody can be on anything else.
 *
 * <p>That last number is Paper's {@code UnsafeValues#getProtocolVersion}, and
 * on Spigot, which has no API for it, the game's own
 * {@code SharedConstants.getProtocolVersion()}: from 26.1 the game ships
 * unobfuscated, so it is there by that name. Both are reached reflectively,
 * because neither is on every server this engine runs on. Before 0.2.85 only
 * Paper was asked, so on Spigot every player was read as a client that draws
 * nothing itself, and a 26.x player whose shaders drew a set ALSO got its
 * displays: a second suit, trailing the first.
 *
 * <p>-1 where neither answers (Spigot before 26.1, whose names are
 * obfuscated), which {@link Armor3dSet#drawnBy} reads as "draws nothing
 * itself" and so shows the displays. That is right there: no version before
 * 26.1 has a shader port.
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
        int paper = paperProtocol();
        return paper > 0 ? paper : gameProtocol();
    }

    /** Paper's and Folia's answer, or -1. */
    private static int paperProtocol() {
        try {
            Object unsafe = Bukkit.getUnsafe();
            Method method = unsafe.getClass().getMethod("getProtocolVersion");
            return positive(method.invoke(unsafe));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /** The game's own constant, by its unobfuscated name, or -1. */
    private static int gameProtocol() {
        try {
            Class<?> constants = Class.forName("net.minecraft.SharedConstants");
            return positive(constants.getMethod("getProtocolVersion").invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return -1;
        }
    }

    private static int positive(Object result) {
        return result instanceof Integer && (Integer) result > 0 ? (Integer) result : -1;
    }
}
