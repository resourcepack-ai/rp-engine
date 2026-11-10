package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.core.item.ModelSources;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which files a Nexo or Oraxen reference means, in the folder it was copied to.
 *
 * <p>Both plugins read a path with no namespace as {@code minecraft:}, so
 * {@code model: furniture/chair} is the file at
 * {@code assets/minecraft/models/furniture/chair.json} of their pack. A real
 * pack is full of those, and of other namespaces besides, and all of it can
 * come across whole as a {@code resourcepack/} folder.
 *
 * <p>A file in the pack's own layout wins, because that is where a piece moved
 * across by hand was put, and it is what a bare path has always meant here.
 */
final class NexoArt {

    /** A caller that has no folder to look in: every reference stays as written. */
    static final NexoArt NONE = new NexoArt(null, null);

    private static final List<String> OWN_MODELS = List.of("assets/models/", "models/", "blueprints/");
    private static final List<String> OWN_TEXTURES = List.of("assets/textures/", "textures/");

    private final Path pack;
    private final String namespace;
    private final Set<String> missing = new HashSet<>();

    private NexoArt(Path pack, String namespace) {
        this.pack = pack;
        this.namespace = namespace;
    }

    static NexoArt of(Path pack, String namespace) {
        return new NexoArt(pack, namespace);
    }

    /** The reference to use for a path written with no namespace. */
    String bare(String path) {
        if (pack == null || own(path)) {
            return path;
        }
        if (ships("minecraft", path)) {
            return "minecraft:" + path;
        }
        missing.add(path);
        return path;
    }

    /** Whether the {@code resourcepack/} folder holds a model or texture at {@code owner:path}. */
    boolean ships(String owner, String path) {
        if (pack == null) {
            return false;
        }
        String root = ModelSources.RESOURCE_PACK + "/assets/" + owner + "/";
        return exists(root + "models/" + path + ".json") || exists(root + "textures/" + path + ".png");
    }

    /** How many different paths named no file at all. */
    int missing() {
        return missing.size();
    }

    private boolean own(String path) {
        for (String folder : OWN_MODELS) {
            if (exists(folder + path + ".json") || exists(folder + path + ".bbmodel")) {
                return true;
            }
        }
        for (String folder : OWN_TEXTURES) {
            if (exists(folder + path + ".png")) {
                return true;
            }
        }
        return ships(namespace, path);
    }

    private boolean exists(String relative) {
        try {
            return Files.isRegularFile(pack.resolve(relative));
        } catch (InvalidPathException e) {
            // Characters this filesystem cannot name: no such file.
            return false;
        }
    }
}
