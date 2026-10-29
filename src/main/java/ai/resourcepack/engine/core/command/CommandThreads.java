package ai.resourcepack.engine.core.command;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.core.Regions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Which thread a command runs on, which only Folia makes a question.
 *
 * <p>Everywhere else every command is the main thread's and this is never
 * asked. On Folia a player's command runs on their region's thread and the
 * console's on the global region's, and the world may only be touched from the
 * thread that owns the part being touched. So before a subcommand runs it is
 * moved to the thread that owns what it is ABOUT, read off its words:
 *
 * <ul>
 *   <li>the server as a whole ({@link #SERVER_WIDE}): the global region;</li>
 *   <li>an online player named in it: that player's region;</li>
 *   <li>a place given as three numbers: the region holding that place;</li>
 *   <li>anything else: where it was typed, which for a player is their own
 *   region and is what nearly every subcommand without a target means.</li>
 * </ul>
 *
 * <p>Read off the words rather than declared per subcommand so that a new
 * subcommand is right on Folia without anybody remembering this class; the
 * cost is that a word which happens to be an online player's name moves a
 * command to them, which is never wrong, only sometimes unnecessary.
 */
final class CommandThreads {
    private CommandThreads() {
    }

    /**
     * Subcommands about the server rather than a place: content, packs, the
     * Studio link. They touch no one entity, and several fan out to every
     * player themselves.
     */
    private static final Set<String> SERVER_WIDE = Set.of(
            "reload", "sync", "distribute", "push", "edit");

    /**
     * Runs {@code work} on the thread that owns what the command is about:
     * at once if that is this thread (always, off Folia), else on its next
     * tick.
     */
    static void run(Plugin plugin, CommandSender sender, String sub, String[] args, Runnable work) {
        if (!Regions.folia()) {
            work.run();
            return;
        }
        if (SERVER_WIDE.contains(sub)) {
            Regions.global(plugin, work);
            return;
        }
        Player named = namedPlayer(args);
        if (named != null) {
            Tasks.now(plugin, named, work);
            return;
        }
        Location place = namedPlace(sender, args);
        if (place != null) {
            Tasks.nowAt(plugin, place, work);
            return;
        }
        if (sender instanceof Entity entity) {
            Tasks.now(plugin, entity, work);
        } else if (sender instanceof BlockCommandSender block) {
            Tasks.nowAt(plugin, block.getBlock().getLocation(), work);
        } else {
            Regions.global(plugin, work);
        }
    }

    /** The first word, after the subcommand, that is exactly an online player's name. */
    private static Player namedPlayer(String[] args) {
        for (int i = 1; i < args.length; i++) {
            Player player = Bukkit.getPlayerExact(args[i]);
            if (player != null) {
                return player;
            }
        }
        return null;
    }

    /**
     * The first three words in a row that are all plain numbers, as a place:
     * in the world named right after them if one is, else the sender's world,
     * else the first world. Relative coordinates ({@code ~}) are the sender's
     * own place, which is where the command already is.
     */
    private static Location namedPlace(CommandSender sender, String[] args) {
        for (int i = 1; i + 2 < args.length; i++) {
            Double x = number(args[i]);
            Double y = number(args[i + 1]);
            Double z = number(args[i + 2]);
            if (x == null || y == null || z == null) {
                continue;
            }
            World world = i + 3 < args.length ? Bukkit.getWorld(args[i + 3]) : null;
            if (world == null) {
                world = senderWorld(sender);
            }
            if (world == null && !Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().get(0);
            }
            return world == null ? null : new Location(world, x, y, z);
        }
        return null;
    }

    private static World senderWorld(CommandSender sender) {
        if (sender instanceof Entity entity) {
            return entity.getWorld();
        }
        if (sender instanceof BlockCommandSender block) {
            return block.getBlock().getWorld();
        }
        return null;
    }

    private static Double number(String word) {
        try {
            double value = Double.parseDouble(word);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
