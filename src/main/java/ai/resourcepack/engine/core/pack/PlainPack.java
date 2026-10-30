package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Diagnostic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An ordinary resource pack in the content folder: {@code pack.mcmeta} and
 * {@code assets/} at the top, and no {@code pack.yml}.
 *
 * <p>Copied into the built pack as written, exactly as a pack's
 * {@code resourcepack/} folder is - every namespace, {@code minecraft}
 * included, its overlays and its {@code pack.png}. It defines nothing; it is
 * art, shaders and sounds that ride along with everything else.
 *
 * <p>It needs no {@code pack.yml} because {@code pack.mcmeta} beside
 * {@code assets/} already says what the folder is. A {@code pack.yml} is
 * demanded of other folders so a stray directory never becomes a namespace by
 * accident, and a stray directory does not look like this.
 *
 * <p>A {@code .zip} of one dropped into the content folder is unpacked into
 * such a folder by {@link #unpackZips} and moved out of the way, because that
 * is what everybody tries first, and the folder is then the one copy to edit.
 */
public final class PlainPack {

    /** Where an unpacked zip goes, beside {@code content/} rather than in it. */
    public static final String UNPACKED = "unpacked-zips";

    private static final String META = "pack.mcmeta";

    private PlainPack() {
    }

    /** Whether {@code folder} is an ordinary resource pack rather than a content pack. */
    public static boolean is(Path folder) {
        return folder != null
                && !Files.exists(folder.resolve("pack.yml"))
                && Files.isRegularFile(folder.resolve(META))
                && Files.isDirectory(folder.resolve(PackBuilder.ASSETS));
    }

    /**
     * The namespace a zip's file name becomes: lower-cased, and every
     * character a namespace cannot hold turned into {@code _}, because pack
     * zips are called things like {@code Faithful 32x - 1.21.zip}.
     */
    public static String namespaceFor(String fileName) {
        String stem = fileName.substring(0, fileName.length() - ".zip".length()).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(stem.length());
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (ok) {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        while (out.length() > 0 && "_.-".indexOf(out.charAt(out.length() - 1)) >= 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * Unpacks every resource pack {@code .zip} at the top of
     * {@code contentRoot} into a folder of its own, and moves the zip to
     * {@code unpackedRoot}.
     *
     * <p>Never over an existing folder: that folder is what the server owner
     * edits, and a zip dropped in later is not allowed to quietly replace it.
     * A zip that is not a resource pack is left where it is.
     */
    public static Unpacked unpackZips(Path contentRoot, Path unpackedRoot) {
        List<String> unpacked = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (contentRoot == null || !Files.isDirectory(contentRoot)) {
            return new Unpacked(unpacked, diagnostics);
        }
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(contentRoot)) {
            for (Path child : children) {
                if (Files.isRegularFile(child)
                        && child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    zips.add(child);
                }
            }
        } catch (IOException e) {
            return new Unpacked(unpacked, diagnostics);
        }
        zips.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path zip : zips) {
            if (unpack(zip, contentRoot, unpackedRoot, diagnostics)) {
                unpacked.add(zip.getFileName() + " -> " + namespaceFor(zip.getFileName().toString()) + "/");
            }
        }
        return new Unpacked(unpacked, diagnostics);
    }

    /**
     * The Studio content manifests in the content folder's resource packs, by
     * folder name, sorted: the {@code studio-content.json} a ResourcePack AI
     * Studio export carries at its root, where the game never looks. It is
     * what says which of the pack's characters are a shader object, a screen
     * or a dialog, which the art alone cannot. A folder whose name is not a
     * namespace is skipped, since the loader refuses that folder's art too.
     */
    public static java.util.Map<String, Path> studioManifests(Path contentRoot, String fileName) {
        java.util.Map<String, Path> found = new java.util.TreeMap<>();
        if (contentRoot == null || !Files.isDirectory(contentRoot)) {
            return found;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(contentRoot)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                Path manifest = child.resolve(fileName);
                if (Files.isDirectory(child) && ContentId.isValidNamespace(name) && is(child)
                        && Files.isRegularFile(manifest)) {
                    found.put(name, manifest);
                }
            }
        } catch (IOException e) {
            return found;
        }
        return found;
    }

    /**
     * What {@link #unpackZips} did: each zip it unpacked, as
     * {@code "name.zip -> folder/"}, and what went wrong with the rest.
     */
    public record Unpacked(List<String> unpacked, List<Diagnostic> diagnostics) {
    }

    private static boolean unpack(Path zip, Path contentRoot, Path unpackedRoot, List<Diagnostic> diagnostics) {
        String name = zip.getFileName().toString();
        String namespace = namespaceFor(name);
        if (!ContentId.isValidNamespace(namespace)) {
            diagnostics.add(Diagnostic.error(name, "Could not make a pack name out of this file's name. "
                    + "Rename it to lowercase a-z, digits, and _ . - only."));
            return false;
        }
        Path target = contentRoot.resolve(namespace);
        if (Files.exists(target)) {
            diagnostics.add(Diagnostic.warning(name, namespace + "/ already exists, so this zip was not unpacked. "
                    + "Delete that folder to replace it with the zip."));
            return false;
        }
        try {
            if (!extract(zip, target)) {
                diagnostics.add(Diagnostic.warning(name, "Not a resource pack (no " + META
                        + " and assets/ inside), so it was left alone."));
                return false;
            }
        } catch (IOException | RuntimeException e) {
            delete(target);
            diagnostics.add(Diagnostic.error(name, "Could not be unpacked. " + describe(e)));
            return false;
        }
        try {
            Files.createDirectories(unpackedRoot);
            Files.move(zip, unpackedRoot.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            diagnostics.add(Diagnostic.warning(name, "Unpacked into " + namespace + "/, but the zip could not be "
                    + "moved to " + unpackedRoot.getFileName() + "/, so delete it yourself. " + describe(e)));
        }
        return true;
    }

    /**
     * Unpacks the resource pack in {@code zip} into {@code target}, from
     * wherever in the zip the pack starts. False, with nothing written, when
     * it is not a resource pack. A throw part way leaves what was written for
     * the caller to remove.
     *
     * <p>Straight into the folder rather than beside it and renamed: on
     * Windows a folder of files just written is often still held open by a
     * virus scanner, and renaming it fails.
     */
    public static boolean extract(Path zip, Path target) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            String prefix = packRoot(file);
            if (prefix == null) {
                return false;
            }
            Files.createDirectories(target);
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !path.startsWith(prefix)) {
                    continue;
                }
                Path out = target.resolve(path.substring(prefix.length())).normalize();
                // A zip is somebody else's file: an entry naming ../ is not
                // allowed out of the folder it is being unpacked into.
                if (!out.startsWith(target) || out.equals(target)) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        }
    }

    /** An exception as a person can read it: several only carry a path as their message. */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    /**
     * Where the pack starts inside the zip: at the top, or inside the one
     * folder a zip made by right-clicking a pack folder puts everything in.
     * Null when it is not a resource pack at all.
     */
    private static String packRoot(ZipFile file) {
        Set<String> names = new HashSet<>();
        Set<String> tops = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = file.entries();
        while (entries.hasMoreElements()) {
            String path = entries.nextElement().getName().replace('\\', '/');
            names.add(path);
            int slash = path.indexOf('/');
            tops.add(slash < 0 ? path : path.substring(0, slash + 1));
        }
        if (holdsPack(names, "")) {
            return "";
        }
        if (tops.size() == 1) {
            String only = tops.iterator().next();
            if (only.endsWith("/") && holdsPack(names, only)) {
                return only;
            }
        }
        return null;
    }

    private static boolean holdsPack(Set<String> names, String prefix) {
        if (!names.contains(prefix + META)) {
            return false;
        }
        String assets = prefix + PackBuilder.ASSETS + "/";
        for (String name : names) {
            if (name.startsWith(assets)) {
                return true;
            }
        }
        return false;
    }

    public static void delete(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Whatever is left is named by the next load's "already exists".
        }
    }
}
