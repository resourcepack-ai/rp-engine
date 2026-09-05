package ai.resourcepack.engine.core.edit;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import ai.resourcepack.engine.core.vehicle.VehicleDefinitions;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole trip: a content folder in, a payload out, a pull back, files
 * changed on disk.
 *
 * <p>The pieces are tested on their own elsewhere; this is the one that would
 * notice them disagreeing — a path named one way on the way out and looked for
 * another way on the way back is invisible to every unit test and is exactly
 * how a save silently does nothing.
 *
 * <p>Every refusal here is also a test, because the refusals are the whole
 * safety argument: this is the only code in the plugin that overwrites a file
 * a human wrote.
 */
class EditRoundTripTest {

    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private static final String MODEL_JSON = "{"
            + "\"textures\": {\"0\": \"mypack:item/chair_wood\", \"particle\": \"mypack:item/chair_wood\"},"
            + "\"elements\": [{\"from\": [0, 0, 0], \"to\": [16, 4, 16],"
            + " \"faces\": {\"up\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#0\"}}}]"
            + "}";

    @TempDir
    Path content;

    @BeforeEach
    void setUp() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/furniture.yml", String.join("\n",
                "chair:",
                "  material: STICK",
                "  model: chair",
                "sign:",
                "  material: PAPER",
                ""));
        write("mypack/assets/models/chair.json", MODEL_JSON);
        writeBytes("mypack/assets/textures/item/chair_wood.png", png());
        writeBytes("mypack/assets/textures/item/sign.png", png());
    }

    private static byte[] png() {
        return Base64.getDecoder().decode(PNG_BASE64);
    }

    private void write(String path, String text) throws IOException {
        writeBytes(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(String path, byte[] bytes) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private String read(String path) throws IOException {
        return Files.readString(content.resolve(path), StandardCharsets.UTF_8);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private ItemInfo item(String id) {
        return ItemDefinitions.parse(load()).items().get(ContentId.parse(id).orElseThrow());
    }

    private VehicleInfo vehicle(String id) {
        return VehicleDefinitions.parse(load()).vehicles().get(ContentId.parse(id).orElseThrow());
    }

    private EditTarget modelTarget() throws EditException {
        return EditTargets.model(content, item("mypack:chair"), "RPEngine/test", "1.21.4");
    }

    /** A pull carrying the target's own files back unchanged, plus edits. */
    private static EditWire.Pull pull(EditTarget target, List<EditWire.File> files) {
        EditWire.Pull pull = new EditWire.Pull();
        pull.id = "test";
        pull.kind = target.request.kind;
        pull.revision = 1;
        pull.files = files;
        pull.removed = new ArrayList<>();
        return pull;
    }

    private static EditWire.File file(String path, String utf8) {
        EditWire.File out = new EditWire.File();
        out.path = path;
        out.encoding = "utf8";
        out.content = utf8;
        return out;
    }

    private static EditWire.File binary(String path, byte[] bytes) {
        EditWire.File out = new EditWire.File();
        out.path = path;
        out.encoding = "base64";
        out.content = Base64.getEncoder().encodeToString(bytes);
        return out;
    }

    // ---- what goes out ------------------------------------------------------

    @Test
    void aModelSessionCarriesTheModelAndTheArtItNames() throws Exception {
        EditTarget target = modelTarget();

        assertEquals("model", target.request.kind);
        assertEquals("mypack:chair", target.request.label);
        assertEquals("assets/models/chair.json", target.request.model.path);
        assertEquals("json", target.request.model.format);
        assertEquals(1, target.request.textures.size());
        assertEquals("mypack:item/chair_wood", target.request.textures.get(0).ref);
        assertEquals("assets/textures/item/chair_wood.png", target.request.textures.get(0).path);
        assertEquals("assets/textures/item", target.request.texturePrefix);
    }

    /**
     * Only art this pack ships. A vanilla reference resolves in the editor
     * without a file, and one naming another pack is not this session's to
     * read — or, later, to overwrite.
     */
    @Test
    void texturesFromElsewhereAreNotCarried() throws Exception {
        write("mypack/assets/models/chair.json", "{"
                + "\"textures\": {\"0\": \"minecraft:block/oak_planks\", \"1\": \"otherpack:item/x\"},"
                + "\"elements\": [{\"from\": [0, 0, 0], \"to\": [16, 4, 16],"
                + " \"faces\": {\"up\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#0\"}}}]}");

        assertTrue(modelTarget().request.textures.isEmpty());
    }

    @Test
    void aTextureSessionCarriesTheItemsOwnSprite() throws Exception {
        EditTarget target = EditTargets.texture(content, item("mypack:sign"), "RPEngine/test", "1.21.4");

        assertEquals("texture", target.request.kind);
        assertEquals(1, target.request.textures.size());
        assertEquals("assets/textures/item/sign.png", target.request.textures.get(0).path);
        assertEquals(Boolean.TRUE, target.request.textures.get(0).primary);
    }

    /**
     * The command never asks for this — an item with no {@code model:} is sent
     * to the texture editor instead — but the entry point refuses rather than
     * handing back a null target, which is a much worse way to find out.
     */
    @Test
    void anItemWithNoModelIsRefusedRatherThanNull() {
        EditException refused = assertThrows(EditException.class,
                () -> EditTargets.model(content, item("mypack:sign"), "RPEngine/test", "1.21.4"));
        assertTrue(refused.getMessage().contains("no model:"), refused.getMessage());
    }

    @Test
    void aVehicleSessionFindsTheFileItsEntryIsIn() throws Exception {
        write("mypack/vehicles/boats.yml", "dinghy:\n  seats:\n    - {role: driver, y: 0.4}\n");
        write("mypack/vehicles/cars.yml", String.join("\n",
                "hatchback:",
                "  model: mypack:chair",
                "  seats:",
                "    - {role: driver, y: 0.6}",
                ""));

        EditTarget target = EditTargets.vehicle(content, vehicle("mypack:hatchback"),
                item("mypack:chair"), "RPEngine/test", "1.21.4");

        assertEquals("cars.yml", target.vehicleFile.getFileName().toString());
        assertEquals("hatchback", target.vehicleKey);
        assertEquals("mypack:chair", target.vehicleModel);
        // The bodywork rides along so the seats have something to sit in.
        assertNotNull(target.request.model);
        assertEquals(1, target.request.textures.size());
    }

    // ---- what comes back ----------------------------------------------------

    @Test
    void anEditedModelAndTextureLandOnDisk() throws Exception {
        EditTarget target = modelTarget();
        String edited = MODEL_JSON.replace("[16, 4, 16]", "[16, 8, 16]");
        byte[] repainted = "not really a png but bytes are bytes".getBytes(StandardCharsets.UTF_8);

        EditApply.Applied applied = EditApply.apply(target, pull(target, List.of(
                file("assets/models/chair.json", edited),
                binary("assets/textures/item/chair_wood.png", repainted))));

        assertEquals(2, applied.written.size());
        assertTrue(read("mypack/assets/models/chair.json").contains("16, 8, 16"));
        assertEquals("not really a png but bytes are bytes",
                read("mypack/assets/textures/item/chair_wood.png"));
    }

    @Test
    void aNewTextureIsWrittenUnderTheItemsOwnFolder() throws Exception {
        EditTarget target = modelTarget();

        EditApply.apply(target, pull(target, List.of(
                file("assets/models/chair.json", MODEL_JSON),
                binary("assets/textures/item/chair_cushion.png", png()))));

        assertTrue(Files.exists(content.resolve("mypack/assets/textures/item/chair_cushion.png")));
    }

    /**
     * The refusal that matters most. Everything else here is about doing the
     * right thing; this is about a pull that names a file no session has any
     * business writing, whether through a bug at the far end or somebody in
     * the middle of the connection.
     */
    @Test
    void aPullNamingSomethingThatIsNotArtIsRefused() throws Exception {
        EditTarget target = modelTarget();

        assertThrows(EditException.class, () -> EditApply.apply(target, pull(target, List.of(
                file("assets/models/chair.json", MODEL_JSON),
                file("pack.yml", "owned: by you")))));
        assertEquals("{}\n", read("mypack/pack.yml"), "and it did not land");
    }

    @Test
    void aPullEscapingThePackIsRefused() throws Exception {
        EditTarget target = modelTarget();

        assertThrows(EditException.class, () -> EditApply.apply(target, pull(target, List.of(
                file("../../elsewhere.json", "no")))));
    }

    @Test
    void onlyAFileThisSessionSentCanBeDeleted() throws Exception {
        EditTarget target = modelTarget();
        EditWire.Pull pull = pull(target, List.of(file("assets/models/chair.json", MODEL_JSON)));
        pull.removed = List.of(
                "assets/textures/item/chair_wood.png",
                // Never sent, so never deleted, whatever the far end says.
                "assets/textures/item/sign.png");

        EditApply.Applied applied = EditApply.apply(target, pull);

        assertEquals(List.of("assets/textures/item/chair_wood.png"), applied.deleted);
        assertFalse(Files.exists(content.resolve("mypack/assets/textures/item/chair_wood.png")));
        assertTrue(Files.exists(content.resolve("mypack/assets/textures/item/sign.png")));
    }

    @Test
    void aPullWithNothingUsableInItIsRefusedRatherThanCountedAsASave() throws Exception {
        EditTarget target = modelTarget();
        assertThrows(EditException.class, () -> EditApply.apply(target, pull(target, List.of())));
    }

    // ---- a project, which never writes its art to disk -----------------------

    @Test
    void aProjectsArtGoesBackInsideTheProject() throws Exception {
        write("mypack/items/furniture.yml", "stool:\n  material: STICK\n  model: stool\n");
        writeBytes("mypack/assets/models/stool.bbmodel", project().getBytes(StandardCharsets.UTF_8));

        EditTarget target = EditTargets.model(content, item("mypack:stool"), "RPEngine/test", "1.21.4");
        assertEquals("bbmodel", target.request.model.format);
        assertEquals(1, target.request.textures.size());
        assertEquals(".project-textures/stool.png", target.request.textures.get(0).path);

        // Send it straight back, art and all.
        List<EditWire.File> files = new ArrayList<>();
        files.add(file("assets/models/stool.bbmodel", target.request.model.json.toString()));
        files.add(binary(".project-textures/stool.png", png()));
        EditApply.Applied applied = EditApply.apply(target, pull(target, files));

        assertEquals(List.of("assets/models/stool.bbmodel"), applied.written);
        assertFalse(Files.exists(content.resolve("mypack/.project-textures")),
                "a project's art has no file of its own and must not grow one");
        // And the file that was written is still a project the loader can read.
        JsonObject project = JsonParser.parseString(read("mypack/assets/models/stool.bbmodel"))
                .getAsJsonObject();
        assertTrue(project.has("elements"));
        assertTrue(project.getAsJsonArray("textures").get(0).getAsJsonObject()
                .get("source").getAsString().startsWith("data:image/png;base64,"));
    }

    private static String project() {
        return "{"
                + "\"resolution\": {\"width\": 16, \"height\": 16},"
                + "\"textures\": [{\"name\": \"stool\", \"uuid\": \"t1\","
                + " \"source\": \"data:image/png;base64," + PNG_BASE64 + "\"}],"
                + "\"elements\": [{\"name\": \"seat\", \"uuid\": \"c1\","
                + " \"from\": [0, 0, 0], \"to\": [16, 4, 16],"
                + " \"faces\": {\"up\": {\"uv\": [0, 0, 16, 16], \"texture\": 0}}}]"
                + "}";
    }
}
