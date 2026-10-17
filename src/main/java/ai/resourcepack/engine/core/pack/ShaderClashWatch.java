package ai.resourcepack.engine.core.pack;

import ai.resourcepack.common.shader.ShaderClashes;
import ai.resourcepack.common.shader.ShaderWatch;
import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.core.command.EngineCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * RP Engine's side of the shader merge, which is the shared library's
 * ({@link ShaderWatch}): this only turns our built packs into files and back.
 *
 * <p>The merged text shader goes INTO the pack of ours that carries our text
 * shader (the server's bundle as it is built, a Studio push as it lands), so a
 * player is sent exactly the packs they always were, once, with nothing
 * following them.
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

        /** Whether {@code shaders.notify-players} is on: chat as well as the console. */
        boolean notifyPlayers();
    }

    private final ShaderWatch watch;

    /** What a pack of ours replaces, by its SHA-1, so a pack is read once however often it is checked. */
    private final Map<String, Set<String>> oursBySha = new ConcurrentHashMap<>();
    /** A merged pack per pack of ours and what it was merged with; absent is "merging changed nothing". */
    private final Map<String, Optional<BuiltPack>> merged = new ConcurrentHashMap<>();

    public ShaderClashWatch(Plugin plugin, Host host) {
        this.watch = new ShaderWatch(plugin, new ShaderWatch.Host() {
            @Override
            public String displayName() {
                return "RP Engine";
            }

            @Override
            public String permission() {
                return PERMISSION;
            }

            @Override
            public boolean mergeEnabled() {
                return host.mergeEnabled();
            }

            @Override
            public boolean notifyPlayers() {
                return host.notifyPlayers();
            }

            @Override
            public void say(Player player, String line) {
                EngineCommand.say(player, line);
            }
        });
    }

    /** Reads the other plugins' packs now. See {@link ShaderWatch#scanNow}. */
    public boolean scanNow() {
        boolean changed = watch.scanNow();
        if (changed) {
            merged.clear();
        }
        return changed;
    }

    /** Reads them again later. See {@link ShaderWatch#scanLater}. */
    public void scanLater(long delayTicks, Consumer<Boolean> then) {
        watch.scanLater(delayTicks, changed -> {
            if (changed) {
                merged.clear();
            }
            then.accept(changed);
        });
    }

    /**
     * {@code ours} with the other plugin's text shader merged into it, or
     * {@code ours} itself when there is nothing to merge. Main thread. Cached
     * per pack and per what it was merged with.
     *
     * @param label  which of our packs, for the messages ("RP Engine's bundle 'main'")
     * @param bundle the name the merged pack is served under
     * @param out    where the merged pack is written
     */
    public BuiltPack merged(BuiltPack ours, String label, String bundle, Path out) {
        if (ours == null || !watch.canMerge() || !shadersOf(ours).contains("rendertype_text")) {
            return ours;
        }
        String key = ours.sha1() + "|" + watch.fingerprint() + "|" + out;
        Optional<BuiltPack> done = merged.get(key);
        if (done == null || done.isPresent() && !Files.isRegularFile(done.get().file())) {
            if (merged.size() > 256) {
                merged.clear();
            }
            done = merge(ours, label, bundle, out);
            merged.put(key, done);
        }
        return done.orElse(ours);
    }

    private Optional<BuiltPack> merge(BuiltPack ours, String label, String bundle, Path out) {
        Map<String, byte[]> files;
        try {
            files = read(ours.file());
        } catch (IOException e) {
            return Optional.empty();
        }
        Optional<Map<String, byte[]>> result = watch.merge(label, files, 0);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        try {
            DeterministicZip zip = new DeterministicZip();
            for (Map.Entry<String, byte[]> e : result.get().entrySet()) {
                zip.add(e.getKey(), e.getValue());
            }
            Files.createDirectories(out.getParent());
            String sha1 = zip.writeTo(out);
            return Optional.of(BuiltPack.of(bundle, out, sha1, Files.size(out), zip.size()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Map<String, byte[]> read(Path zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            var all = file.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (!entry.isDirectory()) {
                    try (var in = file.getInputStream(entry)) {
                        entries.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        return entries;
    }

    /**
     * Compares our packs with what the last scan found, and says what is new.
     *
     * @param ours each of our packs by a name a person can read; one name may
     *             cover several packs (every player's Studio push, say)
     */
    public void check(List<Map.Entry<String, BuiltPack>> ours) {
        Map<String, Set<String>> ourSide = new TreeMap<>();
        for (Map.Entry<String, BuiltPack> e : ours) {
            if (e.getValue() == null) {
                continue;
            }
            Set<String> shaders = shadersOf(e.getValue());
            if (!shaders.isEmpty()) {
                ourSide.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(shaders);
            }
        }
        watch.check(ourSide);
    }

    /** Tells somebody who has just joined about every standing line, if they are staff. */
    public void onJoin(Player player) {
        watch.onJoin(player);
    }

    /** What the last scan found, by plugin. */
    public Map<String, Set<String>> found() {
        return watch.found();
    }

    private Set<String> shadersOf(BuiltPack pack) {
        return oursBySha.computeIfAbsent(pack.sha1(), sha -> ShaderClashes.inZip(pack.file()));
    }
}
