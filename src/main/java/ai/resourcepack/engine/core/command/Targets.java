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
     * Whether a sender may point a command at this player.
     *
     * <p><b>Acting on yourself and acting on somebody else are two
     * permissions</b>, {@code rpengine.<sub>} and {@code rpengine.<sub>.others}
     * (see {@link EngineCommand#othersPermissionFor}). A server that lets its
     * players open a dialog, hear a sound or try on a set of armour has not
     * thereby agreed to let each of them push a screen onto everybody else's —
     * which is what the single node used to mean, because a player could always
     * name another.
     *
     * <p>Only a PLAYER is ever refused: the console and a command block have no
     * self to act on and are the server's own hands, and Studio relays these
     * commands through the console. A command run with {@code execute as} is
     * judged by the player who ran it.
     */
    static boolean permitted(CommandSender sender, String sub, Player target) {
        Player acting = actingPlayer(sender);
        if (acting == null || target == null || acting.getUniqueId().equals(target.getUniqueId())) {
            return true;
        }
        return sender.hasPermission(EngineCommand.othersPermissionFor(sub));
    }

    /** Whether a sender may point this command at anybody but themselves — what decides if names are offered. */
    static boolean mayTargetOthers(CommandSender sender, String sub) {
        return actingPlayer(sender) == null || sender.hasPermission(EngineCommand.othersPermissionFor(sub));
    }

    /** The refusal, naming the node a server owner has to grant. */
    static void refuse(CommandSender sender, String sub, String doing) {
        Reply.to(sender, "You need " + EngineCommand.othersPermissionFor(sub) + " to " + doing + " somebody else.");
    }

    /**
     * The names a {@code [player]} argument completes to: everybody online for
     * a sender who may target others, and only their own name otherwise —
     * completing a name the command would then refuse is worse than not.
     */
    static java.util.List<String> names(CommandSender sender, String sub) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (mayTargetOthers(sender, sub)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                out.add(player.getName());
            }
        } else if (sender instanceof Player self) {
            out.add(self.getName());
        }
        return out;
    }

    /** The player a sender is acting as, or null for the console and a command block. */
    private static Player actingPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied && proxied.getCaller() instanceof Player caller) {
            return caller;
        }
        return null;
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
