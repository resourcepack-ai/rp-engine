package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a Blockbench save file, so nobody has to remember to export. */
class BbModelTest {

    /** A one-pixel PNG, embedded the way Blockbench embeds one. */
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private static String project(String extra) {
        return "{"
                + "\"resolution\": {\"width\": 32, \"height\": 32},"
                + "\"textures\": [{\"name\": \"skin\", \"uuid\": \"tex-1\","
                + " \"source\": \"data:image/png;base64," + PNG_BASE64 + "\"}],"
                + "\"elements\": [{"
                + "  \"name\": \"body\", \"from\": [4, 0, 4], \"to\": [12, 16, 12],"
                + "  \"rotation\": [0, 30, 0], \"origin\": [8, 8, 8],"
                + "  \"faces\": {\"north\": {\"uv\": [0, 0, 16, 32], \"texture\": 0},"
                + "              \"up\": {\"uv\": [0, 0, 8, 8], \"texture\": \"tex-1\", \"rotation\": 90},"
                + "              \"down\": {\"uv\": [0, 0, 8, 8], \"texture\": null}}"
                + "}]"
                + extra
                + "}";
    }

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

    private Map<String, byte[]> zip() throws IOException {
        BuildReport report = new PackBuilder().with(new ItemAssets()).build(content, out, load());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(report.pack("main").orElseThrow().file());
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.put(entry.getName(), zin.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> entries, String path) {
        return new String(entries.get(path), StandardCharsets.UTF_8);
    }

    // ---- conversion ----------------------------------------------------

    @Test
    void convertsCubesAndTheirFaces() {
        JsonObject model = BbModel.convert(project("").getBytes(StandardCharsets.UTF_8), "mypack", "golem")
                .orElseThrow().model();

        JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals("[4.0,0.0,4.0]", element.get("from").toString());
        assertEquals("body", element.get("name").getAsString());
        assertTrue(element.getAsJsonObject("faces").has("north"));
        assertTrue(element.getAsJsonObject("faces").has("up"));
    }

    @Test
    void uvsAreScaledOutOfTheProjectsResolution() {
        JsonObject model = BbModel.convert(project("").getBytes(StandardCharsets.UTF_8), "mypack", "golem")
                .orElseThrow().model();

        // Blockbench UVs are in texture-resolution space (32 here) and
        // Minecraft wants 0-16, so 16 across a 32-wide texture is 8.
        JsonObject north = model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("north");
        assertEquals("[0.0,0.0,8.0,16.0]", north.get("uv").toString());
    }

    @Test
    void aFaceWithNoTextureIsDropped() {
        JsonObject model = BbModel.convert(project("").getBytes(StandardCharsets.UTF_8), "mypack", "golem")
                .orElseThrow().model();

        assertFalse(model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces").has("down"));
    }

    @Test
    void aFaceCanNameItsTextureByIndexOrByUuid() {
        JsonObject faces = BbModel.convert(project("").getBytes(StandardCharsets.UTF_8), "mypack", "golem")
                .orElseThrow().model()
                .getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");

        assertEquals("#0", faces.getAsJsonObject("north").get("texture").getAsString());
        assertEquals("#0", faces.getAsJsonObject("up").get("texture").getAsString());
    }

    @Test
    void aFaceRotationSurvivesOnlyIfTheGameAllowsIt() {
        JsonObject faces = BbModel.convert(project("").getBytes(StandardCharsets.UTF_8), "mypack", "golem")
                .orElseThrow().model()
                .getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");

        assertEquals(90, faces.getAsJsonObject("up").get("rotation").getAsInt());
        assertFalse(faces.getAsJsonObject("north").has("rotation"));
    }

    @Test
    void rotationIsSnappedToOneAxisAndOneOfFiveAngles() {
        JsonObject rotation = BbModel.convert(project("").getBytes(StandardCharsets.UTF_8), "mypack", "golem")
                .orElseThrow().model()
                .getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("rotation");

        // 30 degrees on y is not something Minecraft can express; 22.5 is the
        // nearest it has. That loses information and there is nowhere to put
        // it — the format simply cannot say the rest.
        assertEquals("y", rotation.get("axis").getAsString());
        assertEquals(22.5f, rotation.get("angle").getAsFloat());
    }

    @Test
    void anEmbeddedTextureComesOutAsAPng() {
        BbModel.Converted converted = BbModel.convert(
                project("").getBytes(StandardCharsets.UTF_8), "mypack", "golem").orElseThrow();

        byte[] png = converted.textures().get("golem");
        assertArrayEqualsPrefix(Base64.getDecoder().decode(PNG_BASE64), png);
        assertEquals("mypack:item/golem",
                converted.model().getAsJsonObject("textures").get("0").getAsString());
        assertEquals("mypack:item/golem",
                converted.model().getAsJsonObject("textures").get("particle").getAsString());
    }

    private static void assertArrayEqualsPrefix(byte[] expected, byte[] actual) {
        assertTrue(actual != null && actual.length == expected.length, "texture did not come out");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte " + i);
        }
    }

    @Test
    void animationsRideThroughUntouched() {
        String withAnimations = project(",\"animations\": [{\"name\": \"walk\", \"length\": 1.0}]");

        JsonObject model = BbModel.convert(withAnimations.getBytes(StandardCharsets.UTF_8), "mypack", "golem")
                .orElseThrow().model();

        // Nothing reads them yet. Dropping them would mean the rig work has to
        // go back to the source file to find what was already in hand.
        assertTrue(model.has("animations"));
        assertEquals("walk", model.getAsJsonArray("animations").get(0)
                .getAsJsonObject().get("name").getAsString());
    }

    @Test
    void somethingWithNoCubesIsNotAModel() {
        // A mesh has no from/to and cannot be a block-model element at all.
        String meshOnly = "{\"elements\": [{\"type\": \"mesh\", \"name\": \"blob\"}]}";

        assertTrue(BbModel.convert(meshOnly.getBytes(StandardCharsets.UTF_8), "mypack", "x").isEmpty());
        assertTrue(BbModel.convert("not json".getBytes(StandardCharsets.UTF_8), "mypack", "x").isEmpty());
        assertTrue(BbModel.convert(null, "mypack", "x").isEmpty());
    }

    // ---- building ------------------------------------------------------

    @Test
    void anItemCanNameABlockbenchProjectDirectly() throws IOException {
        write("mypack/items/a.yml", "golem:\n  material: PAPER\n  model: golem\n");
        write("mypack/assets/models/golem.bbmodel", project(""));

        Map<String, byte[]> entries = zip();

        assertTrue(text(entries, "assets/mypack/models/item/golem.json").contains("\"elements\""));
        assertTrue(entries.containsKey("assets/mypack/textures/item/golem.png"),
                "the art rides inside the project file");
    }

    @Test
    void theProjectFileItselfNeverShips() throws IOException {
        write("mypack/items/a.yml", "golem:\n  material: PAPER\n  model: golem\n");
        write("mypack/assets/models/golem.bbmodel", project(""));

        assertFalse(zip().containsKey("assets/mypack/models/golem.bbmodel"));
    }

    @Test
    void aProjectIsPreferredToAnExportedModel() throws IOException {
        write("mypack/items/a.yml", "golem:\n  material: PAPER\n  model: golem\n");
        write("mypack/assets/models/golem.bbmodel", project(""));
        write("mypack/assets/models/golem.json", "{\"textures\":{\"0\":\"item/old\"},\"elements\":[]}");

        // The project is the source of truth; an export beside it is a stale
        // copy of the same thing more often than it is a deliberate override.
        assertTrue(text(zip(), "assets/mypack/models/item/golem.json").contains("mypack:item/golem"));
    }

    @Test
    void anExportedModelStillWorks() throws IOException {
        write("mypack/items/a.yml", "sword:\n  material: PAPER\n  model: sword\n");
        write("mypack/assets/models/sword.json",
                "{\"textures\":{\"0\":\"item/sword\"},\"elements\":[]}");
        write("mypack/assets/textures/item/sword.png", "PNG");

        assertTrue(text(zip(), "assets/mypack/models/item/sword.json").contains("mypack:item/sword"));
    }

    @Test
    void aMissingModelNamesBothPlacesItCouldBe() throws IOException {
        write("mypack/items/a.yml", "golem:\n  material: PAPER\n  model: golem\n");

        BuildReport report = new PackBuilder().with(new ItemAssets()).build(content, out, load());

        String message = report.diagnostics(Diagnostic.Severity.ERROR).get(0).message();
        assertTrue(message.contains(".bbmodel"), message);
        assertTrue(message.contains(".json"), message);
    }

    @Test
    void aBbmodelBuildIsReproducible() throws IOException {
        write("mypack/items/a.yml", "golem:\n  material: PAPER\n  model: golem\n");
        write("mypack/assets/models/golem.bbmodel", project(""));

        BuildReport first = new PackBuilder().with(new ItemAssets()).build(content, out, load());
        BuildReport second = new PackBuilder().with(new ItemAssets()).build(content, out, load());

        assertEquals(first.pack("main").orElseThrow().sha1(), second.pack("main").orElseThrow().sha1());
    }
}
