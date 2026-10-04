package ai.resourcepack.engine.core.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where the model or texture a definition names is read from.
 *
 * <p>A plain name ({@code model: chair}) is a file under the pack's own
 * {@code assets/models/}, or the root {@code models/} and {@code blueprints/}
 * folders other plugins' layouts use. A <strong>namespaced</strong> name
 * ({@code model: minecraft:item/custom/chair}) is a resource location: the
 * model the built pack will have at {@code assets/minecraft/models/item/custom/chair.json}.
 * That is how CraftEngine names art, and its packs keep a whole resource pack
 * folder ({@code resourcepack/assets/<any namespace>/...}) beside their
 * configuration, so a resource location is looked up there, in the pack's own
 * {@code assets/} when it is the pack's own namespace, and in
 * {@code overrides/} when it is {@code minecraft}.
 *
 * <p>The difference that matters is what a bare texture path inside the file
 * means. In a model of ours it is rewritten into the pack's namespace, because
 * that is where its PNGs go. In a file out of a real resource pack it already
 * means {@code minecraft:}, which is the game's own rule, so that is what it
 * is resolved to.
 */
public final class ModelSources {

    /** A pack's folder for a complete resource pack, CraftEngine's layout. */
    public static final String RESOURCE_PACK = "resourcepack";

    private ModelSources() {
    }

    /**
     * A model file found in the pack folder.
     *
     * @param bytes            the file
     * @param path             where it was, relative to the pack folder
     * @param textureNamespace what a bare texture path inside it means
     * @param consumable       whether the copy in the built pack may be
     *                         dropped once it has been rewritten; only ever
     *                         true for the pack's own layout, never for a
     *                         file somebody else's resource pack may also
     *                         reference
     */
    public record Found(byte[] bytes, String path, String textureNamespace, boolean consumable) {
    }

    /**
     * Finds {@code name} with {@code extension} ({@code .json} or
     * {@code .bbmodel}) for a pack in {@code namespace}.
     *
     * @param read reads a path relative to the pack folder
     */
    public static Optional<Found> find(String namespace, String name, String extension,
                                       Function<String, Optional<byte[]>> read) {
        if (isInline(name)) {
            // A model written into the definition itself is its own file.
            return extension.equals(".json")
                    ? Optional.of(new Found(name.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "(inline model)", namespace, false))
                    : Optional.empty();
        }
        for (String[] candidate : candidates(namespace, name, extension)) {
            Optional<byte[]> bytes = read.apply(candidate[0]);
            if (bytes.isPresent()) {
                return Optional.of(new Found(bytes.get(), candidate[0], candidate[1],
                        Boolean.parseBoolean(candidate[2])));
            }
        }
        return Optional.empty();
    }

    /** Whether a model or texture reference is a resource location rather than a name of ours. */
    public static boolean isLocation(String reference) {
        return reference != null && !isInline(reference) && reference.indexOf(':') > 0;
    }

    /**
     * Whether a model "name" is a whole model written inline in the
     * definition ({@code model: {parent: ..., textures: ...}}), which the
     * definition readers carry as its JSON.
     */
    public static boolean isInline(String reference) {
        return reference != null && reference.startsWith("{");
    }

    /**
     * Where a namespaced name was looked for, for an error message that says
     * so.
     */
    public static String describe(String namespace, String name, String extension) {
        List<String> paths = new ArrayList<>();
        for (String[] candidate : candidates(namespace, name, extension)) {
            paths.add(candidate[0]);
        }
        return String.join(" or ", paths);
    }

    /**
     * The texture a sprite item draws, as {@code namespace:path}: the pack's
     * own {@code textures/<texture>} for a plain name, the location as
     * written for a namespaced one.
     */
    public static String textureLocation(String namespace, String texture) {
        return isLocation(texture) ? texture : namespace + ":" + texture;
    }

    /** {path, bare texture namespace, consumable} in the order tried. */
    private static List<String[]> candidates(String namespace, String name, String extension) {
        List<String[]> out = new ArrayList<>();
        if (!isLocation(name)) {
            out.add(new String[] {"assets/models/" + name + extension, namespace, "true"});
            // ItemsAdder's layout keeps models/ at the pack root and
            // ModelEngine's keeps blueprints/; both are read where they lie.
            out.add(new String[] {"models/" + name + extension, namespace, "true"});
            out.add(new String[] {"blueprints/" + name + extension, namespace, "true"});
            return out;
        }
        int colon = name.indexOf(':');
        String owner = name.substring(0, colon);
        String path = name.substring(colon + 1);
        if (owner.equals(namespace)) {
            out.add(new String[] {"assets/models/" + path + extension, namespace, "true"});
        }
        out.add(new String[] {RESOURCE_PACK + "/assets/" + owner + "/models/" + path + extension,
                "minecraft", "false"});
        if (owner.equals("minecraft")) {
            out.add(new String[] {"overrides/models/" + path + extension, "minecraft", "false"});
        }
        return out;
    }
}
