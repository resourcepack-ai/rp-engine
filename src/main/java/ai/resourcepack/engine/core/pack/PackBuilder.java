package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.BuiltPack;
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
     * The resource pack format for 1.21.4, the engine's floor.
     *
     * <p>Not derived from the running server: the floor is a compile-time fact
     * here (the id scheme needs the string-valued {@code item_model} component)
     * and a pack built for a version the engine cannot support would be a
     * quieter failure than refusing.
     */
    public static final int PACK_FORMAT = 46;

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

    private final int packFormat;
    private final String description;

    public PackBuilder() {
        this(PACK_FORMAT, "RP Engine");
    }

    public PackBuilder(int packFormat, String description) {
        this.packFormat = packFormat;
        this.description = description == null ? "RP Engine" : description;
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
            buildBundle(contentRoot, outputDir, bundle, built, diagnostics);
        }
        return BuildReport.of(built, diagnostics);
    }

    private void buildBundle(Path contentRoot, Path outputDir, Bundle bundle,
                             List<BuiltPack> built, List<Diagnostic> diagnostics) {
        DeterministicZip zip = new DeterministicZip();
        // Who wrote each zip path, so a collision can name both sides rather
        // than saying only that one happened.
        Map<String, String> writtenBy = new HashMap<>();

        for (String namespace : bundle.namespaces()) {
            Path packFolder = contentRoot.resolve(namespace);
            copyTree(packFolder.resolve(ASSETS), ASSETS + "/" + namespace,
                    namespace, bundle, zip, writtenBy, diagnostics);
            copyTree(packFolder.resolve(OVERRIDES), ASSETS + "/minecraft",
                    namespace, bundle, zip, writtenBy, diagnostics);
            addIcon(packFolder.resolve(ICON), namespace, bundle, zip, writtenBy, diagnostics);
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
