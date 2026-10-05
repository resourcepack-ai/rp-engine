package ai.resourcepack.engine.core.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The armour layer PNGs a Nexo or Oraxen pack ships, found by their names.
 *
 * <p>Neither plugin needs its armour art written down. Both find it by file
 * name - {@code ruby_armor_layer_1.png} is the body and boots of the
 * {@code ruby} set, {@code ruby_armor_layer_2.png} its leggings - wherever in
 * the pack it is, and pair it with the items called {@code ruby_helmet},
 * {@code ruby_chestplate} and so on. So the only way to read such a pack as it
 * is, is to look for those names the same way.
 *
 * <p>What it finds is a texture reference in the form an item's
 * {@code texture:} takes: a path under the pack's own textures when it is in
 * the pack's namespace, a resource location when it is in a
 * {@code resourcepack/} folder under another one. The build copies it from
 * there to the equipment path the game reads; see {@code armor-art} in
 * FORMAT.md.
 */
final class ArmourLayers {

    /** A pack with no layer art, or a caller that has no folder to look in. */
    static final ArmourLayers NONE = new ArmourLayers(Map.of());

    private static final String LAYER = "_armor_layer_";

    /** {@code ruby_armor_layer_1} (no extension) to its texture reference. */
    private final Map<String, String> found;

    private ArmourLayers(Map<String, String> found) {
        this.found = found;
    }

    /**
     * Every layer PNG in a pack folder.
     *
     * <p>Looked for where the builder serves textures from: the pack's own
     * {@code assets/textures/}, the {@code textures/} other plugins keep at the
     * root, and each namespace of a {@code resourcepack/assets/} folder. The
     * first of two with one name wins, in that order and then by path, which
     * is arbitrary but the same on every machine.
     */
    static ArmourLayers index(Path pack, String namespace) {
        Map<String, String> found = new LinkedHashMap<>();
        collect(pack.resolve("assets").resolve("textures"), "", found);
        collect(pack.resolve("textures"), "", found);
        Path resourcePack = pack.resolve("resourcepack").resolve("assets");
        for (Path other : sorted(resourcePack)) {
            if (Files.isDirectory(other)) {
                String name = other.getFileName().toString();
                collect(other.resolve("textures"), name.equals(namespace) ? "" : name + ":", found);
            }
        }
        return found.isEmpty() ? NONE : new ArmourLayers(Map.copyOf(found));
    }

    /**
     * The layer art of one set: layer 1 for a helmet, chestplate or boots,
     * layer 2 for leggings.
     */
    Optional<String> find(String set, int layer) {
        return set == null ? Optional.empty() : Optional.ofNullable(found.get(set + LAYER + layer));
    }

    /** The layer number a slot is drawn from: leggings have their own, narrower sheet. */
    static int layerOf(String slot) {
        return "legs".equals(slot) ? 2 : 1;
    }

    private static void collect(Path root, String prefix, Map<String, String> found) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".png") || !name.contains(LAYER)) {
                    continue;
                }
                String key = name.substring(0, name.length() - 4);
                String path = root.relativize(file).toString().replace('\\', '/');
                found.putIfAbsent(key, prefix + path.substring(0, path.length() - 4));
            }
        } catch (IOException e) {
            // An unreadable folder has no layers in it as far as a load is
            // concerned; the builder reports the folder when it copies it.
        }
    }

    private static List<Path> sorted(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(folder)) {
            return children.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
