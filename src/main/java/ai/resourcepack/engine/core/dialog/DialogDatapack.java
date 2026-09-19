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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Where a dialog actually lives.
 *
 * <p>A dialog is not resource-pack art, it is <em>registry data</em>: the
 * server reads {@code data/<namespace>/dialog/<name>.json} out of its
 * datapacks, syncs the lot to each client, and {@code /dialog show} names one
 * of them. There is no packet a plugin can send to invent one, and no Bukkit
 * API that registers one — so the only way for a plugin to have a dialog is to
 * write a datapack and be read.
 *
 * <p>This is the same shape as the liquid-colour datapack and carries the same
 * cost, stated in the same way: <strong>a datapack is read when the server
 * loads its worlds, which is before any plugin is enabled.</strong> So a
 * dialog written by this load is not in the registry until the server is
 * RESTARTED, and {@code pending()} on the API is how the engine says so rather
 * than quietly opening nothing.
 *
 * <p><strong>A reload does not do it, and saying it did sent people reloading
 * for ever.</strong> {@code /minecraft:reload} rebuilds the reloadable half of
 * a datapack — recipes, advancements, loot, tags — and the dialog registry is
 * not in that half: it is built with the world, beside the liquid colours'
 * biomes, which the twin of this file has always said the same thing about. A
 * dialog written into a pack the server has already read and ENABLED is still
 * missing after a reload; the game answers {@code /dialog show} with "Can't
 * find element in registry 'minecraft:dialog'" until the next start.
 *
 * <p><b>Nothing here reloads on its own, and it would not help if it did.</b>
 * {@code Server#reloadData} rebuilds every recipe and loot table on the
 * server, including other plugins' — a real surprise to hand somebody because
 * one screen changed — and it would still not put this dialog in the registry,
 * for the reason above. There is no call that would. So this writes and
 * reports, and the owner restarts when it suits them.
 *
 * <p>The directory is ours and is rewritten wholesale: a dialog somebody
 * deleted from their content folder should stop existing, not linger as a
 * screen a command still opens.
 */
public final class DialogDatapack {

    /** Where the generated dialogs live. Ours alone; rewritten on every load. */
    private static final String PACK = "rpengine_dialogs";

    private final Logger log;

    /** Whether what is on disk is not what the running server has read. */
    private volatile boolean reloadWanted;

    public DialogDatapack(Logger log) {
        this.log = log;
    }

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
    public void write(Collection<DialogInfo> dialogs) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return;
        }
        Path root = DataPackFolder.of(worlds.get(0), PACK);
        Path data = root.resolve("data");
        boolean changed;
        try {
            Files.createDirectories(data);
            changed = writeIfDifferent(root.resolve("pack.mcmeta"), mcmeta());
            Set<Path> wanted = new LinkedHashSet<>();
            for (DialogInfo dialog : dialogs) {
                ContentId id = dialog.id();
                Path folder = data.resolve(id.namespace()).resolve("dialog");
                Files.createDirectories(folder);
                Path file = folder.resolve(id.path() + ".json");
                wanted.add(file);
                changed |= writeIfDifferent(file, dialog.json());
            }
            changed |= removeOthers(data, wanted);
        } catch (IOException e) {
            log.warning("Could not write the dialog datapack: " + e.getMessage());
            return;
        }

        // Set by a write that CHANGED something — a later write that happened
        // to change nothing does not undo it. Cleared only by {@link #read},
        // which is a dialog that actually opened.
        if (changed) {
            reloadWanted = true;
            log.info("Dialogs were written to " + root + ". RESTART the server for them to "
                    + "open: dialogs are registry data, read when the world loads and before "
                    + "plugins start, and /minecraft:reload cannot add one.");
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

    /** Deletes dialog files for ids that are gone, and namespaces left empty. */
    private static boolean removeOthers(Path data, Set<Path> keep) throws IOException {
        List<Path> gone = new ArrayList<>();
        if (!Files.isDirectory(data)) {
            return false;
        }
        try (java.util.stream.Stream<Path> found = Files.walk(data)) {
            found.filter(Files::isRegularFile).filter(path -> !keep.contains(path)).forEach(gone::add);
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
