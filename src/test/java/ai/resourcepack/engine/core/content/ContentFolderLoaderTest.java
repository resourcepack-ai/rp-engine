package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.PackMeta;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format in FORMAT.md, asserted. Every test here is a paragraph of that
 * document, and if the two disagree the document is right.
 */
class ContentFolderLoaderTest {

    @TempDir
    Path root;

    private ContentRegistryImpl registry;
    private ContentFolderLoader loader;

    @BeforeEach
    void setUp() {
        registry = new ContentRegistryImpl();
        loader = new ContentFolderLoader(registry);
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private LoadReport load() {
        return loader.load(root, ContentSource.AUTHORED);
    }

    private static List<String> ids(LoadReport report) {
        return report.definitions().stream().map(d -> d.id().toString()).sorted().toList();
    }

    private static List<String> messages(LoadReport report, Diagnostic.Severity severity) {
        return report.diagnostics(severity).stream().map(Diagnostic::toString).toList();
    }

    @Test
    void loadsAPackWithItems() throws IOException {
        write("mypack/pack.yml", "name: My Pack\nauthor: Steve\nversion: 1.0.0\n");
        write("mypack/items/gems.yml", "ruby:\n  material: DIAMOND\nsapphire:\n  material: DIAMOND\n");

        LoadReport report = load();

        assertFalse(report.hasErrors(), () -> messages(report, Diagnostic.Severity.ERROR).toString());
        assertEquals(List.of("mypack:ruby", "mypack:sapphire"), ids(report));
        assertTrue(registry.contains(ContentId.parse("mypack:ruby").orElseThrow(), ContentKind.ITEM));

        PackMeta pack = report.pack("mypack").orElseThrow();
        assertEquals("My Pack", pack.name().orElseThrow());
        assertEquals("Steve", pack.author().orElseThrow());
        assertEquals("1.0.0", pack.version().orElseThrow());
    }

    @Test
    void theFolderNameIsTheNamespace() throws IOException {
        // Even when pack.yml claims otherwise: the namespace is the thing a
        // server owner can see and rename.
        write("mypack/pack.yml", "namespace: somethingelse\n");
        write("mypack/items/a.yml", "ruby: {}\n");

        assertEquals(List.of("mypack:ruby"), ids(load()));
    }

    @Test
    void theCategoryFolderDecidesTheKind() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby: {}\n");
        write("mypack/blocks/a.yml", "ore: {}\n");
        write("mypack/README/a.yml", "chair: {}\n");
        write("mypack/models/a.yml", "golem: {}\n");
        write("mypack/emotes/a.yml", "wave: {}\n");
        write("mypack/sounds/a.yml", "chime: {}\n");
        write("mypack/fonts/a.yml", "icons: {}\n");
        write("mypack/screens/a.yml", "menu: {}\n");
        write("mypack/huds/a.yml", "mana: {}\n");
        write("mypack/recipes/a.yml", "ruby_block: {}\n");
        write("mypack/entities/a.yml", "guard: {}\n");
        write("mypack/liquids/a.yml", "acid: {}\n");

        LoadReport report = load();

        assertFalse(report.hasErrors());
        assertEquals(11, report.definitions().size());
        for (ContentKind kind : ContentKind.values()) {
            // FURNITURE has no folder: it is a block on an item, not a
            // category of its own. See ContentFolderLoader.CATEGORIES.
            int expected = kind == ContentKind.FURNITURE ? 0 : 1;
            assertEquals(expected, report.definitions(kind).size(),
                    () -> "wrong count for " + kind);
        }
    }

    @Test
    void subfoldersOrganiseAndDoNotReachTheId() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/weapons/swords.yml", "ruby_sword:\n  material: DIAMOND_SWORD\n");
        write("mypack/items/tools/picks.yml", "mining/ruby_pick: {}\n");

        // The subfolder contributes nothing; a slash in the KEY is what nests.
        assertEquals(List.of("mypack:mining/ruby_pick", "mypack:ruby_sword"), ids(load()));
    }

    @Test
    void theBodyIsHandedOnUntouched() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml",
                "ruby:\n  material: DIAMOND\n  stack: 16\n  glow: true\n  lore:\n    - one\n    - two\n");

        ContentDefinition ruby = load().definitions().get(0);

        assertEquals("DIAMOND", ruby.body().string("material").orElseThrow());
        assertEquals(16, ruby.body().integer("stack").orElseThrow());
        assertTrue(ruby.body().bool("glow").orElseThrow());
        assertEquals(List.of("one", "two"), ruby.body().strings("lore"));
        assertEquals("mypack/items/a.yml", ruby.origin(), "relative to the content root, so a console line names the pack");
    }

    @Test
    void assetsAreNeverWalkedForDefinitions() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby: {}\n");
        // A yml under assets/ is data for some other layer, not a definition.
        write("mypack/assets/geometry/notes.yml", "should_not_register: {}\n");

        LoadReport report = load();

        assertEquals(List.of("mypack:ruby"), ids(report));
        assertTrue(report.diagnostics().isEmpty(), "assets/ is expected, so it is not even a warning");
    }

    @Test
    void anUnknownFolderWarnsRatherThanFailing() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby: {}\n");
        write("mypack/README/notes.yml", "whatever: {}\n");

        LoadReport report = load();

        assertEquals(List.of("mypack:ruby"), ids(report));
        assertFalse(report.hasErrors());
        assertEquals(1, report.diagnostics(Diagnostic.Severity.WARNING).size());
    }

    // ---- bundles -------------------------------------------------------

    @Test
    void aPackShipsInMainByDefault() throws IOException {
        write("mypack/pack.yml", "name: My Pack\n");

        assertEquals(List.of("main"), load().pack("mypack").orElseThrow().bundles());
    }

    @Test
    void bundlesCanBeNamedAsAListOrAScalar() throws IOException {
        write("a/pack.yml", "bundles: [lobby, dungeon]\n");
        write("b/pack.yml", "bundles: lobby\n");

        LoadReport report = load();

        assertEquals(List.of("lobby", "dungeon"), report.pack("a").orElseThrow().bundles());
        assertEquals(List.of("lobby"), report.pack("b").orElseThrow().bundles());
    }

    @Test
    void aTypodBundleNameWarnsAndThePackStillShips() throws IOException {
        write("mypack/pack.yml", "bundles: [Lobby]\n");

        LoadReport report = load();

        assertFalse(report.hasErrors());
        assertEquals(1, report.diagnostics(Diagnostic.Severity.WARNING).size());
        assertEquals(List.of("main"), report.pack("mypack").orElseThrow().bundles());
    }

    // ---- errors --------------------------------------------------------

    @Test
    void aFolderWithoutPackYmlIsNotAPack() throws IOException {
        write("notapack/items/a.yml", "ruby: {}\n");

        LoadReport report = load();

        assertTrue(report.packs().isEmpty());
        assertTrue(report.definitions().isEmpty());
        assertTrue(report.hasErrors());
        assertTrue(registry.namespaces().isEmpty(), "a stray folder must not claim a namespace");
    }

    @Test
    void anIllegalFolderNameIsRefusedRatherThanLowercased() throws IOException {
        write("MyPack/pack.yml", "{}\n");
        write("MyPack/items/a.yml", "ruby: {}\n");

        LoadReport report = load();

        assertTrue(report.definitions().isEmpty());
        assertTrue(report.hasErrors());
        assertTrue(registry.namespaces().isEmpty());
    }

    @Test
    void enabledFalseSkipsThePackSilently() throws IOException {
        write("mypack/pack.yml", "enabled: false\n");
        write("mypack/items/a.yml", "ruby: {}\n");

        LoadReport report = load();

        assertTrue(report.packs().isEmpty());
        assertTrue(report.definitions().isEmpty());
        assertTrue(report.diagnostics().isEmpty(), "parking a pack is not a problem");
        assertTrue(registry.namespaces().isEmpty(), "a parked pack releases its namespace for another");
    }

    @Test
    void oneBadDefinitionDoesNotCostThePack() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby: {}\nRUBY_TWO: {}\nsapphire: {}\n");

        LoadReport report = load();

        assertEquals(List.of("mypack:ruby", "mypack:sapphire"), ids(report));
        assertEquals(1, report.diagnostics(Diagnostic.Severity.ERROR).size());
        assertTrue(messages(report, Diagnostic.Severity.ERROR).get(0).contains("items/a.yml: RUBY_TWO"));
    }

    @Test
    void aBrokenFileDoesNotCostTheOthers() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/broken.yml", "ruby:\n   - this is\n  not: valid\n");
        write("mypack/items/good.yml", "sapphire: {}\n");

        LoadReport report = load();

        assertEquals(List.of("mypack:sapphire"), ids(report));
        assertTrue(report.hasErrors());
    }

    @Test
    void aDefinitionFileThatIsNotAMapIsAnError() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "- ruby\n- sapphire\n");

        LoadReport report = load();

        assertTrue(report.definitions().isEmpty());
        assertTrue(messages(report, Diagnostic.Severity.ERROR).get(0).contains("map of definitions"));
    }

    @Test
    void idsAreUniqueAcrossCategoryFolders() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby: {}\n");
        write("mypack/blocks/a.yml", "ruby: {}\n");

        LoadReport report = load();

        assertEquals(List.of("mypack:ruby"), ids(report));
        // Category folders are walked in sorted order, so blocks/ gets there
        // first and items/ is the one that loses. Which one wins is not the
        // point; that it is the same one on every machine is.
        assertEquals(ContentKind.BLOCK, report.definitions().get(0).kind());
        assertEquals(1, report.diagnostics(Diagnostic.Severity.ERROR).size());
    }

    @Test
    void aFailingPackNeverStopsAWorkingOne() throws IOException {
        write("broken/items/a.yml", "ruby: {}\n");
        write("working/pack.yml", "{}\n");
        write("working/items/a.yml", "sapphire: {}\n");

        LoadReport report = load();

        assertEquals(List.of("working:sapphire"), ids(report));
        assertTrue(report.hasErrors());
    }

    @Test
    void twoPacksCannotClaimOneNamespace() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby: {}\n");
        loader.load(root, ContentSource.AUTHORED);

        // A second load without a release is a studio push landing on a name a
        // hand-authored pack already holds. First claim wins and the message
        // has to name who holds it.
        LoadReport second = loader.load(root, ContentSource.STUDIO);

        assertTrue(second.definitions().isEmpty());
        assertTrue(messages(second, Diagnostic.Severity.ERROR).get(0).contains("AUTHORED"));
    }

    @Test
    void reservedNamespacesAreRefused() throws IOException {
        write("minecraft/pack.yml", "{}\n");
        write("minecraft/items/a.yml", "ruby: {}\n");

        LoadReport report = load();

        assertTrue(report.definitions().isEmpty());
        assertTrue(messages(report, Diagnostic.Severity.ERROR).get(0).contains("belongs to the game"));
    }

    // ---- the boring edges ----------------------------------------------

    @Test
    void aMissingContentFolderIsNotAProblem() {
        LoadReport report = loader.load(root.resolve("nope"), ContentSource.AUTHORED);

        assertTrue(report.packs().isEmpty());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void nullArgumentsAnswerEmpty() {
        assertTrue(loader.load(null, ContentSource.AUTHORED).packs().isEmpty());
        assertTrue(loader.load(root, null).packs().isEmpty());
    }

    @Test
    void anEmptyFileIsFine() throws IOException {
        write("mypack/pack.yml", "");
        write("mypack/items/a.yml", "");

        LoadReport report = load();

        assertFalse(report.hasErrors());
        assertEquals(List.of("main"), report.pack("mypack").orElseThrow().bundles());
    }

    @Test
    void bothYamlExtensionsAreRead() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby: {}\n");
        write("mypack/items/b.yaml", "sapphire: {}\n");
        write("mypack/items/notes.txt", "ignored: {}\n");

        assertEquals(List.of("mypack:ruby", "mypack:sapphire"), ids(load()));
    }

    @Test
    void packsLoadInSortedOrder() throws IOException {
        write("zebra/pack.yml", "{}\n");
        write("alpha/pack.yml", "{}\n");

        // Reproducibility: once bundles are built from this, load order
        // reaching the output would make the zip differ between machines.
        assertEquals(List.of("alpha", "zebra"),
                load().packs().stream().map(PackMeta::namespace).toList());
    }
}
