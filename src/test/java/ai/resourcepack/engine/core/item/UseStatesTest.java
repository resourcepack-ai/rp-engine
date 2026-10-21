package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentId;
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

/** {@code pull:}, {@code charged:} and {@code charged-firework:}: a bow drawn and a crossbow loaded. */
class UseStatesTest {

    private static final String MODEL = "{\"textures\":{\"0\":\"mypack:item/bow\"},"
            + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{}}]}";

    @TempDir
    Path root;

    private Path content;
    private Path out;

    @BeforeEach
    void setUp() throws IOException {
        content = root.resolve("content");
        out = root.resolve("out");
        Files.createDirectories(content);
        write("mypack/pack.yml", "{}\n");
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private BuildReport build(ItemAssets assets) {
        return new PackBuilder().with(assets).build(content, out, load());
    }

    private static Map<String, String> zip(BuildReport report) throws IOException {
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

    private void textures(String... names) throws IOException {
        for (String name : names) {
            write("mypack/assets/textures/item/" + name + ".png", "PNG");
        }
    }

    @Test
    void aFlatBowIsDrawnBackThroughVanillasOwnThresholds() throws IOException {
        write("mypack/items/a.yml", """
                longbow:
                  material: BOW
                  pull: [longbow_pull_0, longbow_pull_1.png, longbow_pull_2]
                """);
        textures("longbow", "longbow_pull_0", "longbow_pull_1", "longbow_pull_2");

        BuildReport report = build(new ItemAssets());
        Map<String, String> zip = zip(report);

        assertEquals("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                        + "\"on_true\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:use_duration\","
                        + "\"scale\":0.05,\"entries\":["
                        + "{\"threshold\":0.65,\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/longbow_pull_1\"}},"
                        + "{\"threshold\":0.9,\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/longbow_pull_2\"}}],"
                        + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/longbow_pull_0\"}},"
                        + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/longbow\"}}}",
                zip.get("assets/mypack/items/longbow.json"));
        // A bare name on a flat item is a texture, as the item's own look is.
        assertEquals("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"mypack:item/longbow_pull_1\"}}",
                zip.get("assets/mypack/models/item/longbow_pull_1.json"));
        assertTrue(report.diagnostics(Diagnostic.Severity.WARNING).isEmpty(),
                report.diagnostics(Diagnostic.Severity.WARNING).toString());
    }

    @Test
    void aModelledCrossbowLoadsAndShowsItsArrowLookForARocketToo() throws IOException {
        write("mypack/items/a.yml", """
                repeater:
                  material: CROSSBOW
                  model: repeater
                  pull: [repeater_0, repeater_1, repeater_2]
                  charged: repeater_loaded
                """);
        for (String name : List.of("repeater", "repeater_0", "repeater_1", "repeater_2", "repeater_loaded")) {
            write("mypack/assets/models/" + name + ".json", MODEL);
        }
        textures("bow");

        String definition = zip(build(new ItemAssets())).get("assets/mypack/items/repeater.json");

        assertTrue(definition.startsWith("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:charge_type\","
                + "\"cases\":[{\"when\":\"arrow\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/repeater_charged\"}},"
                + "{\"when\":\"rocket\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/repeater_charged\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:condition\""), definition);
        assertTrue(definition.contains("\"property\":\"minecraft:crossbow/pull\",\"entries\":["
                + "{\"threshold\":0.58,"), definition);
        assertTrue(definition.contains("{\"threshold\":1,"), definition);
    }

    @Test
    void statesAreDrawnInTheOrderTheirThresholdsSayWhateverOrderTheyAreWrittenIn() throws IOException {
        write("mypack/items/a.yml", """
                staff:
                  material: BOW
                  pull:
                    - {at: 1.5, texture: staff_full}
                    - {at: 0, texture: staff_start}
                """);
        textures("staff", "staff_full", "staff_start");

        Map<String, String> zip = zip(build(new ItemAssets()));

        assertTrue(zip.get("assets/mypack/models/item/staff_pull_0.json").contains("staff_start"));
        assertTrue(zip.get("assets/mypack/models/item/staff_pull_1.json").contains("staff_full"));
        assertTrue(zip.get("assets/mypack/items/staff.json").contains("{\"threshold\":1.5,"));
    }

    @Test
    void aBowCannotBeLoadedAndIsToldSo() throws IOException {
        write("mypack/items/a.yml", """
                longbow:
                  material: BOW
                  charged: longbow_loaded
                """);
        textures("longbow", "longbow_loaded");

        BuildReport report = build(new ItemAssets());

        assertEquals("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/longbow\"}}",
                zip(report).get("assets/mypack/items/longbow.json"));
        List<Diagnostic> warnings = report.diagnostics(Diagnostic.Severity.WARNING);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).message().contains("Build it on CROSSBOW"), warnings.toString());
    }

    @Test
    void aMissingStateIsLeftOutAndTheRestStillDraw() throws IOException {
        write("mypack/items/a.yml", """
                longbow:
                  material: BOW
                  model: longbow
                  pull: [longbow_0, longbow_gone]
                """);
        write("mypack/assets/models/longbow.json", MODEL);
        write("mypack/assets/models/longbow_0.json", MODEL);
        textures("bow");

        BuildReport report = build(new ItemAssets());

        List<Diagnostic> errors = report.diagnostics(Diagnostic.Severity.ERROR);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().startsWith("pull: no model at"), errors.toString());
        String definition = zip(report).get("assets/mypack/items/longbow.json");
        assertTrue(definition.contains("\"entries\":[],\"fallback\":{\"type\":\"minecraft:model\","
                + "\"model\":\"mypack:item/longbow_pull_0\"}"), definition);
    }

    @Test
    void aFirstPersonModelStillWorksAlongsideTheDraw() throws IOException {
        write("mypack/items/a.yml", """
                longbow:
                  material: BOW
                  first-person: {parent: minecraft:item/generated, textures: {layer0: mypack:item/longbow}}
                  pull: [longbow_0]
                """);
        textures("longbow", "longbow_0");

        String definition = zip(build(new ItemAssets())).get("assets/mypack/items/longbow.json");

        assertTrue(definition.contains("\"on_false\":{\"type\":\"minecraft:select\","
                + "\"property\":\"minecraft:display_context\""), definition);
    }

    @Test
    void below1214ABowsStatesArePredicatesAfterItsNumber() throws IOException {
        write("mypack/items/a.yml", """
                longbow:
                  material: BOW
                  pull: [longbow_0, longbow_1, longbow_2]
                """);
        textures("longbow", "longbow_0", "longbow_1", "longbow_2");
        ModelNumbers numbers = new ModelNumbers(root.resolve("data").toFile());

        Map<String, String> zip = zip(build(new ItemAssets(numbers)));

        int n = numbers.existing(ContentId.parse("mypack:longbow").orElseThrow()).orElseThrow();
        String bow = zip.get("assets/minecraft/models/item/bow.json");
        // In this order, because the client takes the last predicate that matches.
        String expected = "    { \"predicate\": { \"custom_model_data\": " + n + " }, \"model\": \"mypack:item/longbow\" },\n"
                + "    { \"predicate\": { \"custom_model_data\": " + n + ", \"pulling\": 1 }, \"model\": \"mypack:item/longbow_pull_0\" },\n"
                + "    { \"predicate\": { \"custom_model_data\": " + n + ", \"pulling\": 1, \"pull\": 0.65 }, \"model\": \"mypack:item/longbow_pull_1\" },\n"
                + "    { \"predicate\": { \"custom_model_data\": " + n + ", \"pulling\": 1, \"pull\": 0.9 }, \"model\": \"mypack:item/longbow_pull_2\" }\n";
        assertTrue(bow.contains(expected), bow);
        assertFalse(zip.containsKey("assets/mypack/items/longbow.json"));
    }

    @Test
    void below1214OnlyABowOrACrossbowCanBeDrawn() throws IOException {
        write("mypack/items/a.yml", """
                wand:
                  material: STICK
                  pull: [wand_0]
                """);
        textures("wand", "wand_0");
        ModelNumbers numbers = new ModelNumbers(root.resolve("data").toFile());

        BuildReport report = build(new ItemAssets(numbers));

        assertFalse(zip(report).get("assets/minecraft/models/item/stick.json").contains("pulling"));
        assertTrue(report.diagnostics(Diagnostic.Severity.WARNING).stream()
                .anyMatch(d -> d.message().startsWith("pull: below Minecraft 1.21.4")));
    }
}
