package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.core.command.EngineCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells the console, and the staff online, when a core shader our packs ship
 * is also shipped by another plugin's pack. See {@link ShaderClashes} for why
 * that breaks one of them and how the other side is found.
 *
 * <p>Said once per clash, not on every build: a clash is reported when it
 * first appears and again only if it went away and came back. Staff are
 * players with {@code rpengine.shaders}, op by default, and they are told on
 * joining too, because a console line from startup is one nobody reads.
 */
public final class ShaderClashWatch {

    /** The permission whose holders are told in chat. */
    public static final String PERMISSION = "rpengine.shaders";

    /**
     * How long after startup the other plugins' folders are first read.
     * Most build their pack on enable or on the first tick, some a little
     * later off the main thread; the scan is a warning, so late is fine and
     * early would miss them.
     */
    public static final long FIRST_SCAN_DELAY_TICKS = 20L * 20;

    private final Plugin plugin;

    /** Other plugins, by name, and the shaders their packs on disk replace. Written off the main thread. */
    private volatile Map<String, Set<String>> theirs = Map.of();

    /** What a pack of ours replaces, by its SHA-1, so a pack is read once however often it is checked. */
    private final Map<String, Set<String>> oursBySha = new ConcurrentHashMap<>();

    private List<ShaderClashes.Clash> current = List.of();
    private Set<String> reported = Set.of();

    public ShaderClashWatch(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads every other enabled plugin's folder for packs, off the main
     * thread, then calls {@code then} back on it. The plugin list is taken
     * when the delay is up, on the main thread: at startup the plugins after
     * this one are not enabled yet, and Bukkit's list is not safe to read from
     * anywhere else.
     */
    public void scanOthers(long delayTicks, Runnable then) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> scanNow(then), Math.max(1L, delayTicks));
    }

    private void scanNow(Runnable then) {
        if (!plugin.isEnabled()) {
            return;
        }
        Path pluginsFolder = plugin.getDataFolder().toPath().getParent();
        Map<String, Path> folders = new TreeMap<>();
        for (Plugin other : plugin.getServer().getPluginManager().getPlugins()) {
            if (other != plugin && other.isEnabled()) {
                folders.put(other.getName(), other.getDataFolder().toPath());
            }
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Set<String>> found = new TreeMap<>();
            for (Map.Entry<String, Path> e : folders.entrySet()) {
                ShaderClashes.Found result = ShaderClashes.scan(e.getValue(), pluginsFolder);
                if (!result.shaders().isEmpty()) {
                    found.put(e.getKey() + " (" + result.where() + ")", result.shaders());
                }
            }
            theirs = Map.copyOf(found);
            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, then);
            }
        });
    }

    /**
     * Compares our packs with what the last scan found, and says what is new.
     * Main thread.
     *
     * @param ours each of our packs by a name a person can read; one name may
     *             cover several packs (every player's Studio push, say)
     */
    public void check(List<Map.Entry<String, BuiltPack>> ours) {
        Map<String, Set<String>> ourSide = new TreeMap<>();
        for (Map.Entry<String, BuiltPack> e : ours) {
            BuiltPack pack = e.getValue();
            if (pack == null) {
                continue;
            }
            Set<String> shaders = oursBySha.computeIfAbsent(pack.sha1(), sha -> ShaderClashes.inZip(pack.file()));
            if (!shaders.isEmpty()) {
                ourSide.merge(e.getKey(), shaders, (a, b) -> {
                    Set<String> both = new java.util.TreeSet<>(a);
                    both.addAll(b);
                    return both;
                });
            }
        }
        List<ShaderClashes.Clash> clashes = ShaderClashes.find(ourSide, theirs);
        Set<String> now = new HashSet<>();
        List<String> fresh = new ArrayList<>();
        for (ShaderClashes.Clash clash : clashes) {
            String line = clash.describe();
            now.add(line);
            if (!reported.contains(line)) {
                fresh.add(line);
            }
        }
        current = List.copyOf(clashes);
        reported = now;
        for (String line : fresh) {
            plugin.getLogger().warning(line);
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission(PERMISSION)) {
                    EngineCommand.say(player, line);
                }
            }
        }
    }

    /** Tells somebody who has just joined about every clash there is, if they are staff. */
    public void onJoin(Player player) {
        if (current.isEmpty() || !player.hasPermission(PERMISSION)) {
            return;
        }
        for (ShaderClashes.Clash clash : current) {
            EngineCommand.say(player, clash.describe());
        }
    }
}
