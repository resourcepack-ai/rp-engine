package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ClaimResult;
import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentEntry;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistration;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.Namespace;
import ai.resourcepack.engine.api.PackMeta;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a folder of hand-authored content and registers what it finds.
 *
 * <p>The format is specified in {@code FORMAT.md} and this class is tested
 * against that document rather than the other way round. Internal; a host
 * calls it and reads the {@link LoadReport} it returns.
 *
 * <p>Nothing here touches Bukkit, which is why the whole format is testable
 * against a temp directory with no server running.
 */
public final class ContentFolderLoader {

    /**
     * Which top-level folder inside a pack yields which kind.
     *
     * <p><strong>A category exists only if something reads it.</strong> A
     * folder that loads, registers ids and is then never looked at is worse
     * than no folder at all: it is an afternoon spent writing content that
     * does nothing, with no error to explain why. So there is no
     * {@code models/} — a placed model is an item you can put down, declared
     * in a {@code place:} block on the item, because an id is unique across
     * the registry and one chair cannot be two ids without the format feeling
     * like paperwork. There is no {@code blocks/}, because custom blocks are
     * not a feature here. And there is no {@code emotes/} yet: emote keyframes
     * arrive from a Studio push, and the day they can be hand-written is the
     * day this gains a line.
     *
     * <p>Anything else is warned about by name, which is what tells somebody
     * they have typed {@code item/} or {@code Sounds/}.
     */
    private static final Map<String, ContentKind> CATEGORIES = categories();

    /**
     * Folders that hold raw files rather than definitions, and are never
     * walked for them. The builder is what reads these; see FORMAT.md for
     * where each one lands in the built pack.
     */
    private static final Set<String> ASSET_FOLDERS = Set.of("assets", "overrides",
            // ItemsAdder's own layout keeps art at the pack root. Read by the
            // builder from there as well, so warning about it would be telling
            // somebody off for a folder that works.
            "textures", "models", "sounds", "font");

    /**
     * Kinds that are NOT put into the id space.
     *
     * <p>A recipe is not content, it is a rule about content: nothing ships in
     * the pack for it, nothing references it by id, and no asset path derives
     * from it. Registering one would mean {@code mypack:ruby_cube} could be an
     * item or a recipe but never both — and naming a recipe after the thing it
     * makes is the first thing anybody writes.
     *
     * <p>They still get a {@link ContentDefinition} and are still unique within
     * their own kind, so two recipes cannot quietly share a name either.
     */
    private static final Set<ContentKind> UNREGISTERED =
            Set.of(ContentKind.RECIPE);

    private static final String PACK_FILE = "pack.yml";

    private final ContentRegistration registration;

    public ContentFolderLoader(ContentRegistration registration) {
        this.registration = registration;
    }

    private static Map<String, ContentKind> categories() {
        Map<String, ContentKind> map = new LinkedHashMap<>();
        map.put("items", ContentKind.ITEM);
        map.put("blocks", ContentKind.BLOCK);
        map.put("sounds", ContentKind.SOUND);
        map.put("fonts", ContentKind.FONT);
        map.put("screens", ContentKind.SCREEN);
        map.put("huds", ContentKind.HUD);
        map.put("recipes", ContentKind.RECIPE);
        map.put("entities", ContentKind.ENTITY);
        map.put("liquids", ContentKind.LIQUID);
        return Map.copyOf(map);
    }

    /**
     * Loads every pack under {@code root}.
     *
     * <p>Packs are visited in sorted order so that two servers with the same
     * folder on disk load it the same way. That matters more than it looks:
     * once bundles are built from this, load order reaching the output would
     * make the built zip differ between machines and cost every player a
     * redownload.
     *
     * @param root   the content folder, which is allowed not to exist
     * @param source which front door these packs count as
     */
    public LoadReport load(Path root, ContentSource source) {
        List<PackMeta> packs = new ArrayList<>();
        List<ContentDefinition> definitions = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();

        if (root == null || source == null || !Files.isDirectory(root)) {
            return LoadReport.empty();
        }

        for (Path folder : sortedChildren(root, diagnostics, "")) {
            if (Files.isDirectory(folder)) {
                loadPack(root, folder, source, packs, definitions, diagnostics);
            }
        }
        return LoadReport.of(packs, definitions, diagnostics);
    }

    private void loadPack(Path root, Path folder, ContentSource source,
                          List<PackMeta> packs, List<ContentDefinition> definitions,
                          List<Diagnostic> diagnostics) {
        String namespace = folder.getFileName().toString();
        String origin = relative(root, folder);

        if (!ContentId.isValidNamespace(namespace)) {
            diagnostics.add(Diagnostic.error(origin,
                    "Folder name is not a valid namespace. Use lowercase a-z, digits, and _ . - only."));
            return;
        }

        Path packFile = folder.resolve(PACK_FILE);
        boolean itemsAdder = !Files.isRegularFile(packFile) && holdsItemsAdderConfig(folder, diagnostics, origin);
        if (!Files.isRegularFile(packFile) && !itemsAdder) {
            diagnostics.add(Diagnostic.error(origin,
                    "No " + PACK_FILE + ", so this is not a content pack. Add one, or move the folder out."));
            return;
        }

        DefinitionNode packNode;
        if (itemsAdder) {
            // An ItemsAdder pack folder is their contents/<namespace>/, which
            // has no pack.yml in it. Everything pack.yml would have said has a
            // sensible default, so one is not demanded of somebody whose only
            // mistake was having a pack from somewhere else.
            packNode = DefinitionNode.empty();
        } else {
            Optional<DefinitionNode> meta = readMap(packFile, relative(root, packFile), diagnostics);
            if (meta.isEmpty()) {
                return;
            }
            packNode = meta.get();
        }

        if (!packNode.bool("enabled").orElse(Boolean.TRUE)) {
            return;
        }

        ClaimResult claim = registration.claim(namespace, source);
        if (!claim.success()) {
            diagnostics.add(Diagnostic.error(origin, claimMessage(namespace, claim)));
            return;
        }
        Namespace claimed = claim.namespace().orElseThrow();

        List<String> bundles = validBundles(packNode, relative(root, packFile), diagnostics);
        packs.add(PackMeta.of(namespace, source,
                packNode.string("name").orElse(null),
                packNode.string("author").orElse(null),
                packNode.string("version").orElse(null),
                bundles));

        for (Path child : sortedChildren(folder, diagnostics, origin)) {
            String name = child.getFileName().toString();
            if (!Files.isDirectory(child)) {
                continue;
            }
            ContentKind kind = CATEGORIES.get(name);
            if (kind != null) {
                loadCategory(root, child, kind, claimed, definitions, diagnostics);
            } else if (!ASSET_FOLDERS.contains(name) && !name.startsWith(".")) {
                diagnostics.add(Diagnostic.warning(relative(root, child),
                        "Not a content category, so nothing in it was read. Expected one of " + CATEGORIES.keySet()));
            }
        }

        // ItemsAdder keeps its configs as loose files in the pack folder, so
        // they are read from where they already are. This runs for our own
        // packs too: dropping one of their files into a pack of yours works,
        // which is the whole point.
        loadItemsAdderConfigs(root, folder, claimed, definitions, diagnostics);
    }

    /** Whether this folder holds an ItemsAdder config, which is what makes it a pack of theirs. */
    private boolean holdsItemsAdderConfig(Path folder, List<Diagnostic> diagnostics, String origin) {
        for (Path child : list(folder, diagnostics, origin, path -> true)) {
            if (!Files.isDirectory(child) && isDefinitionFile(child)
                    && readMap(child, origin, new ArrayList<>()).map(ItemsAdder::looksLikeOne).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads every ItemsAdder config in a pack folder as definitions of ours.
     *
     * <p>Their namespace is taken from the folder rather than from the file's
     * own {@code info.namespace}: the folder is what this engine claimed, and
     * a file claiming a different one would put content somewhere the pack
     * does not own. A mismatch is worth saying out loud, because it means the
     * folder was renamed on the way in.
     */
    private void loadItemsAdderConfigs(Path root, Path folder, Namespace namespace,
                                       List<ContentDefinition> definitions,
                                       List<Diagnostic> diagnostics) {
        Set<ContentId> unregisteredSeen = new HashSet<>();
        for (Path file : list(folder, diagnostics, relative(root, folder), path -> true)) {
            if (Files.isDirectory(file) || !isDefinitionFile(file)) {
                continue;
            }
            String origin = relative(root, file);
            Optional<DefinitionNode> document = readMap(file, origin, diagnostics);
            if (document.isEmpty() || !ItemsAdder.looksLikeOne(document.get())) {
                continue;
            }
            ItemsAdder.namespaceOf(document.get()).ifPresent(declared -> {
                if (!declared.equals(namespace.name())) {
                    diagnostics.add(Diagnostic.warning(origin, "info.namespace",
                            "says " + declared + " but the folder is " + namespace.name()
                                    + ", and the folder wins. Rename the folder to " + declared
                                    + " if ids like " + declared + ":something are written elsewhere."));
                }
            });

            for (Map.Entry<ContentKind, Map<String, Object>> kind
                    : ItemsAdder.translate(document.get(), origin, diagnostics).entrySet()) {
                DefinitionNode translated = DefinitionNode.of(kind.getValue());
                for (String path : translated.keys()) {
                    define(kind.getKey(), namespace, translated, path, origin,
                            unregisteredSeen, definitions, diagnostics);
                }
            }
        }
    }

    private static String claimMessage(String namespace, ClaimResult claim) {
        switch (claim.reason()) {
            case ALREADY_CLAIMED:
                return "The namespace " + namespace + " is already loaded from "
                        + claim.heldBy().map(Enum::name).orElse("elsewhere")
                        + ". Rename this folder.";
            case RESERVED:
                return "The namespace " + namespace + " belongs to the game. Rename this folder.";
            default:
                return "The namespace " + namespace + " was refused.";
        }
    }

    private List<String> validBundles(DefinitionNode packNode, String origin, List<Diagnostic> diagnostics) {
        List<String> valid = new ArrayList<>();
        for (String bundle : packNode.strings("bundles")) {
            if (ContentId.isValidNamespace(bundle)) {
                valid.add(bundle);
            } else {
                diagnostics.add(Diagnostic.warning(origin, "bundles",
                        "Ignoring the bundle name " + bundle
                                + ". Use lowercase a-z, digits, and _ . - only."));
            }
        }
        // Empty falls through to PackMeta, which means the default bundle. A
        // pack whose only bundle name was a typo therefore still ships rather
        // than vanishing with one warning nobody read.
        return valid;
    }

    private void loadCategory(Path root, Path folder, ContentKind kind, Namespace namespace,
                              List<ContentDefinition> definitions, List<Diagnostic> diagnostics) {
        Set<ContentId> unregisteredSeen = new HashSet<>();
        for (Path file : sortedYamlFiles(folder, diagnostics, relative(root, folder))) {
            String origin = relative(root, file);
            Optional<DefinitionNode> document = readMap(file, origin, diagnostics);
            if (document.isEmpty()) {
                continue;
            }
            for (String path : document.get().keys()) {
                define(kind, namespace, document.get(), path, origin,
                        unregisteredSeen, definitions, diagnostics);
            }
        }
    }

    private void define(ContentKind kind, Namespace namespace, DefinitionNode document,
                        String path, String origin, Set<ContentId> unregisteredSeen,
                        List<ContentDefinition> definitions, List<Diagnostic> diagnostics) {
        if (!ContentId.isValidPath(path)) {
            diagnostics.add(Diagnostic.error(origin, path,
                    "Not a valid id. Use lowercase a-z, digits, and _ . - / only."));
            return;
        }

        Optional<ContentEntry> entry;
        if (UNREGISTERED.contains(kind)) {
            // Outside the id space; see UNREGISTERED. Still unique within its
            // own kind, so two recipes cannot share a name unnoticed.
            ContentId id = ContentId.of(namespace.name(), path).orElseThrow();
            if (!unregisteredSeen.add(id)) {
                diagnostics.add(Diagnostic.error(origin, path,
                        "Already defined in " + namespace.name() + "."));
                return;
            }
            entry = Optional.of(ContentEntry.of(id, kind, namespace.source()));
        } else {
            entry = namespace.define(kind, path);
        }

        if (entry.isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, path,
                    "Already defined in " + namespace.name() + ". Ids are unique across a pack, "
                            + "including across category folders."));
            return;
        }
        // The body is handed on untouched: the loader knows what an id is and
        // does not know what an item is, and a loader that thought it did would
        // change every time a layer gained a field.
        DefinitionNode body = document.node(path).orElse(DefinitionNode.empty());
        definitions.add(ContentDefinition.of(entry.get(), body, origin));
    }

    /**
     * Reads one definition file as a map, whichever of the two it is.
     *
     * <p>A file that parses to something other than a map is an error rather
     * than an empty result: a definition file holding a list is a mistake with
     * a fix, and silently loading nothing from it is how somebody spends an
     * afternoon wondering where their items went.
     */
    private Optional<DefinitionNode> readMap(Path file, String origin, List<Diagnostic> diagnostics) {
        if (isToml(file)) {
            return readToml(file, origin, diagnostics);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // SafeConstructor on purpose: content files come from strangers on
            // the internet as often as from the server owner, and the default
            // constructor instantiates arbitrary classes named in the document.
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object document = yaml.load(reader);
            if (document == null) {
                return Optional.of(DefinitionNode.empty());
            }
            if (!(document instanceof Map)) {
                diagnostics.add(Diagnostic.error(origin, "Expected a map of definitions at the top level."));
                return Optional.empty();
            }
            return Optional.of(DefinitionNode.of((Map<?, ?>) document));
        } catch (YAMLException e) {
            diagnostics.add(Diagnostic.error(origin, "Could not be parsed as YAML. " + firstLine(e)));
            return Optional.empty();
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error(origin, "Could not be read. " + firstLine(e)));
            return Optional.empty();
        }
    }

    /**
     * The same, for TOML.
     *
     * <p>A separate method rather than a branch inside the YAML one because
     * the failures are different: TOML's own most common mistake is a table
     * name with a slash in it, which the message says out loud, since
     * {@code [weapons/sword]} looks perfectly reasonable to somebody writing
     * an id.
     */
    private Optional<DefinitionNode> readToml(Path file, String origin, List<Diagnostic> diagnostics) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Optional.of(DefinitionNode.of(Toml.read(reader)));
        } catch (com.electronwill.nightconfig.core.io.ParsingException e) {
            diagnostics.add(Diagnostic.error(origin, "Could not be parsed as TOML. " + firstLine(e)
                    + " (an id with a / or a . in it has to be quoted: [\"weapons/sword\"])"));
            return Optional.empty();
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error(origin, "Could not be read. " + firstLine(e)));
            return Optional.empty();
        }
    }

    /**
     * SnakeYAML puts the line, the column and a caret diagram in its message,
     * which is genuinely useful and several lines long. The console gets the
     * first line and the rest is dropped rather than wrapped.
     */
    private static String firstLine(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private List<Path> sortedChildren(Path folder, List<Diagnostic> diagnostics, String origin) {
        return list(folder, diagnostics, origin, path -> true);
    }

    private List<Path> sortedYamlFiles(Path folder, List<Diagnostic> diagnostics, String origin) {
        // Recursive: items/weapons/swords.yml is fine, and the subfolder
        // contributes nothing to the id.
        List<Path> found = new ArrayList<>();
        for (Path child : list(folder, diagnostics, origin, path -> true)) {
            if (Files.isDirectory(child)) {
                found.addAll(sortedYamlFiles(child, diagnostics, origin));
            } else if (isDefinitionFile(child)) {
                found.add(child);
            }
        }
        return found;
    }

    /**
     * Whether this is a definition file.
     *
     * <p>Both spellings, side by side in one folder if somebody likes: they
     * produce the same map and the loader cannot tell them apart afterwards.
     */
    private static boolean isDefinitionFile(Path file) {
        return isYaml(file) || isToml(file);
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static boolean isToml(Path file) {
        return file.getFileName().toString().endsWith(".toml");
    }

    private List<Path> list(Path folder, List<Diagnostic> diagnostics, String origin,
                            java.util.function.Predicate<Path> filter) {
        try (Stream<Path> children = Files.list(folder)) {
            return children.filter(filter)
                    // Sorted by name rather than by whatever order the
                    // filesystem hands back, so a load is reproducible.
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error(origin, "Could not be listed. " + firstLine(e)));
            return List.of();
        }
    }

    /** A path a server owner can find, relative to the content root. */
    private static String relative(Path root, Path path) {
        try {
            return root.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.getFileName().toString();
        }
    }
}
