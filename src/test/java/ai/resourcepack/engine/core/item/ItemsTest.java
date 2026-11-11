package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.armor3d.Armor3dSet;
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
 * The item layer, minus the one part that needs a server. Parsing and asset
 * emission are both here; building an {@code ItemStack} is not, because
 * Bukkit's item factory needs a running server and there is deliberately very
 * little logic on that side.
 */
class ItemsTest {

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

    private ItemDefinitions.Result items() {
        return ItemDefinitions.parse(load());
    }

    private static ItemInfo one(ItemDefinitions.Result result, String id) {
        return result.items().get(ContentId.parse(id).orElseThrow());
    }

    private Map<String, String> buildAndRead() throws IOException {
        return buildAndRead(new ItemAssets());
    }

    private Map<String, String> buildAndRead(ItemAssets assets) throws IOException {
        BuildReport report = new PackBuilder().with(assets).build(content, out, load());
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

    // ---- parsing -------------------------------------------------------

    @Test
    void readsTheFieldsAPackWrites() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/gems.yml",
                "ruby:\n"
                        + "  material: diamond\n"
                        + "  name: \"&cRuby\"\n"
                        + "  lore: [shiny, red]\n"
                        + "  stack: 16\n"
                        + "  glow: true\n"
                        + "  unbreakable: true\n");

        ItemInfo ruby = one(items(), "mypack:ruby");

        assertEquals("DIAMOND", ruby.material(), "uppercased, because a human writes it either way");
        assertEquals("&cRuby", ruby.name().orElseThrow());
        assertEquals(List.of("shiny", "red"), ruby.lore());
        assertEquals(16, ruby.maxStack().orElseThrow());
        assertTrue(ruby.glow());
        assertTrue(ruby.unbreakable());
    }

    @Test
    void anItemCanCarryAPermission() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml",
                "wand:\n  material: STICK\n  permission: mypack.wand\n"
                        + "plain:\n  material: STICK\n");

        ItemDefinitions.Result result = items();

        assertEquals("mypack.wand", one(result, "mypack:wand").permission().orElseThrow());
        // Empty rather than a default node: an item nobody restricted should
        // not need a grant to use.
        assertTrue(one(result, "mypack:plain").permission().isEmpty());
    }

    @Test
    void theTexturePathFallsOutOfTheId() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\nweapons/sword:\n  material: DIAMOND_SWORD\n");

        ItemDefinitions.Result result = items();

        // The whole id scheme: nothing is allocated, the path IS the id.
        assertEquals("item/ruby", one(result, "mypack:ruby").texture());
        assertEquals("item/weapons/sword", one(result, "mypack:weapons/sword").texture());
    }

    @Test
    void aTextureCanBeNamedInstead() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n  texture: gems/red\n");

        assertEquals("gems/red", one(items(), "mypack:ruby").texture());
    }

    @Test
    void anItemWithNoMaterialIsSkippedWithAnExplanation() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  name: Ruby\n");

        ItemDefinitions.Result result = items();

        assertTrue(result.items().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).message().contains("material"));
        assertEquals("mypack/items/a.yml", result.diagnostics().get(0).origin());
    }

    @Test
    void aMaterialThatIsNotAThingIsCaughtAtLoadTime() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: RUBY_ORE_THING\n");

        ItemDefinitions.Result result = items();

        // Better here, naming the file, than at give time telling a player
        // nothing happened.
        assertTrue(result.items().isEmpty());
        assertTrue(result.diagnostics().get(0).message().contains("RUBY_ORE_THING"));
    }

    @Test
    void whetherAMaterialCanBeHeldIsCheckedElsewhere() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: WATER\n");

        // WATER is a real Material and never an item, but Material.isItem()
        // resolves through Bukkit's registry and throws with no server. So
        // parsing accepts it and ItemDefinitions.checkGivable warns at runtime.
        // That split is what keeps this whole class testable.
        assertEquals(1, items().items().size());
        assertEquals("WATER", one(items(), "mypack:ruby").material());
    }

    @Test
    void aCopiedModelIsParsedAndAnythingElseIsRefused() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml",
                "copy:\n  material: DIAMOND\n  copy-model: mypack:ruby\n"
                        + "broken:\n  material: DIAMOND\n  copy-model: not an id\n");

        ItemDefinitions.Result result = items();

        assertEquals(ContentId.parse("mypack:ruby").orElseThrow(),
                one(result, "mypack:copy").copiedFrom().orElseThrow());
        assertEquals(ContentId.parse("mypack:ruby").orElseThrow(), one(result, "mypack:copy").modelId());
        assertFalse(result.items().containsKey(ContentId.parse("mypack:broken").orElseThrow()));
    }

    @Test
    void anItemThatCopiesNothingRendersThroughItsOwnId() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n");

        ItemInfo ruby = one(items(), "mypack:ruby");

        assertTrue(ruby.copiedFrom().isEmpty());
        assertEquals(ruby.id(), ruby.modelId());
    }

    @Test
    void anAbsurdStackSizeIsIgnoredWithAWarning() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n  stack: 500\n");

        ItemDefinitions.Result result = items();

        assertTrue(one(result, "mypack:ruby").maxStack().isEmpty());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    @Test
    void onlyItemsAreParsed() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n");
        write("mypack/blocks/a.yml", "ore:\n  material: STONE\n");

        assertEquals(1, items().items().size());
    }

    // ---- asset emission ------------------------------------------------

    @Test
    void anItemGetsADefinitionAndAModel() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n");
        write("mypack/assets/textures/item/ruby.png", "PNG");

        Map<String, String> zip = buildAndRead();

        assertEquals("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/ruby\"}}",
                zip.get("assets/mypack/items/ruby.json"));
        assertEquals("{\"parent\":\"minecraft:item/generated\","
                        + "\"textures\":{\"layer0\":\"mypack:item/ruby\"}}",
                zip.get("assets/mypack/models/item/ruby.json"));
        assertTrue(zip.containsKey("assets/mypack/textures/item/ruby.png"));
    }

    @Test
    void aFirstPersonModelIsDrawnOnlyInTheHoldersOwnView() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "rifle:\n  material: PAPER\n"
                + "  model: {parent: mypack:rifle, textures: {skin: mypack:item/rifle}}\n"
                + "  first-person: {parent: mypack:rifle_sights, textures: {skin: mypack:item/rifle}}\n");
        write("mypack/assets/textures/item/rifle.png", "PNG");

        Map<String, String> zip = buildAndRead();

        assertEquals("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                        + "\"cases\":[{\"when\":[\"firstperson_righthand\",\"firstperson_lefthand\"],"
                        + "\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/rifle_first_person\"}}],"
                        + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/rifle\"}}}",
                zip.get("assets/mypack/items/rifle.json"));
        assertTrue(zip.get("assets/mypack/models/item/rifle_first_person.json").contains("mypack:rifle_sights"));
        assertTrue(zip.get("assets/mypack/models/item/rifle.json").contains("\"mypack:rifle\""));
    }

    @Test
    void aGuiIconIsDrawnOnlyInAnInventory() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "kart:\n  material: PAPER\n"
                + "  model: {parent: mypack:kart, textures: {skin: mypack:item/kart}}\n"
                + "  gui: {texture: kart_icon}\n");
        write("mypack/assets/textures/item/kart.png", "PNG");
        write("mypack/assets/textures/item/kart_icon.png", "PNG");

        Map<String, String> zip = buildAndRead();

        assertEquals("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                        + "\"cases\":[{\"when\":\"gui\","
                        + "\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/kart_gui\"}}],"
                        + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/kart\"}}}",
                zip.get("assets/mypack/items/kart.json"));
        assertEquals("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"mypack:item/kart_icon\"}}",
                zip.get("assets/mypack/models/item/kart_gui.json"));
        assertTrue(zip.containsKey("assets/mypack/textures/item/kart_icon.png"));
    }

    @Test
    void aGuiIconSitsOnTopOfA3dArmourPiece() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "blood_helmet:\n  material: NETHERITE_HELMET\n  armor: head\n"
                + "  armor3d: blood\n  gui: {texture: blood_helmet_icon}\n"
                + "plain_helmet:\n  material: NETHERITE_HELMET\n  armor: head\n  armor3d: blood\n");
        write("mypack/assets/textures/item/blood_helmet_icon.png", "PNG");
        var helmet = Armor3dSet.Piece.HELMET;
        Armor3dSet set = new Armor3dSet("mypack:blood", "Blood", Map.of(helmet,
                new Armor3dSet.Worn(helmet, "armor3d:mypack:blood/helmet", List.of(), null)),
                List.of(), "mypack:armor3d/blood/");
        ItemAssets assets = new ItemAssets().withArmor3d(Map.of("mypack:blood", set));

        Map<String, String> zip = buildAndRead(assets);

        // The inventory gets the icon; everywhere else, the head included, the piece.
        assertEquals("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                        + "\"cases\":[{\"when\":\"gui\","
                        + "\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/blood_helmet_gui\"}}],"
                        + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/armor3d/blood/helmet\"}}}",
                zip.get("assets/mypack/items/blood_helmet.json"));
        assertEquals(java.util.Set.of(ContentId.parse("mypack:blood_helmet").orElseThrow()), assets.armourLooks(),
                "only the one with a look of its own keeps its item_model");
    }

    @Test
    void aGuiModelSitsBesideAFirstPersonOne() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "rifle:\n  material: PAPER\n"
                + "  model: {parent: mypack:rifle, textures: {skin: mypack:item/rifle}}\n"
                + "  first-person: {parent: mypack:rifle_sights, textures: {skin: mypack:item/rifle}}\n"
                + "  gui: {parent: minecraft:item/generated, textures: {layer0: mypack:item/rifle_icon}}\n");
        write("mypack/assets/textures/item/rifle.png", "PNG");
        write("mypack/assets/textures/item/rifle_icon.png", "PNG");

        String definition = buildAndRead().get("assets/mypack/items/rifle.json");

        assertTrue(definition.contains("\"cases\":[{\"when\":[\"firstperson_righthand\",\"firstperson_lefthand\"],"
                + "\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/rifle_first_person\"}},"
                + "{\"when\":\"gui\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/rifle_gui\"}}]"),
                definition);
    }

    @Test
    void anItemMadeOfPartsIsDrawnFromItsPartsModels() throws IOException {
        write("tools/pack.yml", "{}\n");
        write("tools/items/parts.yml", "oak_handle:\n  material: STICK\n"
                + "birch_handle:\n  material: STICK\n"
                + "iron_head:\n  material: PAPER\n"
                + "gold_head:\n  material: PAPER\n"
                + "pickaxe:\n  material: IRON_PICKAXE\n"
                + "  parts:\n    handle: [oak_handle, birch_handle]\n    head: [iron_head, tools:gold_head]\n");
        for (String part : List.of("oak_handle", "birch_handle", "iron_head", "gold_head")) {
            write("tools/assets/textures/item/" + part + ".png", "PNG");
        }

        ItemInfo pickaxe = one(items(), "tools:pickaxe");
        assertEquals(List.of("handle", "head"), List.copyOf(pickaxe.parts().keySet()));
        assertEquals(List.of(ContentId.parse("tools:iron_head").orElseThrow(),
                ContentId.parse("tools:gold_head").orElseThrow()), pickaxe.parts().get("head"));

        Map<String, String> zip = buildAndRead();
        assertEquals("{\"model\":{\"type\":\"minecraft:composite\",\"models\":["
                        + "{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\",\"index\":0,"
                        + "\"cases\":[{\"when\":\"tools:oak_handle\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"tools:item/oak_handle\"}},"
                        + "{\"when\":\"tools:birch_handle\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"tools:item/birch_handle\"}}],"
                        + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"tools:item/oak_handle\"}},"
                        + "{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\",\"index\":1,"
                        + "\"cases\":[{\"when\":\"tools:iron_head\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"tools:item/iron_head\"}},"
                        + "{\"when\":\"tools:gold_head\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"tools:item/gold_head\"}}],"
                        + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"tools:item/iron_head\"}}]}}",
                zip.get("assets/tools/items/pickaxe.json"));
        // Nothing of its own: no texture for it exists, and none is asked for.
        assertFalse(zip.containsKey("assets/tools/models/item/pickaxe.json"));
    }

    @Test
    void aPartThatIsNotAnItemIsReportedAndLeftOut() throws IOException {
        write("tools/pack.yml", "{}\n");
        write("tools/items/parts.yml", "iron_head:\n  material: PAPER\n"
                + "axe:\n  material: IRON_AXE\n  parts:\n    head: [iron_head, nothing_here]\n");
        write("tools/assets/textures/item/iron_head.png", "PNG");

        BuildReport report = new PackBuilder().with(new ItemAssets()).build(content, out, load());
        assertTrue(report.diagnostics().stream().map(Diagnostic::message)
                .anyMatch(message -> message.contains("tools:nothing_here")), report.diagnostics().toString());
    }

    @Test
    void anItemWithoutAFirstPersonModelKeepsItsPlainDefinition() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n");
        write("mypack/assets/textures/item/ruby.png", "PNG");

        assertEquals("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"mypack:item/ruby\"}}",
                buildAndRead().get("assets/mypack/items/ruby.json"));
    }

    @Test
    void aNestedIdKeepsItsPathThroughEveryFile() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "weapons/sword:\n  material: DIAMOND_SWORD\n");

        Map<String, String> zip = buildAndRead();

        assertTrue(zip.containsKey("assets/mypack/items/weapons/sword.json"));
        assertTrue(zip.get("assets/mypack/models/item/weapons/sword.json")
                .contains("mypack:item/weapons/sword"));
    }

    @Test
    void aCopiedModelGeneratesNothing() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml",
                "ruby:\n  material: DIAMOND\ncopy:\n  material: DIAMOND\n  copy-model: mypack:ruby\n");
        write("mypack/assets/textures/item/ruby.png", "PNG");

        Map<String, String> zip = buildAndRead();

        // Its files already exist under the id it points at, and writing them
        // again would be two definitions fighting over one path.
        assertFalse(zip.containsKey("assets/mypack/items/copy.json"));
        assertFalse(zip.containsKey("assets/mypack/models/item/copy.json"));
        assertTrue(zip.containsKey("assets/mypack/items/ruby.json"));
    }

    @Test
    void aTextureNobodyShippedIsNamedRatherThanDiscoveredInGame() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n");

        BuildReport report = new PackBuilder().with(new ItemAssets()).build(content, out, load());

        List<Diagnostic> warnings = report.diagnostics(Diagnostic.Severity.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).message().contains("textures/item/ruby.png"), warnings.get(0).message());
        // A warning, not an error: the item still works and still stacks, it
        // just renders as a missing texture until somebody adds the PNG.
        assertFalse(report.hasErrors());
    }

    @Test
    void anItemOnlyReachesTheBundleItShipsIn() throws IOException {
        write("alpha/pack.yml", "bundles: [lobby]\n");
        write("alpha/items/a.yml", "ruby:\n  material: DIAMOND\n");
        write("beta/pack.yml", "bundles: [arena]\n");
        write("beta/items/a.yml", "shield:\n  material: SHIELD\n");

        BuildReport report = new PackBuilder().with(new ItemAssets()).build(content, out, load());

        assertTrue(read(report, "lobby").containsKey("assets/alpha/items/ruby.json"));
        assertFalse(read(report, "lobby").containsKey("assets/beta/items/shield.json"));
        assertTrue(read(report, "arena").containsKey("assets/beta/items/shield.json"));
    }

    private static Map<String, String> read(BuildReport report, String bundle) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(report.pack(bundle).orElseThrow().file());
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zin.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    void generatedFilesAreReproducibleToo() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\nsapphire:\n  material: DIAMOND\n");

        String first = new PackBuilder().with(new ItemAssets())
                .build(content, out, load()).pack("main").orElseThrow().sha1();
        String second = new PackBuilder().with(new ItemAssets())
                .build(content, out, load()).pack("main").orElseThrow().sha1();

        assertEquals(first, second);
    }

    @Test
    void armorArtIsServedAtTheEquipmentPathAndSaysWhenTheServerCannotDrawIt() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", """
                crown:
                  material: PAPER
                  armor: head
                  armor-art: armour/crown_layer.png
                greaves:
                  material: PAPER
                  armor: legs
                  armor-art: armour/nothing_here
                shipped:
                  material: PAPER
                  armor: chest
                  armor-art: armour/crown_layer
                """);
        write("mypack/assets/textures/armour/crown_layer.png", "layer");
        write("mypack/assets/textures/entity/equipment/humanoid/shipped.png", "mine");

        BuildReport report = new PackBuilder().with(new ItemAssets(null, false)).build(content, out, load());
        Map<String, String> zip = read(report, "main");
        assertEquals("layer", zip.get("assets/mypack/textures/entity/equipment/humanoid/crown.png"));
        // One shipped at the equipment path on purpose is left alone.
        assertEquals("mine", zip.get("assets/mypack/textures/entity/equipment/humanoid/shipped.png"));
        List<Diagnostic> warnings = report.diagnostics(Diagnostic.Severity.WARNING);
        assertTrue(warnings.stream().anyMatch(w -> w.message().contains("armour/nothing_here.png")));
        assertEquals(1, warnings.stream().filter(w -> w.message().contains("nothing_here")
                || w.message().contains("humanoid_leggings/greaves")).count(), "one missing picture, said once");
        // Below 1.21.4 the art is packed, and the build names what will be
        // worn with vanilla art instead.
        assertTrue(warnings.stream().anyMatch(w -> w.message().contains("vanilla art")
                && w.where().orElse("").contains("mypack:crown")));
    }
}
