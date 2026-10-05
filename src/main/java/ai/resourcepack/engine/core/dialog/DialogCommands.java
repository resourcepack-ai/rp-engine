package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogInfo;
import ai.resourcepack.engine.core.command.EngineCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * The commands dialogs are opened with — {@code /shop} for a dialog that says
 * {@code command: shop}. Internal.
 *
 * <h2>Why a dialog has its own command</h2>
 *
 * {@code /rp dialog} is a staff command: it takes any dialog, any values and any
 * player. A server's MENU is the opposite — one screen, opened by the player
 * looking at it, by a word they can remember. Without this the only ways to
 * give players a menu were an item, another plugin's alias, or granting every
 * player {@code /rp dialog} itself. So a dialog may name a command, and that
 * command opens that dialog on whoever types it and on nobody else.
 *
 * <h2>What it takes</h2>
 *
 * One bare word after the command is {@code target}: {@code /punish Steve}
 * opens a punish menu about Steve, which is what {@code {target}} is for. Any
 * {@code name=value} is accepted only from somebody who may run
 * {@code /rp dialog} anyway, because a value fills in what the dialog's words
 * and commands say.
 *
 * <h2>How it is registered</h2>
 *
 * Into the server's command map at run time, because the set of dialogs is
 * content — authored, pushed from Studio, reloaded — and plugin.yml is not.
 * Bukkit has no API for that, so the map is reached the way
 * {@code CommandWithdrawal} reaches it, and it fails the same way: softly, with
 * a log line, leaving {@code /rp dialog} as the way in. A name the server
 * already has — another plugin's command OR alias — is never taken from it:
 * the dialog goes without, the log says so once, and {@code /rp dialogs} shows
 * it. (Bukkit's own {@code register} refuses a command but replaces an alias,
 * which is why the map is asked first.)
 */
public final class DialogCommands {

    private final Plugin plugin;
    private final DialogsImpl dialogs;
    /** What is registered now, by the name a player types. */
    private final Map<String, OpenCommand> registered = new LinkedHashMap<>();
    /** Names that were taken by somebody else when we asked, so the log says it once. */
    private final java.util.Set<String> refused = new java.util.HashSet<>();

    public DialogCommands(Plugin plugin, DialogsImpl dialogs) {
        this.plugin = plugin;
        this.dialogs = dialogs;
    }

    /**
     * Makes the registered commands the catalogue's: one per dialog that names
     * one, the first dialog to name a command keeping it. On the main thread,
     * because the command map is not safe anywhere else; a call from another
     * thread is moved there.
     */
    public void sync(Collection<DialogInfo> catalogue) {
        if (!Bukkit.isPrimaryThread()) {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> sync(catalogue));
            }
            return;
        }
        Map<String, DialogInfo> wanted = new LinkedHashMap<>();
        for (DialogInfo info : catalogue) {
            info.command().ifPresent(name -> wanted.putIfAbsent(name, info));
        }
        // A name no dialog asks for any more is not "taken" any more either.
        refused.retainAll(wanted.keySet());
        if (wanted.isEmpty() && registered.isEmpty()) {
            return;
        }
        CommandMap map = commandMap();
        if (map == null) {
            return;
        }
        boolean changed = false;
        for (String name : new ArrayList<>(registered.keySet())) {
            DialogInfo want = wanted.get(name);
            OpenCommand have = registered.get(name);
            if (want != null && want.id().equals(have.id) && same(want.permission(), have.dialogPermission)) {
                wanted.remove(name);
                continue;
            }
            unregister(map, have);
            registered.remove(name);
            changed = true;
        }
        for (Map.Entry<String, DialogInfo> entry : wanted.entrySet()) {
            String name = entry.getKey();
            // Asked first rather than left to register(), which refuses another
            // plugin's command but quietly REPLACES another plugin's alias.
            if (map.getCommand(name) != null) {
                if (refused.add(name)) {
                    plugin.getLogger().warning("Dialog " + entry.getValue().id() + " asks for /" + name
                            + ", which this server already has; give the dialog another command. It still opens with /rp dialog.");
                }
                continue;
            }
            refused.remove(name);
            OpenCommand command = new OpenCommand(name, entry.getValue());
            map.register(name, plugin.getName().toLowerCase(Locale.ROOT), command);
            registered.put(name, command);
            changed = true;
        }
        if (changed) {
            resend();
        }
    }

    /** Takes every one back out — the plugin is going. */
    public void clear() {
        CommandMap map = registered.isEmpty() ? null : commandMap();
        if (map != null) {
            registered.values().forEach(command -> unregister(map, command));
        }
        registered.clear();
    }

    /** The command a dialog is opened with, {@code /shop} — empty when it has none, or its name was taken. */
    public Optional<String> label(ContentId id) {
        for (OpenCommand command : registered.values()) {
            if (command.id.equals(id)) {
                return Optional.of("/" + command.getName());
            }
        }
        return Optional.empty();
    }

    /** Whether a dialog's command was refused because the server already has one by that name. */
    public boolean taken(String name) {
        return name != null && refused.contains(name);
    }

    private static boolean same(Optional<String> a, String b) {
        return a.orElse(null) == null ? b == null : a.get().equals(b);
    }

    /** The server's command map, or null (and one log line) where it cannot be reached. */
    private CommandMap commandMap() {
        try {
            Object server = Bukkit.getServer();
            return (CommandMap) server.getClass().getMethod("getCommandMap").invoke(server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not reach the command map; dialogs open with /rp dialog only.", e);
            return null;
        }
    }

    /** Out of the map, name and prefixed name both — only where the entry is ours. */
    private void unregister(CommandMap map, OpenCommand command) {
        try {
            Field field = null;
            for (Class<?> type = map.getClass(); type != null && field == null; type = type.getSuperclass()) {
                try {
                    field = type.getDeclaredField("knownCommands");
                } catch (NoSuchFieldException keepLooking) {
                    // The next class up may have it.
                }
            }
            if (field != null) {
                field.setAccessible(true);
                Map<?, ?> known = (Map<?, ?>) field.get(map);
                String prefixed = plugin.getName().toLowerCase(Locale.ROOT) + ":" + command.getName();
                if (known.get(command.getName()) == command) {
                    known.remove(command.getName());
                }
                if (known.get(prefixed) == command) {
                    known.remove(prefixed);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().fine("Could not take /" + command.getName() + " out of the command map: " + e.getMessage());
        }
        command.unregister(map);
    }

    /**
     * Tells the server, and every player, that the commands changed — or a
     * client keeps completing a command that is gone and refuses to complete
     * one that is new. {@code syncCommands} is CraftBukkit's, not API.
     */
    private void resend() {
        try {
            Object server = Bukkit.getServer();
            server.getClass().getMethod("syncCommands").invoke(server);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            plugin.getLogger().fine("No syncCommands on this server; dialog command completions may lag.");
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    /** {@code /shop}: the dialog that named it, on whoever typed it. */
    private final class OpenCommand extends Command {

        private final ContentId id;
        private final String dialogPermission;

        OpenCommand(String name, DialogInfo info) {
            super(name);
            this.id = info.id();
            this.dialogPermission = info.permission().orElse(null);
            setDescription("Opens the " + info.name() + " dialog.");
            setUsage("/" + name + " [player]");
            if (dialogPermission != null) {
                setPermission(dialogPermission);
            }
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                EngineCommand.say(sender, "/" + label + " opens a dialog on whoever types it. "
                        + "From here, /rp dialog " + id + " <player> opens it on somebody.");
                return true;
            }
            if (dialogPermission != null && !player.hasPermission(dialogPermission)) {
                EngineCommand.say(player, "You do not have permission to open that.");
                return true;
            }
            Map<String, String> values = new LinkedHashMap<>();
            boolean staff = player.hasPermission("rpengine.dialog");
            for (String arg : args) {
                int eq = arg.indexOf('=');
                if (eq > 0 && staff) {
                    values.put(arg.substring(0, eq), arg.substring(eq + 1));
                } else if (eq < 0 && !values.containsKey("target")) {
                    values.put("target", arg);
                }
            }
            if (!dialogs.supported()) {
                EngineCommand.say(player, "Dialogs need Minecraft 1.21.6 or newer on the server.");
                return true;
            }
            if (!dialogs.canShow(player, id)) {
                EngineCommand.say(player, "That dialog is drawn in a resource pack you are not holding.");
                return true;
            }
            if (!dialogs.show(player, id, values)) {
                EngineCommand.say(player, "The game would not open that dialog. The console says why.");
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length != 1) {
                return List.of();
            }
            String typed = args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    names.add(online.getName());
                }
            }
            return names;
        }
    }
}
