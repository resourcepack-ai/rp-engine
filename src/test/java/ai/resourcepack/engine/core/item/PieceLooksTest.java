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

/**
 * A placed piece's other looks (a state's {@code model:}, a jukebox's
 * {@code playing-model:}, a {@code connects:} shape) that name a model with no
 * item of its own still have to reach the client on every era.
 */
class PieceLooksTest {

    private static final String MODEL = """
            {"textures": {"0": "item/lamp_on"},
             "elements": [{"from": [4, 0, 4], "to": [12, 8, 12],
               "faces": {"north": {"uv": [0, 0, 8, 8], "texture": "#0"}}}]}
            """;

    private static final ContentId LAMP_ON = ContentId.parse("mypack:lamp_on").orElseThrow();

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

    /** A lamp whose switched-on look is a model file and nothing else. */
    private void aLampWithAModelOnlyState() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", """
                lamp:
                  material: PAPER
                  place:
                    states:
                      - model: mypack:lamp_on
                        light: 15
                """);
        write("mypack/assets/textures/item/lamp.png", "PNG");
        write("mypack/assets/models/lamp_on.json", MODEL);
        write("mypack/assets/textures/item/lamp_on.png", "PNG");
    }

    @Test
    void aModelOnlyStateGetsAnItemDefinition() throws IOException {
        aLampWithAModelOnlyState();

        BuildReport report = new PackBuilder().with(new ItemAssets()).build(content, out, load());
        Map<String, String> entries = zip(report);

        // The display is pointed at mypack:lamp_on, so that definition has to
        // exist, and it has to reach the model the author drew.
        assertEquals("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/lamp_on\"}}",
                entries.get("assets/mypack/items/lamp_on.json"));
        assertTrue(entries.get("assets/mypack/models/item/lamp_on.json").contains("\"elements\""));
        assertTrue(report.diagnostics(Diagnostic.Severity.WARNING).isEmpty(),
                report.diagnostics(Diagnostic.Severity.WARNING).toString());
    }

    @Test
    void aModelOnlyStateIsNumberedOnThePiecesMaterialBelow1214() throws IOException {
        aLampWithAModelOnlyState();
        ModelNumbers numbers = new ModelNumbers(root.resolve("data").toFile());

        Map<String, String> entries = zip(new PackBuilder().with(new ItemAssets(numbers))
                .build(content, out, load()));

        // The build gave the look a number, and the wiring that puts a model on
        // a display asks the same allocator for it, so the two meet.
        int number = numbers.existing(LAMP_ON).orElseThrow();
        assertEquals(number, numbers.of(LAMP_ON));
        String paper = entries.get("assets/minecraft/models/item/paper.json");
        assertTrue(paper.contains("\"custom_model_data\": " + number + " }, \"model\": \"mypack:item/lamp_on\""),
                paper);
        assertTrue(entries.containsKey("assets/mypack/models/item/lamp_on.json"));
        assertFalse(entries.containsKey("assets/mypack/items/lamp_on.json"));
    }

    @Test
    void anItemLookOnAnotherMaterialIsAlsoNumberedOnThePiecesBelow1214() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", """
                lamp:
                  material: PAPER
                  place:
                    states:
                      - model: mypack:lamp_on
                lamp_on:
                  material: STICK
                  model: lamp_on
                """);
        write("mypack/assets/textures/item/lamp.png", "PNG");
        write("mypack/assets/models/lamp_on.json", MODEL);
        write("mypack/assets/textures/item/lamp_on.png", "PNG");
        ModelNumbers numbers = new ModelNumbers(root.resolve("data").toFile());

        Map<String, String> entries = zip(new PackBuilder().with(new ItemAssets(numbers))
                .build(content, out, load()));

        // The display carries paper, not a stick, so the number has to be on paper.
        int number = numbers.existing(LAMP_ON).orElseThrow();
        String needle = "\"custom_model_data\": " + number + " }, \"model\": \"mypack:item/lamp_on\"";
        assertTrue(entries.get("assets/minecraft/models/item/stick.json").contains(needle));
        assertTrue(entries.get("assets/minecraft/models/item/paper.json").contains(needle));
    }

    @Test
    void aLookWithNoItemAndNoModelIsNamedAtBuild() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", """
                lamp:
                  material: PAPER
                  place:
                    states:
                      - model: mypack:lamp_gone
                """);
        write("mypack/assets/textures/item/lamp.png", "PNG");

        BuildReport report = new PackBuilder().with(new ItemAssets()).build(content, out, load());

        List<Diagnostic> warnings = report.diagnostics(Diagnostic.Severity.WARNING);
        assertEquals(1, warnings.size(), warnings.toString());
        String message = warnings.get(0).message();
        assertTrue(message.contains("mypack:lamp_gone"), message);
        assertTrue(message.contains("assets/models/lamp_gone.json"), message);
        assertFalse(zip(report).containsKey("assets/mypack/items/lamp_gone.json"));
    }

    @Test
    void aModelOnlyJukeboxLookIsWrittenToo() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", """
                gramophone:
                  material: PAPER
                  place:
                    jukebox:
                      playing-model: mypack:lamp_on
                """);
        write("mypack/assets/textures/item/gramophone.png", "PNG");
        write("mypack/assets/models/lamp_on.json", MODEL);
        write("mypack/assets/textures/item/lamp_on.png", "PNG");

        Map<String, String> entries = zip(new PackBuilder().with(new ItemAssets()).build(content, out, load()));

        assertTrue(entries.containsKey("assets/mypack/items/lamp_on.json"));
    }
}
