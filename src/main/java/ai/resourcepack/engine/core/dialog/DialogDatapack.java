package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogInfo;
import ai.resourcepack.engine.core.pack.DataPackFolder;
import ai.resourcepack.engine.core.pack.DataPackMeta;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Where a dialog also lives, and the route that needs a restart.
 *
 * <p>A dialog is not resource-pack art, it is <em>registry data</em>: the
 * server reads {@code data/<namespace>/dialog/<name>.json} out of its
 * datapacks at world load and syncs the lot to each client. This writes those
 * files.
 *
 * <p><strong>Opening a dialog no longer goes through here.</strong> The engine
 * hands the whole dialog to {@code /dialog show} in SNBT instead, so the
 * ordinary path needs no registry entry and therefore no restart - see
 * {@link DialogsImpl} and {@link DialogSnbt}. What this still buys is worth
 * having and is why it was kept: the files make each dialog a real
 * {@code namespace:id} the moment the server next starts, which is what lets a
 * server owner name one from their own datapack, from a command block, or from
 * a {@code minecraft:show_dialog} click event written by hand. It is also the
 * fallback for the one dialog SNBT cannot carry.
 *
 * <p>That is the whole of the old cost, stated once and no longer in anybody's
 * way: <strong>a datapack is read when the server loads its worlds, which is
 * before any plugin is enabled</strong>, so a file written this load is in the
 * registry after the next start and not before. {@code /minecraft:reload}
 * rebuilds the reloadable half of a datapack - recipes, advancements, loot,
 * tags - and the dialog registry is not in that half: it is built with the
 * world, beside the liquid colours' biomes, which the twin of this file has
 * always said the same thing about. {@link #reloadWanted()} is how that state
 * is reported, and it now describes the id rather than the dialog.
 *
 * <p>The directory is ours and is rewritten wholesale: a dialog somebody
 * deleted from their content folder should stop existing, not linger as a
 * screen a command still opens.
 *
 * <h2>Page turns that never reach the server</h2>
 *
 * The registry is also what makes a page turn INSTANT. The server sends every
 * player the whole dialog registry at join, and a {@code show_dialog} by id is
 * opened by the client out of it, with no round trip. So the files here carry
 * their page links as {@code show_dialog} wherever the page linked to has
 * nothing per-player in it ({@link DialogLinks#swap}): a page with a
 * placeholder has to be filled by the engine as it opens, and a copy out of
 * the registry would show its braces. And the engine sends a page's links that
 * way only to a player for whom the registry's copy of every page reachable
 * from it is exactly that player's own version — {@link #registered}, against
 * what the files were when this server started ({@code atStart}).
 *
 * <p>A file naming a dialog that is not in the same load stops the server
 * starting at all ("Failed to load datapacks"), so {@link #write} only ever
 * names a page that already has its file, and takes a page's file away only
 * once nothing names it.
 */
public final class DialogDatapack {

    /** Where the generated dialogs live. Ours alone; rewritten on every load. */
    private static final String PACK = "rpengine_dialogs";

    private final Logger log;

    /**
     * Set, for the life of the JVM, once a write has changed what is on disk:
     * from then on the files are not what the registry was built from, and an
     * engine enabled again in the same process (a plugin reload) must not take
     * them for it. A system property because it has to outlive the classloader.
     */
    private static final String WRITTEN_THIS_RUN = "rpengine.dialogs.written";

    /** What stands in for a dialog file about to be deleted: names nothing, so nothing dangles. */
    private static final String NEUTRAL = "{\"type\":\"minecraft:notice\",\"title\":\"\"}";

    /** Whether what is on disk is not what the running server has read. */
    private volatile boolean reloadWanted;

    /**
     * The files this server's dialog registry was built from: what was on disk
     * when the engine came up, which is after the worlds loaded and before it
     * wrote anything. Empty when that cannot be known.
     */
    private final Map<ContentId, String> atStart;

    /** Whether the registry holds an id, asked once each: it is fixed for the life of the process. */
    private final Map<ContentId, Boolean> inRegistry = new ConcurrentHashMap<>();

    /** How the registry is asked: the server's codec, or a test's stand-in. */
    private final Predicate<ContentId> held;

    public DialogDatapack(Logger log) {
        this(log, Boolean.getBoolean(WRITTEN_THIS_RUN) ? Map.of() : readAtStart(), DialogDatapack::probe);
    }

    /** A registry built from {@code atStart}, holding what {@code held} says. For tests. */
    DialogDatapack(Logger log, Map<ContentId, String> atStart, Predicate<ContentId> held) {
        this.log = log;
        this.atStart = Map.copyOf(atStart);
        this.held = held;
    }

    /** Whether any page could turn without the server this run: false until a restart has read a datapack. */
    public boolean instantPossible() {
        return !atStart.isEmpty();
    }

    /**
     * Whether a player's client already holds {@code content} as dialog {@code id}:
     * it was the file on disk when this server started, so the registry was built
     * from it, and the registry really has the id. The second half is asked of
     * the server's own codec, once, because a pack the world has DISABLED is on
     * disk and not in the registry - and a {@code show_dialog} naming an id the
     * registry lacks stops the whole dialog holding it from opening.
     */
    public boolean registered(ContentId id, String content) {
        String before = id == null ? null : atStart.get(id);
        if (before == null || !before.equals(content)) {
            return false;
        }
        return inRegistry.computeIfAbsent(id, held::test);
    }

    /** Decodes a dialog whose one button opens {@code id}: the codec resolves the id, so it fails when it is missing. */
    private static boolean probe(ContentId id) {
        try {
            DialogPackets.decode("{\"type\":\"minecraft:notice\",\"title\":\"\",\"action\":{\"label\":\"\","
                    + "\"action\":{\"type\":\"minecraft:show_dialog\",\"dialog\":\"" + id + "\"}}}");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The dialog files on disk now, by id. Read once, as the engine comes up. */
    private static Map<ContentId, String> readAtStart() {
        try {
            List<World> worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) {
                return Map.of();
            }
            Path data = DataPackFolder.of(worlds.get(0), PACK).resolve("data");
            if (!Files.isDirectory(data)) {
                return Map.of();
            }
            List<Path> files;
            try (java.util.stream.Stream<Path> found = Files.walk(data)) {
                files = found.filter(Files::isRegularFile).toList();
            }
            Map<ContentId, String> out = new HashMap<>();
            for (Path file : files) {
                // data/<namespace>/dialog/<path>.json, the path free to hold slashes.
                Path rel = data.relativize(file);
                if (rel.getNameCount() < 3 || !rel.getName(1).toString().equals("dialog")) {
                    continue;
                }
                String path = rel.subpath(2, rel.getNameCount()).toString().replace('\\', '/');
                if (!path.endsWith(".json")) {
                    continue;
                }
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                ContentId.parse(rel.getName(0) + ":" + path.substring(0, path.length() - 5))
                        .ifPresent(id -> out.put(id, text));
            }
            return Map.copyOf(out);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * A dialog as its file holds it: its page links to pages, among {@code all},
     * with nothing per-player in them turned into {@code show_dialog}s. The
     * engine works out the same thing for each player to compare against.
     */
    public static String fileContent(String json, Map<ContentId, String> all) {
        Predicate<ContentId> plain = page -> all.containsKey(page)
                && !DialogPlaceholders.any(all.get(page)) && !DialogItems.perPlayer(all.get(page));
        return DialogLinks.swap(json, plain);
    }

    /**
     * Whether the files on disk are ahead of the registry the server built.
     *
     * <p>Says nothing about whether a dialog will OPEN, and has not since the
     * engine started sending the dialog itself. True here means the ids are not
     * addressable from outside the engine yet, which a restart fixes.
     */
    public boolean reloadWanted() {
        return reloadWanted;
    }

    /**
     * Says the server has read what is on disk.
     *
     * <p>There is no asking the registry whether a dialog is in it, so the
     * only evidence available is a dialog that opened — which is what calls
     * this. Without it the flag was set once and never cleared, and "run
     * restart the server" became the answer to every later failure of any
     * kind, given to people who had already restarted twice.
     */
    public void read() {
        reloadWanted = false;
    }

    /**
     * Says the server has NOT read what is on disk, after all.
     *
     * <p>The other half of {@link #read}, and the more reliable of the two: a
     * dialog the registry cannot find is proof of it, whatever this thought
     * before. Without it a pack that was written before a restart — so nothing
     * this run "changed" — reported as loaded and then failed to open.
     */
    public void unread() {
        reloadWanted = true;
    }

    /**
     * The command that puts this pack back in the world's enabled list.
     *
     * <p>Worth printing rather than describing, because the case it is for is
     * one this engine caused: a pack the server once read as INCOMPATIBLE goes
     * into the world's disabled list and stays there, and a start only reads
     * the packs that are not on it. So correcting the pack.mcmeta is not
     * enough on a world that has already seen the bad one — somebody has to
     * say this once. It is still followed by a restart, because enabling a
     * pack puts its files in front of the server and only a start builds the
     * registry out of them.
     */
    public static String enableCommand() {
        return "/datapack enable \"file/" + PACK + "\"";
    }

    /**
     * Writes one file per dialog into the main world's datapack folder.
     *
     * <p>Into the FIRST world's level because that is where the server reads
     * datapacks from, whatever else is loaded — a datapack is per-level and the
     * level is the first world's. Which directory that is is
     * {@link DataPackFolder}'s question, and not the same as the world folder.
     */
    public void write(Collection<DialogInfo> loaded) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return;
        }
        // Only what the game will read. The registry is built from this folder
        // as the world loads, BEFORE any plugin can say anything, and one
        // dialog it cannot parse fails the whole registry — the server does not
        // start. So a dialog the server's own codec refuses is left out of the
        // datapack (it still opens as itself, where a refusal is one line in the
        // log), and the file it had is removed with the rest below.
        List<DialogInfo> dialogs = new java.util.ArrayList<>();
        for (DialogInfo dialog : loaded) {
            String problem = DialogPackets.refusal(dialog.json());
            if (problem == null) {
                dialogs.add(dialog);
            } else {
                log.warning("Leaving " + dialog.id() + " out of the dialog datapack: the game will not read it ("
                        + problem + "). A dialog in that folder the game cannot read stops the server starting.");
            }
        }
        Path root = DataPackFolder.of(worlds.get(0), PACK);
        Path data = root.resolve("data");
        boolean changed;
        try {
            Files.createDirectories(data);
            changed = writeIfDifferent(root.resolve("pack.mcmeta"), mcmeta());
            Map<ContentId, String> all = new LinkedHashMap<>();
            for (DialogInfo dialog : dialogs) {
                all.put(dialog.id(), dialog.json());
            }
            Map<Path, String> wanted = new LinkedHashMap<>();
            for (DialogInfo dialog : dialogs) {
                ContentId id = dialog.id();
                Path folder = data.resolve(id.namespace()).resolve("dialog");
                Path file = folder.resolve(id.path() + ".json");
                // The file's own folder rather than dialog/: a path may carry
                // slashes (an authored `menus/shop` is dialog/menus/shop.json),
                // and one that did threw here and stopped every write after it.
                Files.createDirectories(file.getParent());
                // First every page gets a file, as it is, before anything names
                // it: a write cut short leaves no link to a page with no file.
                if (!Files.isRegularFile(file)) {
                    Files.write(file, dialog.json().getBytes(StandardCharsets.UTF_8));
                    changed = true;
                }
                wanted.put(file, fileContent(dialog.json(), all));
            }
            // Then what each file is for: its links between pages the client
            // can open as they are, every one of which now has its file.
            for (Map.Entry<Path, String> entry : wanted.entrySet()) {
                changed |= writeIfDifferent(entry.getKey(), entry.getValue());
            }
            changed |= removeOthers(data, wanted.keySet());
        } catch (IOException e) {
            log.warning("Could not write the dialog datapack: " + e.getMessage());
            return;
        }

        // Set by a write that CHANGED something — a later write that happened
        // to change nothing does not undo it. Cleared only by {@link #read},
        // which is a dialog that actually opened.
        if (changed) {
            reloadWanted = true;
            System.setProperty(WRITTEN_THIS_RUN, "true");
            // Deliberately not an instruction. These open right now, in the
            // command, and telling somebody to restart for something that
            // already works is how a restart became the answer to every later
            // problem. The only thing the restart buys is the id.
            log.info("Dialogs were written to " + root + ". They open now — /rp dialogs lists them. "
                    + "After the next restart each one is also a registry id other datapacks and "
                    + "command blocks can name, and their pages turn without waiting for the server.");
        }
    }

    /** True when the file was not already exactly this. */
    private static boolean writeIfDifferent(Path path, String content) throws IOException {
        if (Files.isRegularFile(path)
                && new String(Files.readAllBytes(path), StandardCharsets.UTF_8).equals(content)) {
            return false;
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /**
     * Deletes dialog files for ids that are gone. Each is first made to name
     * nothing, and only then are any deleted: two that are going may name each
     * other, and a write cut short between deleting one and the other would
     * leave a link to a file that is not there.
     */
    private static boolean removeOthers(Path data, Set<Path> keep) throws IOException {
        List<Path> gone = new ArrayList<>();
        if (!Files.isDirectory(data)) {
            return false;
        }
        try (java.util.stream.Stream<Path> found = Files.walk(data)) {
            found.filter(Files::isRegularFile).filter(path -> !keep.contains(path)).forEach(gone::add);
        }
        for (Path path : gone) {
            writeIfDifferent(path, NEUTRAL);
        }
        for (Path path : gone) {
            Files.deleteIfExists(path);
        }
        return !gone.isEmpty();
    }

    /**
     * The data-pack format of 1.21.6, the first version that has dialogs at
     * all — so the pack says "80 and up" and nothing here has to know what the
     * running server is.
     *
     * <p><b>It was 71, which is 1.21.5</b>, under a comment claiming 71 was
     * 1.21.6. Harmless for as long as {@code supported_formats} was read, and
     * fatal the moment it was not — see {@link DataPackMeta}.
     */
    private static final int DIALOG_PACK_FORMAT = 80;

    private static String mcmeta() {
        return DataPackMeta.mcmeta("RP Engine dialogs. Generated \u2014 edits are overwritten.", DIALOG_PACK_FORMAT);
    }
}
