package ai.resourcepack.engine.core.bedrock;

import ai.resourcepack.engine.api.ContentId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockContentTest {

    @TempDir
    Path root;

    private static byte[] png(int w, int h, int argb) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) image.setRGB(x, y, argb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Path javaZip(Map<String, byte[]> files) throws IOException {
        Path zip = root.resolve("main.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                out.write(file.getValue());
                out.closeEntry();
            }
        }
        return zip;
    }

    private static Map<String, byte[]> read(Path mcpack) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(mcpack))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) files.put(e.getName(), in.readAllBytes());
        }
        return files;
    }

    private static JsonObject json(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final ContentId RUBY = ContentId.of("mypack", "ruby").orElseThrow();
    private static final ContentId HELMET = ContentId.of("mypack", "ruby_helmet").orElseThrow();

    @Test
    void itemsSoundsIconsAndArmourCrossOver() throws IOException {
        Map<String, byte[]> java = new HashMap<>();
        java.put("assets/mypack/textures/item/ruby.png", png(16, 16, 0xFFFF0000));
        java.put("assets/mypack/textures/item/ruby_helmet.png", png(16, 16, 0xFFFF0000));
        java.put("assets/mypack/textures/entity/equipment/humanoid/ruby_helmet.png", png(64, 32, 0xFFAA0000));
        java.put("assets/mypack/sounds.json", ("{\"laser\":{\"category\":\"hostile\",\"sounds\":"
                + "[{\"name\":\"mypack:laser\",\"stream\":false}]}}").getBytes(StandardCharsets.UTF_8));
        java.put("assets/mypack/sounds/laser.ogg", new byte[] {1, 2, 3});
        java.put("assets/mypack/textures/font/heart.png", png(9, 9, 0xFFFF00FF));
        java.put("pack.png", png(8, 8, 0xFF00FF00));
        Path out = root.resolve("out");

        BedrockContent.Result result = BedrockContent.build(javaZip(java),
                List.of(new BedrockContent.Item(RUBY, "PAPER", "Ruby", "item/ruby", RUBY, null, null),
                        new BedrockContent.Item(HELMET, "PAPER", "Ruby Helmet", "item/ruby_helmet", HELMET, null, "head")),
                List.of(new BedrockContent.Icon(0xE003, "assets/mypack/textures/font/heart.png")),
                out, "main", "My Server");

        assertFalse(result.empty());
        assertEquals(2, result.items().size());
        assertEquals(1, result.sounds());
        assertEquals(1, result.icons());
        assertEquals(1, result.armour());

        BedrockContent.Registration ruby = result.items().get(0);
        assertEquals("rpengine:mypack_ruby", ruby.bedrockId());
        assertEquals("minecraft:paper", ruby.javaItem());
        assertEquals("mypack:ruby", ruby.javaModel());
        assertNull(ruby.legacyNumber());
        assertEquals("rpe_mypack_ruby", ruby.icon());
        assertEquals("head", result.items().get(1).armorSlot());

        Map<String, byte[]> pack = read(result.file());
        assertTrue(pack.containsKey("manifest.json"));
        assertTrue(pack.containsKey("pack_icon.png"));
        assertTrue(pack.containsKey("textures/items/rpe/mypack_ruby.png"));
        JsonObject atlas = json(pack.get("textures/item_texture.json"));
        assertEquals("textures/items/rpe/mypack_ruby",
                atlas.getAsJsonObject("texture_data").getAsJsonObject("rpe_mypack_ruby").get("textures").getAsString());

        // Geyser plays an unmapped Java sound under its own name, so the key is
        // the event as the engine plays it.
        JsonObject sounds = json(pack.get("sounds/sound_definitions.json")).getAsJsonObject("sound_definitions");
        JsonObject laser = sounds.getAsJsonObject("mypack:laser");
        assertEquals("hostile", laser.get("category").getAsString());
        assertEquals("sounds/rpe/mypack/laser", laser.getAsJsonArray("sounds").get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(pack.containsKey("sounds/rpe/mypack/laser.ogg"));

        // U+E003 is on page E0, row 0, column 3.
        BufferedImage page = ImageIO.read(new ByteArrayInputStream(pack.get("font/glyph_E0.png")));
        assertNotNull(page);
        assertEquals(256, page.getWidth());
        assertEquals(0xFFFF00FF, page.getRGB(3 * 16, 8));
        assertEquals(0, page.getRGB(0, 0) >>> 24);

        JsonObject attachable = json(pack.get("attachables/rpe_mypack_ruby_helmet.json"))
                .getAsJsonObject("minecraft:attachable").getAsJsonObject("description");
        assertEquals("rpengine:mypack_ruby_helmet", attachable.get("identifier").getAsString());
        assertEquals("geometry.humanoid.armor.helmet", attachable.getAsJsonObject("geometry").get("default").getAsString());
        assertTrue(pack.containsKey("textures/models/armor/rpe_mypack_ruby_helmet.png"));
    }

    @Test
    void anIconOnASheetIsCutOutForBedrock() {
        // Java's font splits the sheet itself; a Bedrock glyph page is laid
        // out by us, so the cell has to be the picture.
        BufferedImage sheet = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        sheet.setRGB(20, 3, 0xFF00FF00);
        BufferedImage cell = BedrockContent.cellOf(sheet,
                new BedrockContent.Icon(0xE000, "faces.png", 1, 2, 2));
        assertEquals(16, cell.getWidth());
        assertEquals(16, cell.getHeight());
        assertEquals(0xFF00FF00, cell.getRGB(4, 3));
        assertTrue(BedrockContent.cellOf(sheet, new BedrockContent.Icon(0xE000, "faces.png")) == sheet);
    }

    @Test
    void theSameContentBuildsTheSameVersion() throws IOException {
        Map<String, byte[]> java = Map.of("assets/mypack/textures/item/ruby.png", png(16, 16, 0xFFFF0000));
        List<BedrockContent.Item> items = List.of(new BedrockContent.Item(RUBY, "PAPER", "Ruby", "item/ruby", RUBY, 7, null));
        Path zip = javaZip(java);
        byte[] first = Files.readAllBytes(BedrockContent.build(zip, items, List.of(), root.resolve("a"), "main", "x").file());
        byte[] second = Files.readAllBytes(BedrockContent.build(zip, items, List.of(), root.resolve("b"), "main", "x").file());
        assertTrue(Arrays.equals(first, second));
    }

    @Test
    void belowOneTwentyOneFourAnItemIsKnownByItsNumber() throws IOException {
        Path zip = javaZip(Map.of("assets/mypack/textures/item/ruby.png", png(16, 16, 0xFFFF0000)));
        BedrockContent.Result result = BedrockContent.build(zip,
                List.of(new BedrockContent.Item(RUBY, "PAPER", "Ruby", "item/ruby", RUBY, 7, null)),
                List.of(), root.resolve("out"), "main", "x");
        assertNull(result.items().get(0).javaModel());
        assertEquals(7, result.items().get(0).legacyNumber());
    }

    @Test
    void aModelWithNoSpriteGetsAnIconDrawnFromIt() throws IOException {
        ContentId chair = ContentId.of("mypack", "chair").orElseThrow();
        ContentId crate = ContentId.of("mypack", "crate").orElseThrow();
        Map<String, byte[]> java = new HashMap<>();
        java.put("assets/mypack/textures/block/wood.png", png(16, 16, 0xFF8B5A2B));
        java.put("assets/mypack/models/item/chair.json", ("{\"textures\":{\"0\":\"mypack:block/wood\"},\"elements\":["
                + "{\"from\":[2,0,2],\"to\":[14,10,14],\"faces\":{\"north\":{\"texture\":\"#0\"},\"south\":{\"texture\":\"#0\"},"
                + "\"east\":{\"texture\":\"#0\"},\"west\":{\"texture\":\"#0\"},\"up\":{\"texture\":\"#0\"},\"down\":{\"texture\":\"#0\"}}}]}")
                .getBytes(StandardCharsets.UTF_8));
        // A block item that only names the game's own cube parent.
        java.put("assets/mypack/models/item/crate.json",
                "{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"mypack:block/wood\"}}".getBytes(StandardCharsets.UTF_8));
        BedrockContent.Result result = BedrockContent.build(javaZip(java),
                List.of(new BedrockContent.Item(chair, "PAPER", "Chair", "item/chair", chair, null, null),
                        new BedrockContent.Item(crate, "PAPER", "Crate", "item/crate", crate, null, null)),
                List.of(), root.resolve("out"), "main", "x");
        assertEquals(2, result.items().size());
        Map<String, byte[]> pack = read(result.file());
        for (String name : List.of("mypack_chair", "mypack_crate")) {
            BufferedImage icon = ImageIO.read(new ByteArrayInputStream(pack.get("textures/items/rpe/" + name + ".png")));
            assertEquals(ModelIcon.SIZE, icon.getWidth());
            // Lit three ways: the top and both visible sides differ.
            java.util.Set<Integer> colours = new java.util.HashSet<>();
            for (int y = 0; y < icon.getHeight(); y++) for (int x = 0; x < icon.getWidth(); x++) {
                int argb = icon.getRGB(x, y);
                if ((argb >>> 24) != 0) colours.add(argb);
            }
            assertEquals(3, colours.size(), name);
            assertEquals(0, icon.getRGB(0, 0) >>> 24, "the corners stay clear");
        }
    }

    @Test
    void anItemWithNothingToDrawFallsBackToItsMaterial() throws IOException {
        Map<String, byte[]> java = Map.of("assets/mypack/textures/entity/equipment/humanoid/ruby_helmet.png",
                png(64, 32, 0xFFAA0000));
        BufferedImage helmet = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        helmet.setRGB(5, 5, 0xFFFFD700);
        BedrockContent.Result result = BedrockContent.build(javaZip(java),
                new BedrockContent.Inputs(
                        List.of(new BedrockContent.Item(HELMET, "GOLDEN_HELMET", "Crown", "item/ruby_helmet", HELMET, null, "head")),
                        List.of(), List.of(), path -> path.equals("item/golden_helmet") ? helmet : null, null),
                root.resolve("out"), "main", "x");
        assertEquals(1, result.items().size());
        assertEquals(1, result.armour(), "worn armour still crosses without a sprite of its own");
        assertTrue(read(result.file()).containsKey("attachables/rpe_mypack_ruby_helmet.json"));
    }

    @Test
    void nothingToCrossBuildsNothingAndRemovesAStalePack() throws IOException {
        Path out = root.resolve("out");
        Files.createDirectories(out);
        Files.write(out.resolve("main.mcpack"), new byte[] {1});
        Path zip = javaZip(Map.of("assets/mypack/models/item/chair.json", "{}".getBytes(StandardCharsets.UTF_8)));
        BedrockContent.Result result = BedrockContent.build(zip,
                List.of(new BedrockContent.Item(RUBY, "PAPER", "Ruby", "item/ruby", RUBY, null, null)),
                List.of(), out, "main", "x");
        assertTrue(result.empty());
        assertFalse(Files.exists(out.resolve("main.mcpack")));
    }

    @Test
    void javaCategoriesMapOntoBedrocksOwn() {
        assertEquals("neutral", BedrockContent.bedrockCategory("master"));
        assertEquals("player", BedrockContent.bedrockCategory("voice"));
        assertEquals("record", BedrockContent.bedrockCategory("records"));
        assertEquals("hostile", BedrockContent.bedrockCategory("hostile"));
    }

    @Test
    void aWornModelGetsAMobEntityThatLoopsNothingOnItsOwn() throws IOException {
        ContentId golem = ContentId.of("mypack", "golem").orElseThrow();
        Map<String, byte[]> java = new HashMap<>();
        java.put("assets/mypack/textures/block/wood.png", png(16, 16, 0xFF8B5A2B));
        java.put("assets/mypack/models/item/golem.json", ("{\"textures\":{\"0\":\"mypack:block/wood\"},"
                + "\"elements\":[{\"from\":[4,0,4],\"to\":[12,16,12],\"faces\":{\"north\":{\"texture\":\"#0\"}}}],"
                + "\"groups\":[{\"name\":\"body\",\"origin\":[8,0,8],\"children\":[0]}],"
                + "\"animations\":[{\"name\":\"idle\",\"loop\":true,\"length\":1,\"animators\":{\"g:0\":"
                + "{\"rotation\":[{\"time\":0,\"value\":[0,0,0]},{\"time\":1,\"value\":[0,10,0]}]}}}]}")
                .getBytes(StandardCharsets.UTF_8));
        String path = "assets/mypack/models/item/golem.json";
        BedrockContent.Result result = BedrockContent.build(javaZip(java),
                new BedrockContent.Inputs(List.of(), List.of(),
                        // Placeable AND worn: one slot, the placed scale kept, the mob's turn used.
                        List.of(new BedrockContent.Model(golem, path, 2f), BedrockContent.Model.worn(golem, path, 90f)),
                        null, null),
                root.resolve("out"), "main", "x");
        assertEquals(Map.of("mypack:golem", 1), result.modelSlots());
        assertEquals(2f, result.modelScales().get("mypack:golem"));
        Map<String, byte[]> pack = read(result.file());

        JsonObject placed = JsonParser.parseString(new String(pack.get("entity/rpe_mypack_golem.entity.json"),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("minecraft:client_entity")
                .getAsJsonObject("description");
        assertTrue(placed.has("scripts"), "a placed model still loops on its own");

        JsonObject mob = JsonParser.parseString(new String(pack.get("entity/rpe_mypack_golem.mob.entity.json"),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("minecraft:client_entity")
                .getAsJsonObject("description");
        assertEquals(BedrockContent.mobIdentifier(1), mob.get("identifier").getAsString());
        assertFalse(mob.has("scripts"), "a mob plays what the engine sends, one at a time");
        assertEquals(BedrockContent.restAnimation("mypack_golem"),
                mob.getAsJsonObject("animations").get("rpe_rest").getAsString());
        assertEquals("geometry.rpe.mypack_golem.worn", mob.getAsJsonObject("geometry").get("default").getAsString());
        assertTrue(pack.containsKey("animations/rpe_mypack_golem.rest.animation.json"));

        JsonObject turned = JsonParser.parseString(new String(pack.get("models/entity/rpe_mypack_golem.worn.geo.json"),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject root = turned.getAsJsonArray("bones").get(0).getAsJsonObject();
        assertEquals("rpe_turn", root.get("name").getAsString());
        assertEquals(-90f, root.getAsJsonArray("rotation").get(1).getAsFloat());
        for (int i = 1; i < turned.getAsJsonArray("bones").size(); i++) {
            assertTrue(turned.getAsJsonArray("bones").get(i).getAsJsonObject().has("parent"), "every bone hangs off the turn");
        }
    }

    @Test
    void anUnturnedWornModelSharesThePlacedGeometry() throws IOException {
        ContentId golem = ContentId.of("mypack", "golem").orElseThrow();
        Map<String, byte[]> java = new HashMap<>();
        java.put("assets/mypack/textures/block/wood.png", png(16, 16, 0xFF8B5A2B));
        java.put("assets/mypack/models/item/golem.json", ("{\"textures\":{\"0\":\"mypack:block/wood\"},"
                + "\"elements\":[{\"from\":[4,0,4],\"to\":[12,16,12],\"faces\":{\"north\":{\"texture\":\"#0\"}}}]}")
                .getBytes(StandardCharsets.UTF_8));
        BedrockContent.Result result = BedrockContent.build(javaZip(java),
                new BedrockContent.Inputs(List.of(), List.of(),
                        List.of(BedrockContent.Model.worn(golem, "assets/mypack/models/item/golem.json", 0f)), null, null),
                root.resolve("out"), "main", "x");
        Map<String, byte[]> pack = read(result.file());
        assertFalse(pack.containsKey("models/entity/rpe_mypack_golem.worn.geo.json"));
        JsonObject mob = JsonParser.parseString(new String(pack.get("entity/rpe_mypack_golem.mob.entity.json"),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("minecraft:client_entity")
                .getAsJsonObject("description");
        assertEquals("geometry.rpe.mypack_golem", mob.getAsJsonObject("geometry").get("default").getAsString());
    }
}
