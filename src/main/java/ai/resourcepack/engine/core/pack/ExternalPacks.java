package ai.resourcepack.engine.core.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * {@code pack.merge}: other resource packs built into RP Engine's own, so a
 * player is sent one pack rather than one per plugin.
 *
 * <p>Each entry is a folder or a {@code .zip}, usually the pack another plugin
 * generates in its own folder. A folder is read where it is; a zip is unpacked
 * into {@code cache/merge/}, again only when the zip has changed. Either way
 * the result goes into the build the way a resource pack dropped into
 * {@code content/} does (see {@link PlainPack}), but the source is never
 * moved or touched, because it belongs to the other plugin, which rewrites it.
 *
 * <p>Each gets a namespace starting {@code _merge.}. It names the pack in the
 * console and decides who wins a file both ship: the build copies packs in
 * sorted order and the later one wins, and {@code _} sorts before the letters
 * a content folder is named with, so the server's own content wins over a
 * merged pack.
 */
public final class ExternalPacks {

    /** The namespace prefix of a merged pack. */
    public static final String PREFIX = "_merge.";

    private ExternalPacks() {
    }

    /** What the entries resolved to, and what could not be. */
    public record Resolved(Map<String, Path> packs, List<String> problems) {
    }

    /**
     * Resolves the configured entries.
     *
     * @param entries as written in config.yml
     * @param base    what a relative entry is relative to: the plugins folder
     * @param cache   where zips are unpacked
     */
    public static Resolved resolve(List<String> entries, Path base, Path cache) {
        Map<String, Path> packs = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        if (entries == null) {
            return new Resolved(packs, problems);
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path source = base.resolve(entry.trim()).normalize();
            String name = source.getFileName() == null ? "pack" : source.getFileName().toString();
            String namespace = PREFIX + PlainPack.namespaceFor(
                    name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? name : name + ".zip");
            if (packs.containsKey(namespace)) {
                namespace = namespace + "." + packs.size();
            }
            try {
                Path folder;
                if (Files.isDirectory(source)) {
                    folder = source;
                } else if (Files.isRegularFile(source) && name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    folder = unpack(source, cache.resolve(namespace.substring(PREFIX.length())));
                } else {
                    problems.add(entry + ": no folder or .zip there (" + source + ").");
                    continue;
                }
                Path root = packRoot(folder);
                if (root == null) {
                    problems.add(entry + ": not a resource pack (no assets folder in it).");
                    continue;
                }
                packs.put(namespace, root);
            } catch (IOException e) {
                problems.add(entry + ": could not be read: " + e.getMessage());
            }
        }
        return new Resolved(packs, problems);
    }

    /**
     * The folder holding {@code assets/}: the folder itself, or the one folder
     * inside it, which is how a zip somebody made by zipping a folder comes out.
     */
    static Path packRoot(Path folder) throws IOException {
        if (Files.isDirectory(folder.resolve(PackBuilder.ASSETS))) {
            return folder;
        }
        List<Path> children;
        try (Stream<Path> list = Files.list(folder)) {
            children = list.filter(Files::isDirectory).toList();
        }
        if (children.size() == 1 && Files.isDirectory(children.get(0).resolve(PackBuilder.ASSETS))) {
            return children.get(0);
        }
        return null;
    }

    /** Unpacks {@code zip} into {@code into} unless it already holds this zip's contents. */
    static Path unpack(Path zip, Path into) throws IOException {
        String stamp = Files.size(zip) + ":" + Files.getLastModifiedTime(zip).toMillis();
        Path marker = into.resolve(".rpengine-source");
        if (Files.isRegularFile(marker) && Files.readString(marker, StandardCharsets.UTF_8).equals(stamp)) {
            return into;
        }
        deleteTree(into);
        Files.createDirectories(into);
        Path root = into.toAbsolutePath().normalize();
        try (InputStream raw = Files.newInputStream(zip); ZipInputStream in = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path to = root.resolve(entry.getName()).normalize();
                if (!to.startsWith(root)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(marker, stamp, StandardCharsets.UTF_8);
        return into;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
