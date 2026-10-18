package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.common.quick.JoinCopies;
import ai.resourcepack.common.quick.QuickActionPack;
import ai.resourcepack.common.quick.StandIn;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Dialogs on the game's Quick Actions key (G, Minecraft 1.21.6 and newer):
 * {@code quick-action:} in a dialog's definition, or Studio's switch for it.
 *
 * <p>The key is the CLIENT's. It opens a dialog out of the registry the player
 * was sent as they joined, and the server never hears of the press, so every
 * dialog on it is a file in a datapack ({@link QuickActionPack}, read when the
 * server starts: a change needs a restart). That file is a LAUNCHER, one
 * button running {@code /rp quick <id>}, which opens the dialog drawn fresh.
 *
 * <p>A dialog on the key {@code direct} (the default) is the dialog itself for
 * each player instead: drawn for them as they join, from the pre-login event,
 * and swapped into the registry packet they are sent ({@link JoinCopies}).
 * What it shows is what was true then, until they join again. Paper only;
 * everywhere else, and for a dialog with a permission (unknown while they are
 * still joining) or with the player's own items in it (the same), the launcher
 * stays.
 *
 * <p>The server is not told when the key opens a copy, so it cannot know a
 * click came from one: every check of a click is against "the dialog the
 * player was shown". {@link #adopt} closes that gap. A command one of the
 * player's own copies runs, which the dialog they were last sent does not,
 * makes that copy the dialog they were shown, before anything checks it.
 */
public final class QuickDialogs implements Listener {

    private static final String PACK = "rpengine_quick";
    private static final String NAMESPACE = "rpengine";
    /** A placeholder in a label: the file is everybody's, so it is left out rather than shown in braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]*}");

    private final DialogsImpl dialogs;
    private final Plugin plugin;
    /** Registry id to the dialog there, in the order the key lists them. */
    private volatile Map<String, DialogInfo> onKey = Map.of();
    private JoinCopies copies;
    private boolean installTried;
    /** Each player's own copies, as sent: what {@link #adopt} reads their clicks against. */
    private final Map<UUID, Map<ContentId, String>> drawn = new ConcurrentHashMap<>();

    QuickDialogs(DialogsImpl dialogs, Plugin plugin) {
        this.dialogs = dialogs;
        this.plugin = plugin;
    }

    /** After every content load: the datapack, and what changed. */
    void update(Collection<DialogInfo> loaded) {
        Map<String, DialogInfo> next = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();
        List<DialogInfo> sorted = new java.util.ArrayList<>(loaded);
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        for (DialogInfo info : sorted) {
            if (info.quickAction().isEmpty()) {
                continue;
            }
            String id = NAMESPACE + ":quick/" + info.id().namespace() + "/" + info.id().path();
            String launcher = launcher(info).toString();
            String refused = DialogPackets.refusal(launcher);
            if (refused != null) {
                plugin.getLogger().warning(info.id() + " is left off the Quick Actions key: the game will not read its launcher ("
                        + refused + ")");
                continue;
            }
            next.put(id, info);
            files.put(id, launcher);
        }
        onKey = Map.copyOf(next);

        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty()) {
            try {
                if (QuickActionPack.write(worlds.get(0), PACK, "RP Engine quick actions. Generated; edits are overwritten.", files)) {
                    plugin.getLogger().info(files.isEmpty()
                            ? "Nothing is on the Quick Actions key any more. That takes effect when the server next restarts."
                            : "The Quick Actions key (G) now opens " + String.join(", ", next.values().stream()
                            .map(d -> d.id().toString()).toList()) + ". That takes effect when the server next restarts.");
                }
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not write the quick actions datapack", e);
            }
        }

        if (!installTried && next.values().stream().anyMatch(QuickDialogs::directable)) {
            installTried = true;
            copies = JoinCopies.install(plugin);
            if (copies == null) {
                plugin.getLogger().info("This server cannot give each player their own copy of a quick action"
                        + " (that needs Paper), so the Quick Actions key opens a launcher for every dialog on it.");
            }
        }
    }

    /** Last, and only for a login going ahead: the player's own copies, drawn on the main thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || copies == null
                || onKey.values().stream().noneMatch(QuickDialogs::directable)) {
            return;
        }
        UUID player = event.getUniqueId();
        String name = event.getName();
        Map<String, String> json;
        try {
            json = Bukkit.getScheduler().callSyncMethod(plugin, () -> draw(player, name)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().fine("Could not draw " + name + "'s quick actions in time: " + e);
            return;
        }
        copies.put(player, json).forEach((id, why) -> {
            plugin.getLogger().warning("The Quick Actions key opens the launcher for " + onKey.get(id).id()
                    + ": the game will not read it as drawn (" + why + ")");
            Map<ContentId, String> mine = drawn.get(player);
            if (mine != null) {
                mine.remove(onKey.get(id).id());
            }
        });
        // A login refused after this point never reaches quit, so its copies go here.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPlayer(player) == null) {
                forget(player);
            }
        }, 20L * 60);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    private Map<String, String> draw(UUID player, String name) {
        Player standIn = StandIn.of(player, name);
        Map<String, String> out = new LinkedHashMap<>();
        Map<ContentId, String> mine = new ConcurrentHashMap<>();
        onKey.forEach((id, info) -> {
            if (!directable(info)) {
                return;
            }
            try {
                String json = dialogs.drawJoining(standIn, info);
                if (json == null) {
                    return;
                }
                JsonObject dialog = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                // The key's list names it: by its label, else by its title as drawn for them.
                if (info.quickAction().get().label() != null) {
                    dialog.add("external_title", label(info));
                } else if (!dialog.has("external_title") && dialog.has("title")) {
                    dialog.add("external_title", dialog.get("title").deepCopy());
                }
                String sent = dialog.toString();
                out.put(id, sent);
                mine.put(info.id(), sent);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not draw " + info.id() + " for the Quick Actions key, so it opens the launcher: " + e);
            }
        });
        drawn.put(player, mine);
        return out;
    }

    /**
     * A command the player sent: when one of their own copies runs it and the
     * dialog they were last sent does not, the copy is what they were shown.
     * Called before anything else reads the command.
     */
    void adopt(Player viewer, String command) {
        Map<ContentId, String> mine = viewer == null || command == null ? null : drawn.get(viewer.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        Optional<DialogsImpl.Shown> shown = dialogs.lastShown(viewer);
        if (shown.isPresent()) {
            Optional<String> current = dialogs.visibleJson(viewer, shown.get().id(), shown.get().values());
            if (current.isPresent() && DialogLinks.runsCommand(current.get(), command)) {
                return;
            }
        }
        for (Map.Entry<ContentId, String> copy : mine.entrySet()) {
            if (DialogLinks.runsCommand(copy.getValue(), command) && dialogs.info(viewer, copy.getKey()).isPresent()) {
                dialogs.remember(viewer, new DialogsImpl.Shown(copy.getKey(), Map.of()), false);
                return;
            }
        }
    }

    /** {@code /rp quick <id>}, what a launcher's button runs: opens it if it is on the key. */
    boolean open(Player player, ContentId id) {
        boolean onIt = onKey.values().stream().anyMatch(d -> d.id().equals(id));
        return onIt && dialogs.show(player, id);
    }

    void forget(UUID player) {
        drawn.remove(player);
        if (copies != null) {
            copies.forget(player);
        }
    }

    void close() {
        if (copies != null) {
            copies.close();
        }
        copies = null;
        drawn.clear();
    }

    /** Whether a player can be given their own copy as they join: not behind a permission, and nothing of theirs in it. */
    private static boolean directable(DialogInfo info) {
        return !info.quickAction().get().launcher() && info.permission().isEmpty() && !DialogItems.perPlayer(info.json());
    }

    /** The file: the dialog's name, and one button that asks for it. */
    private static JsonObject launcher(DialogInfo info) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "minecraft:run_command");
        action.addProperty("command", "rp quick " + info.id());
        JsonObject button = new JsonObject();
        button.add("label", new JsonPrimitive("Open"));
        button.add("action", action);
        JsonObject dialog = new JsonObject();
        dialog.addProperty("type", "minecraft:notice");
        JsonPrimitive label = label(info);
        dialog.add("title", label);
        dialog.add("external_title", label);
        dialog.add("action", button);
        // The dialog it opens replaces this screen; closing first would flash the world.
        dialog.addProperty("after_action", "none");
        dialog.addProperty("pause", false);
        return dialog;
    }

    /** What the key's list calls it: the label, else the dialog's name, with any placeholder left out. */
    private static JsonPrimitive label(DialogInfo info) {
        String raw = info.quickAction().map(DialogInfo.QuickAction::label).orElse(info.name());
        String plain = PLACEHOLDER.matcher(raw == null ? "" : raw).replaceAll("").replaceAll("\\s{2,}", " ").trim();
        return new JsonPrimitive(plain.isEmpty() ? info.id().toString() : plain);
    }
}
