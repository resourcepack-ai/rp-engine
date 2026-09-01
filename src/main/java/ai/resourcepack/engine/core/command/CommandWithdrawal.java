package ai.resourcepack.engine.core.command;

import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Taking a command back out of the server after Bukkit has registered it.
 *
 * <p>Its own class because it is the only reflection in the plugin, and an
 * entrypoint is the worst place for the one piece of code that reaches into
 * CraftBukkit's internals: it is the part most likely to need changing when a
 * server fork moves something, and it should be findable without reading a
 * thousand lines of wiring first.
 *
 * <p>Bukkit registers everything in {@code plugin.yml} before a plugin can say
 * it would rather not have one, and an unregistered command still answers —
 * with its usage line, which reads as a broken plugin rather than as a command
 * this server does not have.
 *
 * <p>It fails soft, and that is the design rather than a concession. The worst
 * case is a server that turned a command off still having one that refuses
 * politely, which is a great deal better than one that will not start because
 * a field was renamed.
 */
public final class CommandWithdrawal {

    private CommandWithdrawal() {
    }

    /**
     * Takes a command back out of the server, name and aliases.
     *
     * <p>Bukkit registers everything in plugin.yml before a plugin can say it
     * would rather not have one, and an unregistered command still answers —
     * with its usage line, which reads as a broken plugin rather than a
     * command this server does not have. So the entry is removed from the
     * command map itself.
     *
     * <p>By reflection, because the map is CraftBukkit's and the API exposes
     * no way to withdraw a command. It fails soft: the worst case is a server
     * that turned this off still having a /emote that refuses politely, which
     * is a great deal better than one that will not start.
     */
    public static void withdraw(Plugin plugin, PluginCommand command) {
        try {
            PluginManager manager = plugin.getServer().getPluginManager();
            Object map = declared(manager, "commandMap").get(manager);
            Map<?, ?> known = (Map<?, ?>) declared(map, "knownCommands").get(map);

            List<String> names = new ArrayList<>(command.getAliases());
            names.add(command.getName());
            for (String name : names) {
                known.remove(name);
                known.remove(plugin.getName().toLowerCase(Locale.ROOT) + ":" + name);
            }
            command.unregister((org.bukkit.command.CommandMap) map);

            // Paper builds a Brigadier tree from the command map at startup,
            // and a client is told about commands from that rather than from
            // the map. Not API, so a server that does not have it simply keeps
            // offering a completion for a command that is no longer there.
            try {
                Server server = plugin.getServer();
                server.getClass().getMethod("syncCommands").invoke(server);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                plugin.getLogger().fine("No syncCommands on this server; completions may lag.");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Could not withdraw /" + command.getName()
                    + "; it will answer with its usage line instead: " + e.getMessage());
            command.setExecutor((sender, cmd, label, args) -> {
                sender.sendMessage(EngineCommand.prefix()
                        + "This server has turned that command off. Use /rp emote.");
                return true;
            });
        }
    }

    /**
     * A field on a class or any of its parents, made accessible.
     *
     * <p>Up the hierarchy because the field wanted is declared on
     * {@code SimpleCommandMap} while the object is a server-specific subclass
     * of it, which is exactly the sort of thing that differs between Paper and
     * Spigot and between versions of each.
     */
    private static Field declared(Object of, String name) throws NoSuchFieldException {
        for (Class<?> type = of.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException keepLooking) {
                // The next class up may have it.
            }
        }
        throw new NoSuchFieldException(name + " on " + of.getClass().getName());
    }
}
