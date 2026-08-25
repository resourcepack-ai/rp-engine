package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 3D item models: a Blockbench export becoming the model the pack ships. */
class GeometryTest {

    /** What Blockbench writes for a one-cube item with one texture. */
    private static final String EXPORT = """
            {
              "credit": "Made with Blockbench",
              "textures": {"0": "item/sword", "particle": "item/sword"},
              "elements": [
                {"from": [7, 0, 7], "to": [9, 16, 9],
                 "faces": {"north": {"uv": [0, 0, 2, 16], "texture": "#0"}}}
              ],
              "display": {"thirdperson_righthand": {"rotation": [0, 0, 45]}}
            }
            """;

    @TempDir
    Path root;

    private Path content;
    private Path out;

    @BeforeEach
    void setUp() throws IOException {
        content = root.resolve("content");
        out = root.resolve("out");
        Files.createDirectories(content);
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private BuildReport build() {
        return new PackBuilder().with(new ItemAssets()).build(content, out, load());
    }

    private Map<String, String> zip(BuildReport report) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(report.pack("main").orElseThrow().file());
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zin.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private void aSwordWithAModel() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "sword:\n  material: DIAMOND_SWORD\n  model: sword\n");
        write("mypack/assets/models/sword.json", EXPORT);
        write("mypack/assets/textures/item/sword.png", "PNG");
    }

    // ---- rewriting -----------------------------------------------------

    @Test
    void aBareTexturePathIsRewrittenIntoThePacksNamespace() {
        Geometry.Model model = Geometry.read(EXPORT.getBytes(StandardCharsets.UTF_8), "mypack").orElseThrow();

        String json = new String(model.json(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"0\":\"mypack:item/sword\""), json);
        assertTrue(json.contains("\"particle\":\"mypack:item/sword\""), json);
        assertEquals(List.of("mypack:item/sword"), model.textures());
    }

    @Test
    void anExplicitNamespaceIsLeftAlone() {
        // Somebody who typed minecraft:item/stick meant it.
        String source = "{\"textures\":{\"0\":\"minecraft:item/stick\"}}";

        Geometry.Model model = Geometry.read(source.getBytes(StandardCharsets.UTF_8), "mypack").orElseThrow();

        assertTrue(new String(model.json(), StandardCharsets.UTF_8).contains("minecraft:item/stick"));
        assertEquals(List.of("minecraft:item/stick"), model.textures());
    }

    @Test
    void aSlotReferenceIsNotATexturePath() {
        // A leading # points at another slot in the same file. Rewriting it
        // would break the link rather than resolve it.
        String source = "{\"textures\":{\"0\":\"item/sword\",\"layer0\":\"#0\"}}";

        Geometry.Model model = Geometry.read(source.getBytes(StandardCharsets.UTF_8), "mypack").orElseThrow();

        String json = new String(model.json(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"layer0\":\"#0\""), json);
        assertEquals(List.of("mypack:item/sword"), model.textures());
    }

    @Test
    void everythingThatIsNotATextureGoesThroughUnread() {
        Geometry.Model model = Geometry.read(EXPORT.getBytes(StandardCharsets.UTF_8), "mypack").orElseThrow();

        // A converter that thinks it understands the whole format has to change
        // every time Mojang adds a field to it.
        String json = new String(model.json(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"elements\""), json);
        assertTrue(json.contains("\"thirdperson_righthand\""), json);
        assertTrue(json.contains("\"credit\""), json);
    }

    @Test
    void somethingThatIsNotJsonIsRefusedRatherThanGuessedAt() {
        assertTrue(Geometry.read("not json".getBytes(StandardCharsets.UTF_8), "mypack").isEmpty());
        assertTrue(Geometry.read("[1,2,3]".getBytes(StandardCharsets.UTF_8), "mypack").isEmpty());
        assertTrue(Geometry.read(null, "mypack").isEmpty());
        assertTrue(Geometry.read("{}".getBytes(StandardCharsets.UTF_8), null).isEmpty());
    }

    @Test
    void aModelWithNoTexturesIsFine() {
        assertTrue(Geometry.read("{\"parent\":\"item/generated\"}".getBytes(StandardCharsets.UTF_8), "mypack")
                .orElseThrow().textures().isEmpty());
    }

    // ---- building ------------------------------------------------------

    @Test
    void theModelFileBecomesTheItemsModel() throws IOException {
        aSwordWithAModel();

        Map<String, String> entries = zip(build());

        String model = entries.get("assets/mypack/models/item/sword.json");
        assertTrue(model.contains("\"elements\""), model);
        assertTrue(model.contains("mypack:item/sword"), model);
        // The item definition is unchanged: it points at the same place either
        // way, and only what lives there differs.
        assertEquals("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/sword\"}}",
                entries.get("assets/mypack/items/sword.json"));
    }

    @Test
    void aModelAnItemUsedDoesNotAlsoShipAsSource() throws IOException {
        aSwordWithAModel();

        Map<String, String> entries = zip(build());

        // Every player would otherwise download both the source and the thing
        // built from it.
        assertFalse(entries.containsKey("assets/mypack/models/sword.json"));
        assertTrue(entries.containsKey("assets/mypack/models/item/sword.json"));
    }

    @Test
    void aModelNothingReferencesIsLeftWhereItWasPut() throws IOException {
        aSwordWithAModel();
        write("mypack/assets/models/shared_parent.json", "{\"textures\":{\"0\":\"item/sword\"}}");

        Map<String, String> entries = zip(build());

        // The reason source removal is per-file rather than a blanket "never
        // ship assets/models/": a parent model that several models inherit
        // from is referenced from inside a model file, which nothing here
        // reads. Dropping it would break every model that inherits it, and the
        // failure would look like the child model being wrong.
        assertTrue(entries.containsKey("assets/mypack/models/shared_parent.json"));
        assertFalse(entries.containsKey("assets/mypack/models/sword.json"));
    }

    @Test
    void anItemWithNoModelIsStillAFlatSprite() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n");
        write("mypack/assets/textures/item/ruby.png", "PNG");

        assertTrue(zip(build()).get("assets/mypack/models/item/ruby.json")
                .contains("minecraft:item/generated"));
    }

    @Test
    void aMissingModelFileFallsBackToTheSprite() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "sword:\n  material: DIAMOND_SWORD\n  model: nope\n");
        write("mypack/assets/textures/item/sword.png", "PNG");

        BuildReport report = build();

        // An item that vanishes because its art is missing is a much worse
        // failure than one that renders wrong: the first loses whatever was in
        // somebody's chest.
        assertTrue(zip(report).get("assets/mypack/models/item/sword.json")
                .contains("minecraft:item/generated"));
        assertTrue(report.hasErrors());
        assertTrue(report.diagnostics(Diagnostic.Severity.ERROR).get(0).message().contains("assets/models/nope.json"));
    }

    @Test
    void aFileThatIsNotAModelSaysHowToExportOne() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "sword:\n  material: DIAMOND_SWORD\n  model: sword\n");
        write("mypack/assets/models/sword.json", "this is not json");
        write("mypack/assets/textures/item/sword.png", "PNG");

        BuildReport report = build();

        assertTrue(report.diagnostics(Diagnostic.Severity.ERROR).get(0).message().contains("Blockbench"));
    }

    @Test
    void aTextureTheModelNamesAndNobodyShippedIsReported() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "sword:\n  material: DIAMOND_SWORD\n  model: sword\n");
        write("mypack/assets/models/sword.json", EXPORT);

        BuildReport report = build();

        List<Diagnostic> warnings = report.diagnostics(Diagnostic.Severity.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).message().contains("assets/mypack/textures/item/sword.png"),
                warnings.get(0).message());
    }

    @Test
    void aModelBuildIsReproducible() throws IOException {
        aSwordWithAModel();

        assertEquals(build().pack("main").orElseThrow().sha1(),
                build().pack("main").orElseThrow().sha1());
    }
}
