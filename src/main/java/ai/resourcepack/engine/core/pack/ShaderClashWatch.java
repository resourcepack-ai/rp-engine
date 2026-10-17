package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.core.command.EngineCommand;
import ai.resourcepack.engine.core.pack.shader.MergedShaders;
import ai.resourcepack.engine.core.pack.shader.PackSource;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks after the core shaders RP Engine's packs and other plugins' packs both
 * replace: merges the text shader where it can, and says what it did.
 *
 * <p>A core shader is one file per name and a client keeps only the top pack's
 * copy, so a HUD plugin that draws through the text shader (BetterHud) and a
 * Studio shader object cannot both work unless one file does both. For the
 * text shader that file can be made ({@link MergedShaders}); this finds the
 * other plugin's pack, makes it for each set of our packs a player can hold,
 * and serves it as a pack of its own on top of everything else. For every
 * other shader, and for a version where the text shader cannot be merged, it
 * says plainly that only one of the two will draw.
 *
 * <p>Two kinds of message, both to the console and to players holding
 * {@code rpengine.shaders} (op by default), live and on joining:
 * <ul>
 *   <li><b>merged</b>: which plugin's shader, on which versions. Merging works,
 *       but it is the place a future break would show up, so it is said.</li>
 *   <li><b>not merged</b>: which file, which plugins, what stops working.</li>
 * </ul>
 * Each is said when it first appears and again only if it went away and came
 * back.
 */
public final class ShaderClashWatch {

    /** The permission whose holders are told in chat. */
    public static final String PERMISSION = "rpengine.shaders";

    /** The bundle-name prefix of every merged-shader pack. */
    public static final String BUNDLE_PREFIX = "rpengine-shaders-";

    /**
     * How long after startup the other plugins' folders are first read.
     * Most build their pack on enable or on the first tick, some a little
     * later off the main thread; late is fine and early would miss them.
     */
    public static final long FIRST_SCAN_DELAY_TICKS = 20L * 20;

    /** What the plugin around this provides. */
    public interface Host {
        /** Whether {@code shaders.merge} is on. */
        boolean mergeEnabled();

        /** The pack format to write into a merged pack's pack.mcmeta. */
        int packFormat();

        /** Where merged packs are written. */
        Path output();

        /** Makes a written pack downloadable. */
        void serve(BuiltPack pack);

        /** Stops serving a pack nobody holds any more. */
        void unserve(String bundle);
    }

    private final Plugin plugin;
    private final Host host;

    /** Other plugins, by "name (where)", and the shaders their packs on disk replace. Written off the main thread. */
    private volatile Map<String, Set<String>> theirs = Map.of();
    /** The other plugins' packs that replace the text shader, read, by the same label. */
    private volatile Map<String, PackSource> theirText = Map.of();

    /** What a pack of ours replaces, by its SHA-1, so a pack is read once however often it is checked. */
    private final Map<String, Set<String>> oursBySha = new ConcurrentHashMap<>();
    private final Map<String, Optional<PackSource>> sourceBySha = new ConcurrentHashMap<>();
    /** A merged pack (or that there is none) per set of our packs, keyed on their hashes and theirs. */
    private final Map<String, Optional<BuiltPack>> merged = new ConcurrentHashMap<>();

    /** The clash lines standing after the last check, in the order said. */
    private Set<String> clashLines = new LinkedHashSet<>();
    /** The merge lines standing, for the merges currently cached. */
    private final Set<String> mergeLines = new LinkedHashSet<>();
    /** Every line already said, so a standing one is said once; forgotten when it stops standing. */
    private final Set<String> said = new HashSet<>();

    public ShaderClashWatch(Plugin plugin, Host host) {
        this.plugin = plugin;
        this.host = host;
    }

    // ---- reading the other plugins' packs ---------------------------------

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
            Map<String, PackSource> text = new TreeMap<>();
            for (Map.Entry<String, Path> e : folders.entrySet()) {
                ShaderClashes.Found result = ShaderClashes.scan(e.getValue(), pluginsFolder);
                if (result.shaders().isEmpty()) {
                    continue;
                }
                String label = e.getKey() + " (" + result.where() + ")";
                found.put(label, result.shaders());
                if (result.shaders().contains("rendertype_text")) {
                    for (Path pack : result.packs()) {
                        Optional<PackSource> read = PackSource.read(e.getKey(), pack);
                        if (read.isPresent() && read.get().hasTextShader()) {
                            // One pack per plugin: the first that has it. A
                            // plugin holding two builds of its pack (a folder
                            // and its zip) means the same shader twice.
                            text.putIfAbsent(e.getKey() + " (" + ShaderClashes.relative(pluginsFolder, pack) + ")", read.get());
                        }
                    }
                }
            }
            theirs = Map.copyOf(found);
            theirText = Map.copyOf(text);
            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    forgetMerges();
                    then.run();
                });
            }
        });
    }

    /** Drops every merged pack, after the other plugins' packs were read again. */
    private void forgetMerges() {
        for (Optional<BuiltPack> pack : merged.values()) {
            pack.ifPresent(p -> host.unserve(p.bundle()));
        }
        merged.clear();
        mergeLines.clear();
        said.retainAll(clashLines);
    }

    // ---- merging ------------------------------------------------------------

    /**
     * The merged-shader pack to stack on top of {@code ours} (one player's
     * packs from this server, bottom first), or null when there is nothing to
     * merge. Main thread. Cached per set of packs, so calling it on every send
     * costs a map lookup.
     */
    public BuiltPack mergedFor(List<BuiltPack> ours) {
        if (!host.mergeEnabled() || theirText.size() != 1) {
            return null;
        }
        List<BuiltPack> shading = new ArrayList<>();
        for (BuiltPack pack : ours) {
            if (pack != null && shadersOf(pack).contains("rendertype_text")) {
                shading.add(pack);
            }
        }
        if (shading.isEmpty()) {
            return null;
        }
        Map.Entry<String, PackSource> other = theirText.entrySet().iterator().next();
        StringBuilder key = new StringBuilder(other.getKey());
        for (BuiltPack pack : shading) {
            key.append('|').append(pack.sha1());
        }
        return merged.computeIfAbsent(key.toString(), k -> merge(shading, other.getKey(), other.getValue(), k)).orElse(null);
    }

    private Optional<BuiltPack> merge(List<BuiltPack> shading, String label, PackSource theirs, String key) {
        List<PackSource> ours = new ArrayList<>();
        for (BuiltPack pack : shading) {
            Optional<PackSource> source = sourceBySha.computeIfAbsent(pack.sha1(),
                    sha -> PackSource.read(pack.bundle(), pack.file()));
            source.ifPresent(ours::add);
        }
        MergedShaders.Plan plan = MergedShaders.plan(ours, theirs);
        String who = owner(label);
        for (MergedShaders.Outcome refused : plan.refused()) {
            sayMerge("Could not merge RP Engine's text shader with " + who + "'s" + where(label) + " for "
                    + span(refused.versions()) + ": " + refused.refused()
                    + " On those versions a player sees only whichever of the two packs is higher in their list.", true);
        }
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        String bundle = BUNDLE_PREFIX + hash(key);
        try {
            BuiltPack pack = MergedShaders.write(plan, bundle, host.output().resolve("shaders").resolve(bundle + ".zip"),
                    host.packFormat(), "RP Engine - text shader merged with " + who);
            host.serve(pack);
            // Neighbouring merged groups read as one span: they differ only in
            // which file each version gets, which is nobody's business here.
            List<String> versions = new ArrayList<>();
            String run = null;
            for (MergedShaders.Outcome o : plan.outcomes()) {
                if (o.merged()) {
                    run = run == null ? o.versions() : run + ", " + o.versions();
                } else if (run != null) {
                    versions.add(span(run));
                    run = null;
                }
            }
            if (run != null) {
                versions.add(span(run));
            }
            sayMerge("Merged RP Engine's text shader with " + who + "'s" + where(label) + ", so both draw, for "
                    + String.join(", ", versions) + ". It goes to players as a pack of its own on top of the others ("
                    + bundle + ").", false);
            return Optional.of(pack);
        } catch (IOException e) {
            sayMerge("Could not write the merged text shader pack, so only one of RP Engine's and " + who
                    + "'s shaders will draw: " + e.getMessage(), true);
            return Optional.empty();
        }
    }

    /** Whether a pack is one of the merged-shader packs this makes. */
    public static boolean isMergedPack(BuiltPack pack) {
        return pack != null && pack.bundle().startsWith(BUNDLE_PREFIX);
    }

    // ---- saying so ----------------------------------------------------------

    /**
     * Compares our packs with what the last scan found, and says what is new:
     * every shader both replace that is not the merged text shader. Main thread.
     *
     * @param ours each of our packs by a name a person can read; one name may
     *             cover several packs (every player's Studio push, say)
     */
    public void check(List<Map.Entry<String, BuiltPack>> ours) {
        Map<String, Set<String>> ourSide = new TreeMap<>();
        for (Map.Entry<String, BuiltPack> e : ours) {
            BuiltPack pack = e.getValue();
            if (pack == null || isMergedPack(pack)) {
                continue;
            }
            Set<String> shaders = shadersOf(pack);
            if (!shaders.isEmpty()) {
                ourSide.merge(e.getKey(), shaders, (a, b) -> {
                    Set<String> both = new java.util.TreeSet<>(a);
                    both.addAll(b);
                    return both;
                });
            }
        }
        boolean merging = host.mergeEnabled() && theirText.size() == 1;
        Set<String> now = new LinkedHashSet<>();
        for (ShaderClashes.Clash clash : ShaderClashes.find(ourSide, theirs)) {
            if (clash.shader().equals("rendertype_text") && merging) {
                // Said by the merge itself, version by version.
                continue;
            }
            String why = !clash.shader().equals("rendertype_text")
                    ? " RP Engine merges only the text shader."
                    : !host.mergeEnabled()
                    ? " Merging is off (shaders.merge in config.yml)."
                    : " More than one other plugin replaces it, and RP Engine can merge its own with one of them, not several.";
            now.add(clash.describe() + why);
        }
        clashLines = now;
        said.retainAll(union());
        for (String line : now) {
            announce(line, true);
        }
        // Each of our packs alone, so a merge (and its message) happens with nobody online.
        for (Map.Entry<String, BuiltPack> e : ours) {
            if (e.getValue() != null && !isMergedPack(e.getValue())) {
                mergedFor(List.of(e.getValue()));
            }
        }
    }

    private void sayMerge(String line, boolean warning) {
        mergeLines.add(line);
        announce(line, warning);
    }

    /** Logs and tells staff a line, the first time it stands. */
    private void announce(String line, boolean warning) {
        if (!said.add(line)) {
            return;
        }
        if (warning) {
            plugin.getLogger().warning(line);
        } else {
            plugin.getLogger().info(line);
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission(PERMISSION)) {
                EngineCommand.say(player, line);
            }
        }
    }

    private Set<String> union() {
        Set<String> all = new LinkedHashSet<>(clashLines);
        all.addAll(mergeLines);
        return all;
    }

    /** Tells somebody who has just joined about every standing line, if they are staff. */
    public void onJoin(Player player) {
        if (!player.hasPermission(PERMISSION)) {
            return;
        }
        for (String line : union()) {
            EngineCommand.say(player, line);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private Set<String> shadersOf(BuiltPack pack) {
        return oursBySha.computeIfAbsent(pack.sha1(), sha -> ShaderClashes.inZip(pack.file()));
    }

    /** "BetterHud (BetterHud/build.zip)" to "BetterHud". */
    private static String owner(String label) {
        int at = label.indexOf(" (");
        return at > 0 ? label.substring(0, at) : label;
    }

    /** "BetterHud (BetterHud/build.zip)" to " (BetterHud/build.zip)". */
    private static String where(String label) {
        int at = label.indexOf(" (");
        return at > 0 ? label.substring(at) : "";
    }

    /** "1.19.4, 1.20 - 1.20.1, ..., 1.21.5" to "1.19.4 to 1.21.5". */
    static String span(String versions) {
        String[] labels = versions.split(", ");
        String first = labels[0].split(" - ")[0];
        String[] last = labels[labels.length - 1].split(" - ");
        String end = last[last.length - 1];
        return first.equals(end) ? first : first + " to " + end;
    }

    private static String hash(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                out.append(String.format("%02x", d[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    /** What the last scan found, by plugin. */
    public Map<String, Set<String>> found() {
        return theirs;
    }
}
