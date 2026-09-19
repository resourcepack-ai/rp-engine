package ai.resourcepack.engine.core.pack;

import org.bukkit.World;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a datapack this engine generates has to be written.
 *
 * <p>There are two of them — the dialogs and the liquid colours — and both got
 * this wrong in the same way, so it is written once here, beside
 * {@link DataPackMeta} for the same reason.
 *
 * <p>A datapack is per-LEVEL. The server reads {@code datapacks/} out of the
 * directory that holds {@code level.dat} and out of nowhere else, and both of
 * these used to ask {@link World#getWorldFolder()} for it. <strong>That is a
 * world's own data, which a server may keep a level down per dimension</strong>
 * — {@code <level>/dimensions/<namespace>/<name>}, beside that dimension's
 * {@code region} and {@code poi} — rather than the level directory itself.
 *
 * <p>The cost of the difference is worth stating, because it is silent in
 * every direction: a perfectly good pack is written into a directory nothing
 * reads. Nothing fails, nothing is logged, {@code /minecraft:reload} picks up
 * nothing because there is nothing there to pick up, and {@code /datapack
 * enable} cannot name it either — which is the point at which somebody has run
 * out of things to try. The only symptom is a registry that does not contain
 * what the pack holds, which is also the symptom of the {@code pack.mcmeta}
 * trap {@link DataPackMeta} exists for, and the two are indistinguishable from
 * a chat message.
 *
 * <p>So the level directory is found rather than assumed: walk up from the
 * world folder until a {@code level.dat} is underfoot. That is no steps on
 * every layout the game has had until now — where the world folder IS the
 * level directory — and three on the one above, and it has to know about
 * neither.
 */
public final class DataPackFolder {

    /**
     * How far above the world folder the level directory may be.
     *
     * <p>Three is the layout above; the fourth step is slack rather than
     * meaning. The bound is what stops an unexpected world folder walking up
     * into the directory the server was started from and writing a datapack
     * next to the jar.
     */
    private static final int MAX_DEPTH = 4;

    private DataPackFolder() {
    }

    /** The directory to write the named generated datapack into. */
    public static Path of(World world, String pack) {
        return of(world.getWorldFolder().toPath(), pack);
    }

    /** As {@link #of(World, String)}, from a world folder that is already a path. */
    static Path of(Path worldFolder, String pack) {
        return levelRoot(worldFolder).resolve("datapacks").resolve(pack);
    }

    /**
     * The nearest directory at or above {@code worldFolder} holding a
     * {@code level.dat}, or {@code worldFolder} itself when there is none.
     *
     * <p>Falling back to the world folder is the behaviour this replaced, and
     * it is deliberate: a world whose level cannot be found is some layout
     * nobody here has seen, and putting its datapack somewhere inert is better
     * than guessing at a directory further out. Both callers log the path they
     * wrote to, which is what makes that case answerable.
     */
    private static Path levelRoot(Path worldFolder) {
        Path at = worldFolder;
        for (int up = 0; up <= MAX_DEPTH && at != null; up++) {
            if (Files.isRegularFile(at.resolve("level.dat"))) {
                return at;
            }
            at = at.getParent();
        }
        return worldFolder;
    }
}
