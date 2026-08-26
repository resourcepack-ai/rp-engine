package ai.resourcepack.engine.core.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Who a command should act on.
 *
 * <p>Three commands here draw something on one client — a screen, an overlay,
 * a sound — and each has two callers who address that client differently. A
 * player runs it on themselves and names nobody. Studio relays it through the
 * console, wrapped in {@code execute at <player>}, and names {@code @p}.
 *
 * <p>The second one is why a selector is resolved rather than looked up:
 * {@code Bukkit.getPlayerExact("@p")} is simply null, and the console has no
 * position of its own for {@code @p} to mean anything — but the sender inside
 * an {@code execute at} does, which is exactly the position the relay put
 * there.
 */
final class Targets {

    private Targets() {
    }

    /**
     * The player a command names, or the one who ran it.
     *
     * @param token a name, a selector, or null for "whoever ran this"
     * @return the player, or null if nobody was named and nobody is running it
     */
    static Player of(CommandSender sender, String token) {
        if (token == null || token.isEmpty()) {
            return sender instanceof Player ? (Player) sender : null;
        }
        if (!token.startsWith("@")) {
            return Bukkit.getPlayerExact(token);
        }
        try {
            for (Entity found : Bukkit.selectEntities(sender, token)) {
                if (found instanceof Player) {
                    return (Player) found;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Not a selector this server understands; fall through to the
            // sender, which is what a person typing it meant anyway.
        }
        return sender instanceof Player ? (Player) sender : null;
    }
}
