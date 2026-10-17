package ai.resourcepack.engine.core.pack.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A resource pack, read the way the client reads it: files at paths, and the
 * version overlays its {@code pack.mcmeta} declares.
 *
 * <p>Read once into memory, and only the parts a shader merge needs (the
 * metadata and the core shaders), so a big pack costs its central directory
 * and a few kilobytes of GLSL rather than its textures.
 */
public final class PackSource {

    /** One overlay a pack declares: a directory, and the formats it applies to. */
    public record Overlay(String directory, int min, int max) {

        boolean covers(int format) {
            return format >= min && format <= max;
        }
    }

    private final String name;
    private final Map<String, String> files;
    private final List<Overlay> overlays;

    private PackSource(String name, Map<String, String> files) {
        this.name = name;
        this.files = files;
        this.overlays = parseOverlays(files.get("pack.mcmeta"));
    }

    /** A pack from files already in memory: path to text. */
    public static PackSource of(String name, Map<String, String> files) {
        return new PackSource(name, new TreeMap<>(files));
    }

    /** A pack on disk, a zip or a folder holding {@code pack.mcmeta}; empty if it is neither. */
    public static Optional<PackSource> read(String name, Path location) {
        try {
            if (Files.isDirectory(location)) {
                return Optional.of(fromFolder(name, location));
            }
            if (Files.isRegularFile(location)) {
                return Optional.of(fromZip(name, location));
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static PackSource fromZip(String name, Path zip) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && wanted(path) && entry.getSize() < 4 * 1024 * 1024) {
                    try (var in = file.getInputStream(entry)) {
                        files.put(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return new PackSource(name, files);
    }

    private static PackSource fromFolder(String name, Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var walk = Files.walk(root, 8)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                String path = root.relativize(file).toString().replace('\\', '/');
                if (Files.isRegularFile(file) && wanted(path) && Files.size(file) < 4 * 1024 * 1024) {
                    files.put(path, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        return new PackSource(name, files);
    }

    /** The metadata, every core shader and every include: what a merge reads, and nothing else. */
    private static boolean wanted(String path) {
        return path.equals("pack.mcmeta") || path.contains("assets/minecraft/shaders/core/")
                || path.contains("assets/minecraft/shaders/include/");
    }

    public String name() {
        return name;
    }

    public List<Overlay> overlays() {
        return overlays;
    }

    /** Every file read, path to text. */
    public Map<String, String> files() {
        return files;
    }

    /**
     * The file a client on {@code format} reads at {@code path}: the latest
     * overlay that covers the format and has it, else the pack's own, else
     * none. Overlays later in the list win over earlier ones, as in the game.
     */
    public Optional<String> resolve(String path, int format) {
        for (int i = overlays.size() - 1; i >= 0; i--) {
            Overlay o = overlays.get(i);
            if (o.covers(format)) {
                String text = files.get(o.directory() + "/" + path);
                if (text != null) {
                    return Optional.of(text);
                }
            }
        }
        return Optional.ofNullable(files.get(path));
    }

    /** Whether the pack ships the text shader in any form, for any version. */
    public boolean hasTextShader() {
        for (String path : files.keySet()) {
            if (path.matches("(?:[^/]+/)?assets/minecraft/shaders/core/(?:rendertype_text|text)\\.(?:vsh|fsh|json)")) {
                return true;
            }
        }
        return false;
    }

    private static List<Overlay> parseOverlays(String mcmeta) {
        List<Overlay> out = new ArrayList<>();
        if (mcmeta == null) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(mcmeta).getAsJsonObject();
            if (!root.has("overlays") || !root.get("overlays").isJsonObject()) {
                return out;
            }
            JsonElement list = root.getAsJsonObject("overlays").get("entries");
            if (list == null || !list.isJsonArray()) {
                return out;
            }
            for (JsonElement e : list.getAsJsonArray()) {
                if (!e.isJsonObject() || !e.getAsJsonObject().has("directory")) {
                    continue;
                }
                int[] range = range(e.getAsJsonObject());
                if (range != null) {
                    out.add(new Overlay(e.getAsJsonObject().get("directory").getAsString(), range[0], range[1]));
                }
            }
        } catch (RuntimeException ignored) {
            // A pack whose metadata cannot be read has no overlays the game would honour either.
        }
        return out;
    }

    /** An overlay's formats, written any of the ways the game has accepted. */
    static int[] range(JsonObject o) {
        try {
            if (o.has("min_format") && o.has("max_format")) {
                return new int[] {major(o.get("min_format")), major(o.get("max_format"))};
            }
            JsonElement f = o.get("formats");
            if (f == null) {
                return null;
            }
            if (f.isJsonPrimitive()) {
                return new int[] {f.getAsInt(), f.getAsInt()};
            }
            if (f.isJsonArray()) {
                JsonArray a = f.getAsJsonArray();
                return a.size() == 2 ? new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()} : null;
            }
            if (f.isJsonObject()) {
                return new int[] {f.getAsJsonObject().get("min_inclusive").getAsInt(),
                        f.getAsJsonObject().get("max_inclusive").getAsInt()};
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static int major(JsonElement e) {
        return e.isJsonArray() ? e.getAsJsonArray().get(0).getAsInt() : e.getAsInt();
    }
}
