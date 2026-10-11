package ai.resourcepack.engine.core.armor3d;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.pack.PackFiles;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hand-written set baked the way Studio's armour pack writer bakes one.
 *
 * <p>Every expected number below is worked by hand from that writer's
 * arithmetic, not read back from this one: the point is that a set built here
 * and the same set pushed from Studio put every part in the same place.
 */
class Armor3dBakerTest {

    /** A one-pixel PNG, embedded the way Blockbench embeds one. */
    private static final String PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private static String face(String uv) {
        return "{\"uv\": [" + uv + "], \"texture\": 0}";
    }

    private static String cube(String uuid, String from, String to, String rotation, String origin) {
        return "{\"name\": \"" + uuid + "\", \"type\": \"cube\", \"uuid\": \"" + uuid + "\","
                + " \"from\": [" + from + "], \"to\": [" + to + "],"
                + (rotation == null ? "" : " \"rotation\": [" + rotation + "],")
                + " \"origin\": [" + origin + "],"
                + " \"faces\": {\"north\": " + face("0, 0, 8, 8") + ", \"south\": " + face("8, 8, 16, 32")
                + ", \"down\": {\"uv\": [0, 0, 1, 1], \"texture\": null}}}";
    }

    /**
     * The fixture, in Blockbench 5.0's shape: group data in {@code groups},
     * the tree in {@code outliner} as uuids. A cape in a group of its own
     * inside the body, and a stray group holding a cube nothing wears.
     */
    static String santa() {
        return "{"
                + "\"meta\": {\"format_version\": \"5.0\", \"model_format\": \"free\"},"
                + "\"resolution\": {\"width\": 32, \"height\": 32},"
                + "\"elements\": ["
                + cube("helm", "-4.5, 24, -4.5", "4.5, 32.5, 4.5", null, "0, 28, 0") + ","
                + cube("plate", "-4.5, 12, -2.5", "4.5, 24, 2.5", null, "0, 18, 0") + ","
                + cube("pad", "-9, 21, -3", "-4, 24, 3", "0, 0, 30", "-6.5, 22.5, 0") + ","
                + cube("bracer", "-9, 14, -2.5", "-4, 16, 2.5", "0, 0, 22.5", "-6.5, 15, 0") + ","
                + cube("boot", "-0.1, 0, -2.5", "4.1, 3, 2.5", null, "2, 1.5, 0") + ","
                + cube("cape", "-4, 8, 2.5", "4, 23, 3", null, "0, 15, 2.75") + ","
                + cube("stray", "20, 0, 0", "21, 1, 1", null, "20.5, 0.5, 0.5")
                + "],"
                + "\"groups\": ["
                + "{\"name\": \"head\", \"origin\": [0, 24, 0], \"rotation\": [0, 0, 0], \"uuid\": \"g-head\"},"
                + "{\"name\": \"body\", \"origin\": [0, 24, 0], \"rotation\": [0, 0, 0], \"uuid\": \"g-body\"},"
                + "{\"name\": \"cape\", \"origin\": [0, 24, 0], \"rotation\": [0, 0, 0], \"uuid\": \"g-cape\"},"
                + "{\"name\": \"right_arm\", \"origin\": [-5, 22, 0], \"rotation\": [0, 0, 0], \"uuid\": \"g-rarm\"},"
                + "{\"name\": \"left_foot\", \"origin\": [1.9, 12, 0], \"rotation\": [0, 0, 0], \"uuid\": \"g-lfoot\"},"
                + "{\"name\": \"props\", \"origin\": [0, 0, 0], \"rotation\": [0, 0, 0], \"uuid\": \"g-props\"}"
                + "],"
                + "\"outliner\": ["
                + "{\"uuid\": \"g-head\", \"children\": [\"helm\"]},"
                + "{\"uuid\": \"g-body\", \"children\": [\"plate\", {\"uuid\": \"g-cape\", \"children\": [\"cape\"]}]},"
                + "{\"uuid\": \"g-rarm\", \"children\": [\"pad\", \"bracer\"]},"
                + "{\"uuid\": \"g-lfoot\", \"children\": [\"boot\"]},"
                + "{\"uuid\": \"g-props\", \"children\": [\"stray\"]}"
                + "],"
                + "\"textures\": [{\"name\": \"santa.png\", \"id\": \"0\", \"uuid\": \"t-0\","
                + " \"source\": \"data:image/png;base64," + PNG + "\"}]"
                + "}";
    }

    private static Armor3dBaker.Baked bake(String project, List<Diagnostic> diagnostics) {
        Armor3dBlockbench.Source source = Armor3dBlockbench.read(project.getBytes(StandardCharsets.UTF_8),
                "christmas/armor3d/costumes.yml", "santa", diagnostics).orElseThrow();
        return Armor3dBaker.bake("christmas", "santa", "Santa Suit", source);
    }

    private static JsonObject json(Armor3dBaker.Baked baked, String path) {
        return JsonParser.parseString(new String(baked.files().get(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final String MODELS = "assets/christmas/models/item/armor3d/santa/";

    @Test
    void theSetHasThePiecesItsBonesBelongTo() {
        Armor3dSet set = bake(santa(), new ArrayList<>()).set();

        assertEquals("christmas:santa", set.id());
        assertEquals("Santa Suit", set.name());
        assertEquals(List.of(Armor3dSet.Piece.HELMET, Armor3dSet.Piece.CHESTPLATE, Armor3dSet.Piece.BOOTS),
                new ArrayList<>(set.pieces().keySet()));
        assertTrue(set.authored());
        // Drawn by 26.1 - 26.2's own shaders, displays for everybody else.
        assertEquals(1, set.shaderProtocols().size());
        assertArrayEquals(new int[]{775, 776}, set.shaderProtocols().get(0));
        assertTrue(set.drawnBy(775));
        assertFalse(set.drawnBy(774));
    }

    @Test
    void theBodyPiecesWearEquipmentAssetsAndTheHelmetDoesNot() {
        Armor3dBaker.Baked baked = bake(santa(), new ArrayList<>());

        assertNull(baked.shaderProblem());
        assertEquals("christmas:armor3d/santa_chestplate",
                baked.set().piece(Armor3dSet.Piece.CHESTPLATE).orElseThrow().asset());
        assertEquals("christmas:armor3d/santa_boots",
                baked.set().piece(Armor3dSet.Piece.BOOTS).orElseThrow().asset());
        assertNull(baked.set().piece(Armor3dSet.Piece.HELMET).orElseThrow().asset());
        // The layer texture in the base pack, the assets only in the overlay.
        assertTrue(baked.files().containsKey(
                "assets/christmas/textures/entity/equipment/humanoid/armor3d/santa.png"));
        assertEquals(List.of("assets/christmas/equipment/armor3d/santa_boots.json",
                "assets/christmas/equipment/armor3d/santa_chestplate.json"),
                new ArrayList<>(baked.overlayFiles().keySet()));
    }

    @Test
    void displaysOffLeavesNoDisplaysAndNoDisplayModels() {
        Armor3dBlockbench.Source source = Armor3dBlockbench.read(santa().getBytes(StandardCharsets.UTF_8),
                "christmas/armor3d/costumes.yml", "santa", new ArrayList<>()).orElseThrow();
        Armor3dBaker.Baked baked = Armor3dBaker.bake("christmas", "santa", "Santa Suit", source, false);

        for (Armor3dSet.Worn worn : baked.set().pieces().values()) {
            assertTrue(worn.parts().isEmpty(), worn.piece().wire());
        }
        assertNotNull(baked.set().piece(Armor3dSet.Piece.CHESTPLATE).orElseThrow().asset());
        // The icons stay: a piece is still an item somebody holds.
        assertTrue(baked.files().containsKey(MODELS + "chestplate.json"));
        assertFalse(baked.files().containsKey(MODELS + "boots.left_foot.json"));
    }

    @Test
    void theHelmetIsTheGamesSoItHasNoDisplays() {
        Armor3dBaker.Baked baked = bake(santa(), new ArrayList<>());
        Armor3dSet.Worn helmet = baked.set().piece(Armor3dSet.Piece.HELMET).orElseThrow();

        assertEquals("armor3d:christmas:santa/helmet", helmet.item());
        assertTrue(helmet.parts().isEmpty());
        assertNull(helmet.asset());
        assertNull(helmet.bedrockSlot());

        // Wearer pixel (x, y, z) is model pixel (x + 8, y - 20, z + 8), and the
        // head transform is turned 180 and scaled 1.6.
        JsonObject model = json(baked, MODELS + "helmet.json");
        JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals("[3.5,4,3.5]", element.get("from").toString());
        assertEquals("[12.5,12.5,12.5]", element.get("to").toString());
        JsonObject head = model.getAsJsonObject("display").getAsJsonObject("head");
        assertEquals("[0,180,0]", head.get("rotation").toString());
        assertEquals("[1.6,1.6,1.6]", head.get("scale").toString());

        // gui: extent 9, so scale 14.5 / 9; the centre (0, 0.25, 0) scaled,
        // turned (15, 30, 0) and undone.
        JsonObject gui = model.getAsJsonObject("display").getAsJsonObject("gui");
        assertEquals("[1.6111,1.6111,1.6111]", gui.get("scale").toString());
        assertEquals("[0,-0.3891,-0.1042]", gui.get("translation").toString());
    }

    @Test
    void partsLandWhereStudiosDo() {
        Armor3dBaker.Baked baked = bake(santa(), new ArrayList<>());
        List<Armor3dSet.Part> parts = baked.set().piece(Armor3dSet.Piece.CHESTPLATE).orElseThrow().parts();

        // Bones in the order their first cube appears: body (the plate and,
        // through its subgroup, the cape), then the right arm. Per bone, the
        // square model first and then one per turn the format cannot hold.
        assertEquals(3, parts.size());

        Armor3dSet.Part body = parts.get(0);
        assertEquals("body", body.bone());
        assertEquals("armor3d:christmas:santa/chestplate.body", body.model());
        assertArrayEquals(new float[]{0, 24, 0}, body.anchor());
        assertNull(body.rotation());
        assertEquals(1f, body.scale());

        Armor3dSet.Part arm = parts.get(1);
        assertEquals("right_arm", arm.bone());
        assertEquals("armor3d:christmas:santa/chestplate.right_arm", arm.model());
        assertArrayEquals(new float[]{-5, 22, 0}, arm.anchor());
        assertNull(arm.rotation());

        // 30 degrees about z is not a block model angle: its own display,
        // standing at the cube's origin and carrying the turn.
        Armor3dSet.Part pad = parts.get(2);
        assertEquals("armor3d:christmas:santa/chestplate.right_arm.0", pad.model());
        assertArrayEquals(new float[]{-6.5f, 22.5f, 0}, pad.anchor());
        assertArrayEquals(new float[]{0, 0, 30}, pad.rotation());
        assertEquals(1f, pad.scale());

        Armor3dSet.Part boot = baked.set().piece(Armor3dSet.Piece.BOOTS).orElseThrow().parts().get(0);
        assertEquals("left_foot", boot.bone());
        assertArrayEquals(new float[]{1.9f, 12, 0}, boot.anchor());
    }

    @Test
    void theModelsAreBuiltRoundTheirAnchors() {
        Armor3dBaker.Baked baked = bake(santa(), new ArrayList<>());

        // About the body's pivot (0, 24, 0): shifted by (8, -16, 8).
        JsonObject body = json(baked, MODELS + "chestplate.body.json");
        assertEquals("[3.5,-4,5.5]", body.getAsJsonArray("elements").get(0).getAsJsonObject().get("from").toString());
        assertEquals("[4,-8,10.5]", body.getAsJsonArray("elements").get(1).getAsJsonObject().get("from").toString());
        assertFalse(body.has("display"));

        // A legal 22.5 is kept as an element rotation, about its origin moved
        // with the cube: (-6.5, 15, 0) + (13, -14, 8).
        JsonObject arm = json(baked, MODELS + "chestplate.right_arm.json");
        JsonObject bracer = arm.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals("[4,0,5.5]", bracer.get("from").toString());
        JsonObject rotation = bracer.getAsJsonObject("rotation");
        assertEquals("[6.5,1,8]", rotation.get("origin").toString());
        assertEquals("z", rotation.get("axis").getAsString());
        assertEquals(22.5, rotation.get("angle").getAsDouble());

        // The turned part is square about its own origin; the display turns it.
        JsonObject pad = json(baked, MODELS + "chestplate.right_arm.0.json")
                .getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals("[5.5,6.5,5]", pad.get("from").toString());
        assertEquals("[10.5,9.5,11]", pad.get("to").toString());
        assertFalse(pad.has("rotation"));

        // UVs out of a 32-wide texture into 0-16; a face with no texture is gone.
        JsonObject faces = pad.getAsJsonObject("faces");
        assertEquals("[0,0,4,4]", faces.getAsJsonObject("north").get("uv").toString());
        assertEquals("[4,4,8,16]", faces.getAsJsonObject("south").get("uv").toString());
        assertEquals("#0", faces.getAsJsonObject("north").get("texture").getAsString());
        assertFalse(faces.has("down"));

        // The icon approximates a free turn: 30 snaps to 22.5.
        JsonObject icon = json(baked, MODELS + "chestplate.json");
        assertTrue(icon.has("display"));
        boolean approximated = false;
        for (var element : icon.getAsJsonArray("elements")) {
            JsonObject turned = element.getAsJsonObject().getAsJsonObject("rotation");
            approximated |= turned != null && turned.get("angle").getAsDouble() == 22.5;
        }
        assertTrue(approximated);
    }

    @Test
    void everyModelHasAnItemDefinitionOfItsOwn() {
        Armor3dBaker.Baked baked = bake(santa(), new ArrayList<>());

        // 7 models (helmet, chestplate icon + 3 parts, boots icon + 1 part),
        // an item definition each, the texture, and the shader layer texture.
        assertEquals(16, baked.files().size());
        assertTrue(baked.files().containsKey("assets/christmas/textures/item/armor3d/santa.png"));
        JsonObject definition = json(baked, "assets/christmas/items/armor3d/santa/chestplate.right_arm.0.json");
        assertEquals("christmas:item/armor3d/santa/chestplate.right_arm.0",
                definition.getAsJsonObject("model").get("model").getAsString());
        assertEquals("christmas:armor3d/santa/chestplate.right_arm.0",
                baked.set().itemModel("armor3d:christmas:santa/chestplate.right_arm.0"));
    }

    @Test
    void groupsThatAreNotBonesAreNamed() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        bake(santa(), diagnostics);

        String said = diagnostics.toString();
        assertTrue(said.contains("cape"), said);
        assertTrue(said.contains("props"), said);
        assertTrue(said.contains("1 cube is not inside any bone"), said);
    }

    @Test
    void aModelTooBigForTheFormatIsShrunkAndGrownBack() {
        // A cape reaching from the shoulders to well below the feet: 40 pixels
        // under the body's pivot is past the format's -16.
        String project = santa().replace("[-4, 8, 2.5], \"to\": [4, 23, 3]", "[-4, -16, 2.5], \"to\": [4, 23, 3]");
        Armor3dSet.Part body = bake(project, new ArrayList<>()).set()
                .piece(Armor3dSet.Piece.CHESTPLATE).orElseThrow().parts().get(0);

        // Lowest y is -16 - 24 + 8 = -32, 40 from the centre: 24 / 40.
        assertEquals(0.6f, body.scale());
    }

    @Test
    void blockbenchsAnglesBecomeTheEnginesOrder() {
        double[] zyx = {10, 20, 30};
        double[] xyz = Armor3dBlockbench.fromBlockbench(zyx);
        double[][] expected = Armor3dBlockbench.zyx(zyx);
        double[][] actual = Armor3dBlockbench.xyz(xyz);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertEquals(expected[i][j], actual[i][j], 1e-4);
            }
        }
        // One axis is the same number in both, exactly.
        assertArrayEquals(new double[]{0, 45, 0}, Armor3dBlockbench.fromBlockbench(new double[]{0, 45, 0}));
        assertNull(Armor3dBlockbench.fromBlockbench(new double[]{0, 0, 0}));
    }

    @Test
    void theOlderInlineOutlinerIsReadToo() {
        String older = "{"
                + "\"resolution\": {\"width\": 16, \"height\": 16},"
                + "\"elements\": [" + cube("boot", "-4.1, 0, -2.5", "0.1, 3, 2.5", null, "-2, 1.5, 0") + "],"
                + "\"outliner\": [{\"name\": \"right_foot\", \"origin\": [-1.9, 12, 0], \"uuid\": \"g\","
                + " \"children\": [\"boot\"]}],"
                + "\"textures\": [{\"id\": \"0\", \"source\": \"data:image/png;base64," + PNG + "\"}]"
                + "}";
        Armor3dSet set = bake(older, new ArrayList<>()).set();

        Armor3dSet.Part boot = set.piece(Armor3dSet.Piece.BOOTS).orElseThrow().parts().get(0);
        assertEquals("right_foot", boot.bone());
        assertArrayEquals(new float[]{-1.9f, 12, 0}, boot.anchor());
    }

    @Test
    void aGroupTurnIsFoldedIntoItsCubes() {
        // The arm group turned 30 about z at the shoulder: its bracer, unturned
        // in its own right, comes out turned 30 about z, about the shoulder
        // moved nowhere (it IS the pivot) - a display of its own.
        String project = santa().replace(
                "{\"name\": \"right_arm\", \"origin\": [-5, 22, 0], \"rotation\": [0, 0, 0]",
                "{\"name\": \"right_arm\", \"origin\": [-6.5, 15, 0], \"rotation\": [0, 0, 7.5]");
        List<Armor3dSet.Part> parts = bake(project, new ArrayList<>()).set()
                .piece(Armor3dSet.Piece.CHESTPLATE).orElseThrow().parts();

        // The bracer's own 22.5 plus the group's 7.5, about the same point.
        boolean found = false;
        for (Armor3dSet.Part part : parts) {
            if (part.rotation() != null && Math.abs(part.rotation()[2] - 30) < 1e-3
                    && Math.abs(part.anchor()[0] + 6.5f) < 1e-3 && Math.abs(part.anchor()[1] - 15) < 1e-3) {
                found = true;
            }
        }
        assertTrue(found, parts.toString());
    }

    // ---- through the content folder and the build ----------------------------

    @TempDir
    Path root;

    @Test
    void aContentFolderSetIsLoadedAndBuilt() throws IOException {
        Path content = root.resolve("content");
        write(content, "christmas/pack.yml", "{}\n");
        write(content, "christmas/armor3d/costumes.yml",
                "santa:\n  name: \"Santa Suit\"\n  model: armor_santa\n");
        write(content, "christmas/assets/models/armor_santa.bbmodel", santa());

        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
        assertEquals(1, loaded.definitions(ContentKind.ARMOR3D).size(), loaded.diagnostics().toString());

        Armor3dDefinitions.Result parsed = Armor3dDefinitions.parse(loaded, PackFiles.folder(content));
        Armor3dSet set = parsed.sets().get("christmas:santa");
        assertEquals("Santa Suit", set.name());

        BuildReport report = new PackBuilder().with(new Armor3dAssets(parsed, true))
                .build(content, root.resolve("out"), loaded);
        Map<String, byte[]> zip = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(report.pack("main").orElseThrow().file());
             ZipInputStream entries = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                zip.put(entry.getName(), entries.readAllBytes());
            }
        }
        assertTrue(zip.containsKey("assets/christmas/items/armor3d/santa/helmet.json"), zip.keySet().toString());
        assertTrue(zip.containsKey(MODELS + "boots.left_foot.json"));
        // The source is consumed: a client cannot read a .bbmodel.
        assertFalse(zip.containsKey("assets/christmas/models/armor_santa.bbmodel"));

        // The shader range's overlay: the assets, the shaders, the licence, declared.
        String overlay = "rpai_armor3d_26_1/";
        assertTrue(zip.containsKey(overlay + "assets/christmas/equipment/armor3d/santa_chestplate.json"));
        assertFalse(zip.containsKey("assets/christmas/equipment/armor3d/santa_chestplate.json"));
        assertTrue(zip.containsKey(overlay + "assets/minecraft/shaders/core/entity.vsh"));
        assertTrue(zip.containsKey(overlay + "assets/minecraft/shaders/include/objmc_main.glsl"));
        assertTrue(zip.containsKey(overlay + "OBJCUBED-LICENSE.txt"));
        JsonObject mcmeta = JsonParser.parseString(new String(zip.get("pack.mcmeta"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject entry = mcmeta.getAsJsonObject("overlays").getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("rpai_armor3d_26_1", entry.get("directory").getAsString());
        assertEquals(84, entry.get("min_format").getAsInt());
        assertEquals(88, entry.get("max_format").getAsInt());
        assertFalse(entry.has("formats"));
    }

    @Test
    void aMissingModelIsAnErrorNamingWhereItLooked() throws IOException {
        Path content = root.resolve("content");
        write(content, "christmas/pack.yml", "{}\n");
        write(content, "christmas/armor3d/costumes.yml", "elf:\n  model: nowhere\n");

        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
        Armor3dDefinitions.Result parsed = Armor3dDefinitions.parse(loaded, PackFiles.folder(content));

        assertTrue(parsed.sets().isEmpty());
        assertTrue(parsed.diagnostics().toString().contains("assets/models/nowhere.bbmodel"),
                parsed.diagnostics().toString());
    }

    @Test
    void theCatalogueIsBothSourcesWithPushedOnesNamedStudio() {
        Armor3dSet authored = bake(santa(), new ArrayList<>()).set();
        Armor3dSet pushed = new Armor3dSet("a1b2", "Pushed", Map.of());
        Armor3dCatalogue catalogue = new Armor3dCatalogue(() -> Map.of("a1b2", pushed));
        catalogue.replaceAuthored(Map.of(authored.id(), authored));

        assertEquals(2, catalogue.all().size());
        assertEquals(authored, catalogue.find("christmas:santa"));
        assertEquals(pushed, catalogue.find("studio:a1b2"));
        assertEquals(pushed, catalogue.find("a1b2"));
        assertEquals("studio:a1b2", Armor3dCatalogue.label(pushed));
        assertEquals("christmas:santa", Armor3dCatalogue.label(authored));
    }

    private static void write(Path content, String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
