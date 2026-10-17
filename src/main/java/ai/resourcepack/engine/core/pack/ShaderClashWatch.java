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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Looks after the core shaders RP Engine's packs and other plugins' packs both
 * replace: merges the text shader where it can, and says what it did.
 *
 * <p>A core shader is one file per name and a client keeps only the top pack's
 * copy, so a HUD plugin that draws through the text shader (BetterHud) and a
 * Studio shader object cannot both work unless one file does both. For the
 * text shader that file can be made ({@link MergedShaders}), and it is made
 * INTO the pack of ours that carries the text shader (the server's bundle as
 * it is built, a Studio push as it arrives), so a player is sent exactly the
 * packs they always were, once, with nothing following them. For every other
 * shader, and for a version where the text shader cannot be merged, it says
 * plainly that only one of the two will draw.
 *
 * <p>Two kinds of message, both to the console and to players holding
 * {@code rpengine.shaders} (op by default), live and on joining:
 * <ul>
 *   <li><b>merged</b>: which plugin's shader, into which pack, on which
 *       versions. Merging works, but it is the place a future break would
 *       show up, so it is said.</li>
 *   <li><b>not merged</b>: which file, which plugins, what stops working.</li>
 * </ul>
 * Each is said once, and again only if it went away and came back.
 */
public final class ShaderClashWatch {

    /** The permission whose holders are told in chat. */
    public static final String PERMISSION = "rpengine.shaders";

    /**
     * How long after startup the other plugins' folders are read a second
     * time. The first read is before the bundle is built, and finds whatever
     * pack each plugin left on disk last time; a plugin that builds its pack
     * as it enables (after this one) can have changed it since.
     */
    public static final long SECOND_SCAN_DELAY_TICKS = 20L * 20;

    /** What the plugin around this provides. */
    public interface Host {
        /** Whether {@code shaders.merge} is on. */
        boolean mergeEnabled();
    }

    private final Plugin plugin;
    private final Host host;

    /** Other plugins, by "name (where)", and the shaders their packs on disk replace. */
    private volatile Map<String, Set<String>> theirs = Map.of();
    /** The other plugins' packs that replace the text shader, read, by the same label. */
    private volatile Map<String, PackSource> theirText = Map.of();
    /** What {@link #theirText} held, as one string, so a read that found the same can say so. */
    private volatile String fingerprint = "";

    /** What a pack of ours replaces, by its SHA-1, so a pack is read once however often it is checked. */
    private final Map<String, Set<String>> oursBySha = new ConcurrentHashMap<>();
    /** A merged pack per pack of ours and what it was merged with; absent is "merging changed nothing". */
    private final Map<String, Optional<BuiltPack>> merged = new ConcurrentHashMap<>();

    /** The clash lines standing after the last check. */
    private Set<String> clashLines = new LinkedHashSet<>();
    /** The merge lines standing, by the pack of ours they are about. */
    private final Map<String, List<String>> mergeLines = new LinkedHashMap<>();
    /** Every line already said, so a standing one is said once; forgotten when it stops standing. */
    private final Set<String> said = new HashSet<>();

    public ShaderClashWatch(Plugin plugin, Host host) {
        this.plugin = plugin;
        this.host = host;
    }

    // ---- reading the other plugins' packs ---------------------------------

    /**
     * Reads every other enabled plugin's folder for packs, here and now.
     * Before a build, so the bundle can carry the merge from the start. Main
     * thread; bounded by {@link ShaderClashes#scan}'s depth and entry budget.
     *
     * @return whether what it found differs from the last read
     */
    public boolean scanNow() {
        Map<String, Path> folders = otherFolders(false);
        return found(read(folders));
    }

    /**
     * Reads them again later, off the main thread, and calls {@code then} back
     * on it with whether anything changed. The plugin list is taken when the
     * delay is up: a plugin enabled after this one only then has its own.
     */
    public void scanLater(long delayTicks, Consumer<Boolean> then) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Map<String, Path> folders = otherFolders(true);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                Scan scan = read(folders);
                if (plugin.isEnabled()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> then.accept(found(scan)));
                }
            });
        }, Math.max(1L, delayTicks));
    }

    /** Every other plugin's data folder; only the enabled ones when asked. */
    private Map<String, Path> otherFolders(boolean enabledOnly) {
        Map<String, Path> folders = new TreeMap<>();
        for (Plugin other : plugin.getServer().getPluginManager().getPlugins()) {
            if (other != plugin && (!enabledOnly || other.isEnabled())) {
                folders.put(other.getName(), other.getDataFolder().toPath());
            }
        }
        return folders;
    }

    private record Scan(Map<String, Set<String>> shaders, Map<String, PackSource> text) {
    }

    private Scan read(Map<String, Path> folders) {
        Path pluginsFolder = plugin.getDataFolder().toPath().getParent();
        Map<String, Set<String>> found = new TreeMap<>();
        Map<String, PackSource> text = new TreeMap<>();
        for (Map.Entry<String, Path> e : folders.entrySet()) {
            ShaderClashes.Found result = ShaderClashes.scan(e.getValue(), pluginsFolder);
            if (result.shaders().isEmpty()) {
                continue;
            }
            found.put(e.getKey() + " (" + result.where() + ")", result.shaders());
            if (result.shaders().contains("rendertype_text")) {
                for (Path pack : result.packs()) {
                    Optional<PackSource> source = PackSource.read(e.getKey(), pack);
                    if (source.isPresent() && source.get().hasTextShader()) {
                        // One pack per plugin: the first that has it. A plugin
                        // holding two builds of its pack (a folder and its zip)
                        // means the same shader twice.
                        text.putIfAbsent(e.getKey() + " (" + ShaderClashes.relative(pluginsFolder, pack) + ")", source.get());
                        break;
                    }
                }
            }
        }
        return new Scan(found, text);
    }

    /** Takes a scan's answer. Main thread. */
    private boolean found(Scan scan) {
        theirs = Map.copyOf(scan.shaders());
        theirText = Map.copyOf(scan.text());
        StringBuilder print = new StringBuilder();
        for (Map.Entry<String, PackSource> e : scan.text().entrySet()) {
            print.append(e.getKey()).append('{');
            for (Map.Entry<String, String> f : e.getValue().files().entrySet()) {
                print.append(f.getKey()).append('=').append(f.getValue().hashCode()).append(';');
            }
            print.append('}');
        }
        String now = hash(print.toString());
        boolean changed = !now.equals(fingerprint);
        fingerprint = now;
        if (changed) {
            merged.clear();
        }
        return changed;
    }

    // ---- merging ------------------------------------------------------------

    /**
     * {@code ours} with the other plugin's text shader merged into it, or
     * {@code ours} itself when there is nothing to merge (merging off, no
     * other plugin replaces the text shader, more than one does, ours has no
     * text shader, or no version could be merged). Main thread. Cached per pack
     * and per what it was merged with.
     *
     * @param label  which of our packs, for the messages ("RP Engine's bundle 'main'")
     * @param bundle the name the merged pack is served under
     * @param out    where the merged pack is written
     */
    public BuiltPack merged(BuiltPack ours, String label, String bundle, Path out) {
        if (ours == null || !host.mergeEnabled() || theirText.size() != 1
                || !shadersOf(ours).contains("rendertype_text")) {
            return ours;
        }
        Map.Entry<String, PackSource> other = theirText.entrySet().iterator().next();
        String key = ours.sha1() + "|" + fingerprint + "|" + out;
        Optional<BuiltPack> done = merged.get(key);
        if (done == null || done.isPresent() && !java.nio.file.Files.isRegularFile(done.get().file())) {
            if (merged.size() > 256) {
                merged.clear();
            }
            done = merge(ours, label, bundle, out, other.getKey(), other.getValue());
            merged.put(key, done);
        }
        return done.orElse(ours);
    }

    private Optional<BuiltPack> merge(BuiltPack ours, String label, String bundle, Path out,
                                      String theirLabel, PackSource theirs) {
        Optional<PackSource> source = PackSource.read(ours.bundle(), ours.file());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        MergedShaders.Plan plan = MergedShaders.plan(List.of(source.get()), theirs);
        String who = owner(theirLabel);
        List<String> lines = new ArrayList<>();
        for (MergedShaders.Outcome refused : plan.refused()) {
            lines.add("Could not merge " + label + "'s text shader with " + who + "'s" + where(theirLabel) + " for "
                    + span(refused.versions()) + ": " + refused.refused()
                    + " On those versions a player sees only whichever of the two packs is higher in their list.");
        }
        Optional<BuiltPack> result = Optional.empty();
        if (!plan.isEmpty()) {
            try {
                result = Optional.ofNullable(MergedShaders.into(ours, plan, bundle, out));
                lines.add("Merged " + who + "'s text shader" + where(theirLabel) + " into " + label
                        + ", so both draw, for " + String.join(", ", spans(plan)) + ".");
            } catch (IOException e) {
                lines.add("Could not write " + label + " with " + who + "'s text shader merged in, so only one of"
                        + " the two will draw: " + e.getMessage());
            }
        }
        // A line that stands again (a rebuild that merged the same way) is not
        // said again; one that went away is forgotten, so it would be.
        mergeLines.put(label, lines);
        said.retainAll(union());
        for (String line : lines) {
            announce(line, line.startsWith("Could not"));
        }
        return result;
    }

    /** The versions a plan merged, neighbouring groups read as one span. */
    private static List<String> spans(MergedShaders.Plan plan) {
        List<String> out = new ArrayList<>();
        String run = null;
        for (MergedShaders.Outcome o : plan.outcomes()) {
            if (o.merged()) {
                run = run == null ? o.versions() : run + ", " + o.versions();
            } else if (run != null) {
                out.add(span(run));
                run = null;
            }
        }
        if (run != null) {
            out.add(span(run));
        }
        return out;
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
            if (pack == null) {
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
        if (!merging) {
            mergeLines.clear();
        }
        clashLines = now;
        said.retainAll(union());
        for (String line : now) {
            announce(line, true);
        }
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
        for (List<String> lines : mergeLines.values()) {
            all.addAll(lines);
        }
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
            for (int i = 0; i < 6; i++) {
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
