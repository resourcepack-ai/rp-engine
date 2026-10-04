package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.McVersion;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds one resource pack per bundle.
 *
 * <p>This is the piece that makes the engine usable by somebody who has never
 * heard of ResourcePack AI. Studio can hand a server a finished pack, but a
 * server owner writing their own content needs the zip built where the content
 * is, and a marketplace plugin that cannot do that is not a whole product.
 *
 * <p><strong>The output is reproducible.</strong> Every ordering decision in
 * here is sorted rather than incidental, because the ordering reaches
 * {@link DeterministicZip} and therefore the SHA-1 the client caches by. See
 * that class for why it matters more than it looks.
 *
 * <p>What this step does <em>not</em> do is generate per-kind files: no item
 * model JSON, no font providers, no rig geometry. Those arrive with the layers
 * that understand them. What is here is the part every kind shares — routing
 * assets, resolving collisions, writing {@code pack.mcmeta}, and producing a
 * hash somebody can serve.
 */
public final class PackBuilder {

    /**
     * The format used when nobody says otherwise.
     *
     * <p>This used to be the engine's whole answer: one number, for the one
     * version it supported. It is now only a fallback for the constructor
     * that takes no format at all, which is a convenience for tests — a
     * running server's format comes from {@code Compatibility}, resolved from
     * the version the server actually reports, because the engine now spans a
     * range of them and a single compiled-in number would be wrong on all but
     * one.
     *
     * <p>Read from {@link PackFormats} rather than written again here, so
     * there is one table and not a literal beside it that can drift.
     */
    public static final int PACK_FORMAT =
            PackFormats.forVersion(McVersion.of(1, 21, 4)).orElse(46);

    /** Namespaced content: {@code <pack>/assets/**} becomes {@code assets/<namespace>/**}. */
    static final String ASSETS = "assets";

    /**
     * Vanilla replacements: {@code <pack>/overrides/**} becomes
     * {@code assets/minecraft/**}.
     *
     * <p>A separate folder rather than letting a pack write
     * {@code assets/minecraft/} itself, because these are the only files that
     * can collide between two packs in one bundle, and a folder named for what
     * it does makes that visible to the person writing it instead of surprising
     * on the day two packs are installed together.
     */
    static final String OVERRIDES = "overrides";

    /** A pack's own icon, offered to the bundle it ships in. */
    static final String ICON = "pack.png";

    /**
     * Where the other plugins keep their art: at the pack root rather than
     * under {@code assets/}.
     *
     * <p>{@code textures/}, {@code models/}, {@code sounds/} and {@code font/}
     * are ItemsAdder's layout; {@code blueprints/} is ModelEngine's. Copied as
     * if they were under {@code assets/}, so somebody who dropped a folder
     * from either in gets their pictures as well as their content. Ours is
     * still the documented layout — these are more places to look, not more
     * ways to write a pack.
     */
    static final List<String> IMPORTED_ASSETS =
            List.of("textures", "models", "sounds", "font", "blueprints");

    private final int packFormat;
    private final String description;
    private final List<PackContributor> contributors = new ArrayList<>();

    public PackBuilder() {
        this(PACK_FORMAT, "RP Engine");
    }

    public PackBuilder(int packFormat, String description) {
        this.packFormat = packFormat;
        this.description = description == null ? "RP Engine" : description;
    }

    /**
     * Adds something that writes generated files into every bundle.
     *
     * <p>Contributors run in the order they were added, after the pack's own
     * assets are copied. Order is part of the output, so add them in a fixed
     * order rather than from anything iteration-order-dependent.
     */
    public PackBuilder with(PackContributor contributor) {
        if (contributor != null) {
            contributors.add(contributor);
        }
        return this;
    }

    /**
     * Builds every bundle the loaded packs declared.
     *
     * @param contentRoot where the pack folders are, the same root the loader read
     * @param outputDir   where the zips go, created if it is not there
     */
    public BuildReport build(Path contentRoot, Path outputDir, LoadReport loaded) {
        if (contentRoot == null || outputDir == null || loaded == null) {
            return BuildReport.of(List.of(), List.of());
        }
        List<BuiltPack> built = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Bundle bundle : Bundles.resolve(loaded.packs())) {
            buildBundle(contentRoot, outputDir, bundle, loaded, built, diagnostics);
        }
        return BuildReport.of(built, diagnostics);
    }

    private void buildBundle(Path contentRoot, Path outputDir, Bundle bundle, LoadReport loaded,
                             List<BuiltPack> built, List<Diagnostic> diagnostics) {
        DeterministicZip zip = new DeterministicZip();
        // Who wrote each zip path, so a collision can name both sides rather
        // than saying only that one happened.
        Map<String, String> writtenBy = new HashMap<>();

        for (String namespace : bundle.namespaces()) {
            Path packFolder = contentRoot.resolve(namespace);
            copyTree(packFolder.resolve(ASSETS), ASSETS + "/" + namespace,
                    namespace, bundle, zip, writtenBy, diagnostics);
            for (String theirs : IMPORTED_ASSETS) {
                copyTree(packFolder.resolve(theirs), ASSETS + "/" + namespace + "/" + theirs,
                        namespace, bundle, zip, writtenBy, diagnostics);
            }
            copyTree(packFolder.resolve(OVERRIDES), ASSETS + "/minecraft",
                    namespace, bundle, zip, writtenBy, diagnostics);
            addIcon(packFolder.resolve(ICON), namespace, bundle, zip, writtenBy, diagnostics);
        }

        // After the copy, so a contributor can ask whether a texture it is
        // about to point at was actually shipped.
        for (PackContributor contributor : contributors) {
            contributor.contribute(bundle, loaded, new Sink(contentRoot, zip, writtenBy, diagnostics));
        }

        zip.add("pack.mcmeta", mcmeta(bundle).getBytes(StandardCharsets.UTF_8));

        Path file = outputDir.resolve(bundle.name() + ".zip");
        try {
            String sha1 = zip.writeTo(file);
            built.add(BuiltPack.of(bundle.name(), file, sha1, Files.size(file), zip.size()));
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error(bundle.name() + ".zip",
                    "Could not be written. " + message(e)));
        }
    }

    /** A contributor's view of the bundle being built. */
    private static final class Sink implements PackContributor.Contribution {

        private final Path contentRoot;
        private final DeterministicZip zip;
        private final Map<String, String> writtenBy;
        private final List<Diagnostic> diagnostics;

        private Sink(Path contentRoot, DeterministicZip zip,
                     Map<String, String> writtenBy, List<Diagnostic> diagnostics) {
            this.contentRoot = contentRoot;
            this.zip = zip;
            this.writtenBy = writtenBy;
            this.diagnostics = diagnostics;
        }

        @Override
        public void add(String zipPath, byte[] content) {
            zip.add(zipPath, content);
            writtenBy.put(zipPath, "generated");
        }

        @Override
        public boolean has(String zipPath) {
            return zip.has(zipPath);
        }

        @Override
        public void drop(String zipPath) {
            zip.remove(zipPath);
            writtenBy.remove(zipPath);
        }

        @Override
        public java.util.Optional<byte[]> source(String namespace, String relativePath) {
            if (namespace == null || relativePath == null) {
                return java.util.Optional.empty();
            }
            Path file = contentRoot.resolve(namespace).resolve(relativePath);
            // Never outside the pack's own folder, whatever the definition
            // asked for. A content pack is somebody else's file on your disk.
            if (!file.normalize().startsWith(contentRoot.resolve(namespace).normalize())
                    || !Files.isRegularFile(file)) {
                return java.util.Optional.empty();
            }
            try {
                return java.util.Optional.of(Files.readAllBytes(file));
            } catch (IOException e) {
                return java.util.Optional.empty();
            }
        }

        @Override
        public void warn(String origin, String where, String message) {
            diagnostics.add(Diagnostic.warning(origin, where, message));
        }

        @Override
        public void error(String origin, String where, String message) {
            diagnostics.add(Diagnostic.error(origin, where, message));
        }
    }

    /**
     * {@code pack.mcmeta}, written by hand rather than through a JSON library.
     *
     * <p>Two fields and no user input beyond a description we control the
     * escaping of. Pulling in a serializer for this would be more moving parts
     * than the file has.
     */
    private String mcmeta(Bundle bundle) {
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + packFormat + ",\n"
                + "    \"description\": \"" + escape(description + " - " + bundle.name()) + "\"\n"
                + "  }\n"
                + "}\n";
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void addIcon(Path icon, String namespace, Bundle bundle, DeterministicZip zip,
                         Map<String, String> writtenBy, List<Diagnostic> diagnostics) {
        if (!Files.isRegularFile(icon)) {
            return;
        }
        // A bundle has one icon and its packs are visited in sorted order, so
        // the first namespace alphabetically wins. Arbitrary, but stable, and
        // stable is the property that matters: the alternative is a hash that
        // moves depending on which pack was installed first.
        if (writtenBy.containsKey(ICON)) {
            diagnostics.add(Diagnostic.warning(namespace + "/" + ICON,
                    "The bundle " + bundle.name() + " already takes its icon from "
                            + writtenBy.get(ICON) + ", so this one is unused."));
            return;
        }
        readInto(icon, ICON, namespace, zip, writtenBy, diagnostics);
    }

    private void copyTree(Path from, String toPrefix, String namespace, Bundle bundle,
                          DeterministicZip zip, Map<String, String> writtenBy,
                          List<Diagnostic> diagnostics) {
        if (!Files.isDirectory(from)) {
            return;
        }
        for (Path file : sortedFiles(from, namespace, diagnostics)) {
            String path = toPrefix + "/" + relative(from, file);
            String previous = writtenBy.get(path);
            if (previous != null) {
                // Only reachable through overrides/, since everything else is
                // namespaced. Later-sorted wins, which is stable; the warning
                // is what makes it findable.
                diagnostics.add(Diagnostic.warning(namespace + "/" + OVERRIDES,
                        "Both " + previous + " and " + namespace + " replace " + path
                                + " in the bundle " + bundle.name() + ". " + namespace + " wins."));
            }
            readInto(file, path, namespace, zip, writtenBy, diagnostics);
        }
    }

    private void readInto(Path file, String path, String namespace, DeterministicZip zip,
                          Map<String, String> writtenBy, List<Diagnostic> diagnostics) {
        try {
            zip.add(path, Files.readAllBytes(file));
            writtenBy.put(path, namespace);
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error(namespace, "Could not read " + path + ". " + message(e)));
        }
    }

    private List<Path> sortedFiles(Path folder, String namespace, List<Diagnostic> diagnostics) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> children = Files.list(folder)) {
            List<Path> sorted = children
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path child : sorted) {
                if (Files.isDirectory(child)) {
                    found.addAll(sortedFiles(child, namespace, diagnostics));
                } else {
                    found.add(child);
                }
            }
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error(namespace, "Could not list a folder. " + message(e)));
        }
        return found;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.isEmpty() ? e.getClass().getSimpleName() : text;
    }
}
