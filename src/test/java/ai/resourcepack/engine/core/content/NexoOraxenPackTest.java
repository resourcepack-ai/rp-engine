package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.ItemStats;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.api.RecipeInfo;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.core.font.IconDefinitions;
import ai.resourcepack.engine.core.block.BlockDefinitions;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.model.ModelDefinitions;
import ai.resourcepack.engine.core.recipe.RecipeDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import ai.resourcepack.engine.core.sound.SoundDefinitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Current Nexo/Oraxen item YAML, copied into a content folder without conversion. */
class NexoOraxenPackTest {
    @TempDir Path content;

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private static ContentId id(String id) {
        return ContentId.parse(id).orElseThrow();
    }

    private static boolean warned(LoadReport report, String id, String text) {
        return report.diagnostics().stream().anyMatch(d -> d.message().contains(text)
                && (id == null || d.where().map(id::equals).orElse(false)));
    }

    private static ModelInfo placed(LoadReport report, String id) {
        var items = ItemDefinitions.parse(report).items();
        return ModelDefinitions.parse(report, items).model().get(id(id));
    }

    @Test
    void currentPluginItemsLoadWithoutPackYml() throws IOException {
        write("nexo_pack/items.yml", """
                ruby:
                  itemname: "Ruby"
                  material: DIAMOND
                  Pack:
                    model: nexo_pack:item/ruby
                sticker:
                  displayname: "Sticker"
                  material: PAPER
                  Pack:
                    texture: nexo_pack:item/sticker
                """);

        var items = ItemDefinitions.parse(load()).items();
        ItemInfo ruby = items.get(ContentId.parse("nexo_pack:ruby").orElseThrow());
        ItemInfo sticker = items.get(ContentId.parse("nexo_pack:sticker").orElseThrow());
        assertEquals("Ruby", ruby.name().orElseThrow());
        assertEquals("item/ruby", ruby.model().orElseThrow());
        assertEquals("item/sticker", sticker.texture());
    }

    @Test
    void furnitureBecomesAPlacedModel() throws IOException {
        write("oraxen_pack/items.yml", """
                chair:
                  itemname: Chair
                  material: PAPER
                  Pack:
                    model: oraxen_pack:item/chair
                  Mechanics:
                    furniture: {}
                """);

        LoadReport report = load();
        assertTrue(ItemDefinitions.parse(report).items().containsKey(ContentId.parse("oraxen_pack:chair").orElseThrow()));
        assertTrue(report.definitions().stream().anyMatch(d -> d.id().toString().equals("oraxen_pack:chair")
                && d.body().node("place").isPresent()));
    }

    @Test
    void aForeignAssetIsNamedAndNotMisresolved() throws IOException {
        write("pack/items.yml", """
                borrowed:
                  material: PAPER
                  Pack:
                    model: somebody_else:item/thing
                """);
        assertTrue(load().diagnostics().stream().anyMatch(d -> d.message().contains("outside this pack's namespace")));
    }

    @Test
    void componentOnlyNexoItemsAndFurnitureUseTheEngineForms() throws IOException {
        write("pack/items.yml", """
                modern:
                  itemname: Modern
                  material: PAPER
                  Components:
                    item_model: pack:item/modern
                chair:
                  material: PAPER
                  Pack:
                    model: pack:item/chair
                  Mechanics:
                    furniture:
                      barriers:
                        - 0,0,0
                      seat: 0.6
                """);
        LoadReport report = load();
        ItemInfo modern = ItemDefinitions.parse(report).items().get(ContentId.parse("pack:modern").orElseThrow());
        assertEquals("item/modern", modern.model().orElseThrow());
        assertTrue(report.definitions().stream().anyMatch(d -> d.id().toString().equals("pack:chair")
                && d.body().node("place").isPresent()));
    }

    @Test
    void customBlocksBecomeRpEngineBlocksInsteadOfASecondItemId() throws IOException {
        write("pack/blocks.yml", """
                ruby_ore:
                  material: PAPER
                  Pack:
                    model: pack:ruby_ore
                  Mechanics:
                    custom_block:
                      type: NOTEBLOCK
                      model: pack:ruby_ore
                      hardness: 3
                      drop:
                        best_tool: PICKAXE
                """);
        var block = BlockDefinitions.parse(load()).blocks().get(ContentId.parse("pack:ruby_ore").orElseThrow());
        assertEquals("ruby_ore", block.model());
        assertEquals(3f, block.hardness());
        assertEquals("pickaxe", block.tool().orElseThrow());
    }

    @Test
    void pluginRecipeFoldersAreTranslatedRatherThanTreatedAsNativeYaml() throws IOException {
        write("pack/items.yml", """
                ruby:
                  material: PAPER
                  Pack: { model: pack:ruby }
                """);
        write("pack/recipes/shaped.yml", """
                ruby_pick:
                  result: { nexo_item: ruby }
                  ingredients:
                    A: { minecraft_type: DIAMOND }
                    B: { minecraft_type: STICK }
                  shape: ["AAA", " B ", " B "]
                """);
        write("pack/recipes/furnace.yml", """
                baked_ruby:
                  result: { minecraft_type: DIAMOND, amount: 2 }
                  input: { oraxen_item: ruby }
                  cookingTime: 80
                  experience: 1.5
                """);

        var recipes = RecipeDefinitions.parse(load()).recipes();
        var shaped = recipes.get(ContentId.parse("pack:ruby_pick").orElseThrow());
        var furnace = recipes.get(ContentId.parse("pack:baked_ruby").orElseThrow());
        assertEquals("pack:ruby", shaped.result());
        assertEquals("DIAMOND", shaped.keys().get("A"));
        assertEquals("pack:ruby", furnace.ingredients().get(0));
        assertEquals(2, furnace.amount());
        assertEquals(80, furnace.cookingTime());
    }

    @Test
    void staticGlyphsBecomeIconsGifsExplainThemselvesAndSheetsSplit() throws IOException {
        write("pack/items.yml", """
                ruby:
                  material: PAPER
                  Pack: { model: pack:ruby }
                """);
        write("pack/glyphs/icons.yml", """
                ruby:
                  texture: pack:ui/ruby.png
                  ascent: 9
                  height: 11
                animated:
                  gif: pack:gifs/animated.gif
                grid:
                  texture: pack:ui/grid
                  rows: 2
                  columns: 2
                """);

        var icons = IconDefinitions.parse(load()).icons();
        var ruby = icons.get(ContentId.parse("pack:ruby").orElseThrow());
        assertEquals("ui/ruby", ruby.file());
        assertEquals(11, ruby.height());
        assertEquals(9, ruby.ascent());
        // The 2x2 sheet is four icons, one per cell; the GIF has no sheet to cut.
        assertEquals(5, icons.size());
        assertEquals(4, icons.get(ContentId.parse("pack:grid_4").orElseThrow()).cell());
        assertTrue(load().diagnostics().stream().anyMatch(d -> d.message().contains("Animated GIF glyphs")));
        assertTrue(load().diagnostics().stream().anyMatch(d -> d.message().contains("multi-bitmap glyph")));
    }

    @Test
    void normalNestedNexoItemsFolderIsReadThroughTheTranslator() throws IOException {
        write("pack/items/tools/ruby.yml", """
                ruby_sword:
                  itemname: Ruby Sword
                  material: DIAMOND_SWORD
                  Pack: { model: pack:item/ruby_sword }
                  AttributeModifiers:
                    - attribute: ATTACK_DAMAGE
                      amount: 7.5
                      operation: ADD_NUMBER
                      slot: MAINHAND
                """);

        var sword = ItemDefinitions.parse(load()).items().get(ContentId.parse("pack:ruby_sword").orElseThrow());
        assertEquals("Ruby Sword", sword.name().orElseThrow());
        assertEquals(1, sword.stats().modifiers().size());
        assertEquals("attack_damage", sword.stats().modifiers().get(0).attribute());
        // Detailed form, so the operation is not read as a second attribute.
        assertEquals("add", sword.stats().modifiers().get(0).operation());
        assertEquals("hand", sword.stats().modifiers().get(0).slot());
    }

    // ---- modern lowercase Oraxen --------------------------------------------

    @Test
    void modernLowercaseOraxenItemsAreRecognisedAndTranslated() throws IOException {
        write("oraxen_pack/items/armors.yml", """
                emerald_helmet:
                  displayname: "<gradient:#89E59D:#37C6BA>Emerald Helmet"
                  material: PAPER
                  lore:
                    - "Gives 1 extra heart"
                  unbreakable: true
                  Enchantments:
                    minecraft:protection: 2
                  components:
                    max_stack_size: 1
                    durability:
                      value: 437
                      damage_entity_hit: true
                    equippable:
                      slot: HEAD
                      model: oraxen:emerald
                    enchantment_glint_override: true
                    food: { nutrition: 4, saturation: 2.5, can_always_eat: true }
                    fire_resistant: true
                  AttributeModifiers:
                    - { attribute: MAX_HEALTH, amount: 2, operation: 0, slot: HEAD }
                    - { attribute: GENERIC_ARMOR_TOUGHNESS, amount: 0.1, operation: 1, slot: HAND }
                  pack:
                    generate_model: true
                    parent_model: "item/generated"
                    textures:
                      - default/armors/emerald_helmet
                """);

        LoadReport report = load();
        ItemInfo helmet = ItemDefinitions.parse(report).items().get(id("oraxen_pack:emerald_helmet"));
        assertEquals("default/armors/emerald_helmet", helmet.texture());
        assertEquals("head", helmet.armor().orElseThrow());
        assertEquals(1, helmet.maxStack().orElseThrow());
        assertEquals(437, helmet.stats().maxDamage().orElseThrow());
        assertTrue(helmet.glow());
        assertTrue(helmet.unbreakable());
        assertEquals(2, helmet.stats().enchantments().get("protection"));
        ItemStats.Food food = helmet.stats().food().orElseThrow();
        assertEquals(4, food.nutrition());
        assertTrue(food.alwaysEdible());

        ItemStats.Modifier health = helmet.stats().modifiers().get(0);
        assertEquals("max_health", health.attribute());
        assertEquals("add", health.operation());
        assertEquals("head", health.slot());
        ItemStats.Modifier toughness = helmet.stats().modifiers().get(1);
        assertEquals("armor_toughness", toughness.attribute());
        assertEquals("multiply_base", toughness.operation());
        assertEquals("hand", toughness.slot());

        // The art cannot be moved by an importer, so it says where it goes,
        // and the component nothing reads is named rather than dropped.
        assertTrue(warned(report, "emerald_helmet", "entity/equipment/humanoid/emerald_helmet.png"));
        assertTrue(warned(report, "emerald_helmet", "fire_resistant"));
    }

    @Test
    void oraxensMapFormOfAttributeModifiersAndNexosMaxDamage() throws IOException {
        write("pack/items.yml", """
                plate:
                  material: PAPER
                  pack: { model: pack:item/plate }
                  components:
                    equippable: { slot: LEGS }
                  AttributeModifiers:
                    armor_bonus:
                      attribute: ARMOR
                      amount: 6
                      operation: ADD_NUMBER
                      slot: LEGS
                    speed:
                      attribute: generic.movement_speed
                      amount: 0.2
                      operation: MULTIPLY_SCALAR_1
                nexo_sword:
                  itemname: Sword
                  material: IRON_SWORD
                  Pack: { model: pack:item/sword }
                  Components:
                    max_damage: 900
                  Enchantments: { sharpness: 3 }
                """);

        LoadReport report = load();
        var items = ItemDefinitions.parse(report).items();
        ItemInfo plate = items.get(id("pack:plate"));
        assertEquals("legs", plate.armor().orElseThrow());
        assertEquals("armor", plate.stats().modifiers().get(0).attribute());
        assertEquals("legs", plate.stats().modifiers().get(0).slot());
        assertEquals("movement_speed", plate.stats().modifiers().get(1).attribute());
        assertEquals("multiply", plate.stats().modifiers().get(1).operation());
        // Leggings are the narrow sheet.
        assertTrue(warned(report, "plate", "humanoid_leggings/plate.png"));

        ItemInfo sword = items.get(id("pack:nexo_sword"));
        assertEquals(900, sword.stats().maxDamage().orElseThrow());
        assertEquals(3, sword.stats().enchantments().get("sharpness"));
    }

    @Test
    void textureOnlyItemsTakeTheFirstLayerAndSayWhenTheShapeIsLost() throws IOException {
        write("pack/items.yml", """
                layered:
                  material: PAPER
                  Pack:
                    texture: [pack:item/base, pack:item/overlay]
                mapped:
                  material: PAPER
                  Pack:
                    parent_model: item/handheld
                    textures: { layer0: item/stick_art }
                cube:
                  material: PAPER
                  Pack:
                    parent_model: block/cube_all
                    texture: item/cube.png
                """);

        LoadReport report = load();
        var items = ItemDefinitions.parse(report).items();
        assertEquals("item/base", items.get(id("pack:layered")).texture());
        assertEquals("item/stick_art", items.get(id("pack:mapped")).texture());
        assertEquals("item/cube", items.get(id("pack:cube")).texture());
        assertTrue(warned(report, "layered", "first of its 2 texture layers"));
        assertTrue(warned(report, "cube", "block/cube_all"));
        assertFalse(warned(report, "mapped", "parent_model"));
    }

    // ---- furniture ----------------------------------------------------------

    @Test
    void nexoFurnitureCarriesItsHitboxSeatLightFacingSurfaceAndScale() throws IOException {
        write("pack/items.yml", """
                bench:
                  itemname: Bench
                  material: PAPER
                  Pack: { model: pack:furniture/bench }
                  Mechanics:
                    furniture:
                      hitbox:
                        barrier: 0,0,0
                        interactions:
                          - 0,0,0 2,0.75
                      seat: 0.4,0.5,-0.1
                      lights:
                        light: 0,0,0 12
                      restricted_rotation: VERY_STRICT
                      limited_placing: { roof: false, wall: false, floor: true }
                      properties:
                        scale: 1.5,1.5,1.5
                        translation: 0,0.1,0
                      jukebox: { volume: 1.0 }
                """);

        LoadReport report = load();
        ModelInfo bench = placed(report, "pack:bench");
        assertTrue(bench.solid());
        assertEquals(2f, bench.width());
        assertEquals(0.75f, bench.height());
        // Nexo's seat y is where the rider sits, which is ours.
        assertEquals(0.5f, bench.seat());
        assertEquals(0.4f, bench.seatSide());
        assertEquals(-0.1f, bench.seatForward());
        assertEquals(12, bench.light());
        assertEquals(ModelInfo.Facing.CARDINAL, bench.facing());
        assertEquals(ModelInfo.Surface.FLOOR, bench.surface());
        assertEquals(1.5f, bench.scale());
        assertTrue(warned(report, "bench", "translation"));
        assertTrue(warned(report, "bench", "jukebox"));
        assertTrue(warned(report, "bench", "both solid and a light"));
    }

    @Test
    void oraxenFurnitureSeatsAreArmourStandOffsetsAndBarriersAreBlocks() throws IOException {
        write("pack/items.yml", """
                chair:
                  displayname: "<gray>Chair"
                  material: PAPER
                  mechanics:
                    furniture:
                      type: DISPLAY_ENTITY
                      hitboxes:
                        - 0,0,0 1.0,2.0
                      display_entity_properties:
                        display_transform: FIXED
                        scale: { x: 2, y: 2, z: 2 }
                      barrier: true
                      rotatable: true
                      limited_placing:
                        roof: false
                        floor: true
                        wall: false
                      seats:
                        - 0,-1.2,0
                        - 1,-1.2,0 90
                      drop:
                        loots:
                          - { oraxen_item: chair, probability: 1.0 }
                  pack:
                    generate_model: false
                    model: default/chair
                coach:
                  material: PAPER
                  mechanics:
                    furniture:
                      barriers:
                        - origin
                        - z: 1
                      lights:
                        - 0,1,0 5
                        - 0,0,0 9
                      limited_placing: { floor: true, wall: true, roof: false }
                  pack: { model: default/coach }
                lamp:
                  material: PAPER
                  Mechanics:
                    furniture:
                      seat: { height: 0.5 }
                      light: 14
                  Pack: { model: default/lamp }
                """);

        LoadReport report = load();
        ModelInfo chair = placed(report, "pack:chair");
        assertTrue(chair.solid());
        assertEquals(1f, chair.width());
        assertEquals(2f, chair.height());
        assertEquals((float) (-1.2 + NexoOraxen.ORAXEN_SEAT_LIFT), chair.seat(), 0.001f);
        // Oraxen's default restricted_rotation places on eight facings.
        assertEquals(ModelInfo.Facing.DIAGONAL, chair.facing());
        assertEquals(2f, chair.scale());
        assertEquals("pack:chair", chair.drop().orElseThrow().toString());
        assertTrue(warned(report, "chair", "2 seats"));
        assertTrue(warned(report, "chair", "rotatable"));
        assertTrue(warned(report, "chair", "armour-stand offset"));

        ModelInfo coach = placed(report, "pack:coach");
        assertTrue(coach.solid());
        assertEquals(9, coach.light());
        assertEquals(ModelInfo.Surface.ANY, coach.surface());
        assertTrue(warned(report, "coach", "2 barrier entries"));
        assertTrue(warned(report, "coach", "floor and wall"));

        // Oraxen's legacy seat: {height}, migrated by Oraxen to y = height - 1.
        ModelInfo lamp = placed(report, "pack:lamp");
        assertEquals((float) (0.5 - 1 + NexoOraxen.ORAXEN_SEAT_LIFT), lamp.seat(), 0.001f);
        assertEquals(14, lamp.light());
    }

    // ---- custom blocks ------------------------------------------------------

    @Test
    void oraxenBlocksTakeHardnessToolAndDropFromTheirFirstBreakingRule() throws IOException {
        write("pack/items.yml", """
                ruby:
                  material: PAPER
                  pack: { model: pack:item/ruby }
                caveblock:
                  displayname: "Cave Block"
                  material: PAPER
                  pack:
                    generate_model: false
                    model: default/caveblock
                  mechanics:
                    block:
                      type: FULL
                      block-sounds:
                        break-sound: block.glass.break
                        place-sound: block.glass.place
                      custom-variation: 0
                      appearance:
                        model: default/caveblock
                      breaking:
                        - when:
                            - minecraft:iron_pickaxe
                            - minecraft:diamond_pickaxe
                          hardness: 4
                          drops:
                            - item: ruby
                              probability: 1.0
                        - else:
                          hardness: 6
                amethyst_ore:
                  material: PAPER
                  pack:
                    generate_model: true
                    parent_model: block/cube_all
                    textures:
                      - default/amethyst_ore
                  mechanics:
                    block:
                      type: FULL
                      appearance:
                        model: amethyst_ore
                slab:
                  material: PAPER
                  pack: { model: default/slab }
                  mechanics:
                    block:
                      type: SLAB
                      appearance: { model: default/slab }
                      light: 10
                """);

        LoadReport report = load();
        var blocks = BlockDefinitions.parse(report).blocks();
        BlockInfo cave = blocks.get(id("pack:caveblock"));
        assertEquals("default/caveblock", cave.model());
        assertEquals(4f, cave.hardness());
        assertEquals("pickaxe", cave.tool().orElseThrow());
        assertEquals("pack:ruby", cave.drop().orElseThrow().toString());
        assertEquals("block.glass.place", cave.sound().orElseThrow());
        assertTrue(warned(report, "caveblock", "break-sound"));
        assertTrue(warned(report, "caveblock", "particular tiers"));

        // A generated model has no file, and the warning says how to make one.
        assertEquals("", blocks.get(id("pack:amethyst_ore")).model());
        assertTrue(warned(report, "amethyst_ore", "assets/models/amethyst_ore.json"));

        assertTrue(warned(report, "slab", "slab is not a full block"));
        assertTrue(warned(report, "slab", "cannot give off light"));
    }

    @Test
    void nexoCustomBlocksReadTheirSoundsAndLegacyMechanicNames() throws IOException {
        write("pack/blocks.yml", """
                marble:
                  itemname: Marble
                  material: PAPER
                  Pack: { model: pack:block/marble }
                  Mechanics:
                    custom_block:
                      type: NOTEBLOCK
                      custom_variation: 3
                      hardness: 2.5
                      drop:
                        best_tool: PICKAXE
                        minimal_type: STONE
                        loots:
                          - nexo_item: marble
                      block_sounds:
                        place:
                          sound: block.stone.place
                          volume: 1.0
                weed:
                  material: PAPER
                  Pack: { model: pack:block/weed }
                  Mechanics:
                    stringblock:
                      model: pack:block/weed
                """);

        LoadReport report = load();
        var blocks = BlockDefinitions.parse(report).blocks();
        BlockInfo marble = blocks.get(id("pack:marble"));
        assertEquals(2.5f, marble.hardness());
        assertEquals("pickaxe", marble.tool().orElseThrow());
        assertEquals("block.stone.place", marble.sound().orElseThrow());
        assertEquals("pack:marble", marble.drop().orElseThrow().toString());
        assertTrue(warned(report, "marble", "minimal_type"));
        assertEquals("block/weed", blocks.get(id("pack:weed")).model());
        assertTrue(warned(report, "weed", "stringblock is not a full block"));
    }

    // ---- sounds -------------------------------------------------------------

    @Test
    void theirSoundsYmlBecomesRpEngineSoundsInThisPacksNamespace() throws IOException {
        write("pack/items.yml", """
                ruby:
                  material: PAPER
                  Pack: { model: pack:ruby }
                """);
        write("pack/sounds/sounds.yml", """
                settings:
                  automatically_generate: true
                sounds:
                  - id: pack:welcome
                    sound: welcome.ogg
                    stream: true
                    subtitle: "Welcome Song"
                    category: record
                    volume: 0.8f
                    pitch: 1.2
                    jukebox:
                      duration: 180s
                  - id: music.boss
                    sounds:
                      - pack:music/boss1.ogg
                      - pack:music/boss2.ogg
                  - id: block.wood.place
                    replace: true
                  - id: elsewhere:thing
                    sound: elsewhere:thing
                """);

        LoadReport report = load();
        var sounds = SoundDefinitions.parse(report).sounds();
        SoundInfo welcome = sounds.get(id("pack:welcome"));
        assertEquals("welcome", welcome.file());
        assertTrue(welcome.stream());
        assertEquals("Welcome Song", welcome.subtitle().orElseThrow());
        assertEquals("record", welcome.category());
        assertEquals(0.8f, welcome.volume());
        assertEquals(1.2f, welcome.pitch());
        assertEquals("music/boss1", sounds.get(id("pack:music.boss")).file());
        assertEquals(2, sounds.size());
        assertTrue(warned(report, "pack:welcome", "jukebox"));
        assertTrue(warned(report, "music.boss", "minecraft:music.boss"));
        assertTrue(warned(report, "music.boss", "one of 2 files"));
        assertTrue(warned(report, "block.wood.place", "overrides/sounds.json"));
        assertTrue(warned(report, "elsewhere:thing", "another namespace"));
    }

    @Test
    void aSoundsYmlLooseInAPackFolderWithoutPackYmlStillLoads() throws IOException {
        write("pack/sounds.yml", """
                sounds:
                  - id: pack:chime
                    sound: pack:bells/chime
                """);
        assertEquals("bells/chime", SoundDefinitions.parse(load()).sounds().get(id("pack:chime")).file());
    }

    // ---- glyphs and recipes -------------------------------------------------

    @Test
    void multiBitmapAndAnimatedGlyphsBecomeCellsOfTheirSheet() throws IOException {
        write("pack/items.yml", """
                ruby:
                  material: PAPER
                  Pack: { model: pack:ruby }
                """);
        write("pack/glyphs/sheets.yml", """
                faces:
                  texture: pack:font/faces.png
                  height: 8
                  ascent: 7
                  rows: 2
                  columns: 3
                spinner:
                  texture: animations/spinner
                  animation:
                    frames: 16
                    fps: 16
                  ascent: 6
                  height: 7
                  chat:
                    placeholders: [":spinner:"]
                """);

        LoadReport report = load();
        var icons = IconDefinitions.parse(report).icons();
        IconInfo fifth = icons.get(id("pack:faces_5"));
        assertEquals("faces", fifth.file());
        assertEquals(2, fifth.rows());
        assertEquals(3, fifth.columns());
        assertEquals(5, fifth.cell());
        assertTrue(icons.containsKey(id("pack:faces_6")));
        assertFalse(icons.containsKey(id("pack:faces")));

        IconInfo spinner = icons.get(id("pack:spinner"));
        assertEquals(16, spinner.rows());
        assertEquals(1, spinner.columns());
        assertEquals(1, spinner.cell());
        assertTrue(warned(report, "spinner", "first frame"));
        assertTrue(warned(report, "spinner", "chat"));
    }

    @Test
    void nexoRecipeTypeFoldersAndUnsupportedKindsAreRead() throws IOException {
        write("pack/items.yml", """
                ruby:
                  material: PAPER
                  Pack: { model: pack:ruby }
                """);
        write("pack/recipes/shapeless/gems.yml", """
                ruby_dust:
                  result: { nexo_item: ruby, amount: 2 }
                  ingredients:
                    A: { minecraft_type: REDSTONE, amount: 2 }
                    B: { nexo_item: ruby }
                """);
        write("pack/recipes/blasting/blasting_recipes.yml", """
                quick_ruby:
                  result: { nexo_item: ruby }
                  input: { minecraft_type: DIAMOND_ORE }
                  cookingTime: 50
                """);
        write("pack/recipes/shaped/tagged.yml", """
                plank_ruby:
                  result: { nexo_item: ruby }
                  ingredients:
                    P: { tag: minecraft:planks }
                  shape: ["PPP"]
                """);
        write("pack/recipes/smithing.yml", """
                ruby_upgrade:
                  template: { minecraft_type: NETHERITE_UPGRADE_SMITHING_TEMPLATE }
                  base: { nexo_item: ruby }
                  addition: { minecraft_type: NETHERITE_INGOT }
                  result: { nexo_item: ruby }
                """);

        LoadReport report = load();
        var recipes = RecipeDefinitions.parse(report).recipes();
        RecipeInfo dust = recipes.get(id("pack:ruby_dust"));
        assertEquals(RecipeInfo.Type.SHAPELESS, dust.type());
        assertEquals(java.util.List.of("REDSTONE", "REDSTONE", "pack:ruby"), dust.ingredients());
        RecipeInfo quick = recipes.get(id("pack:quick_ruby"));
        assertEquals(RecipeInfo.Type.BLASTING, quick.type());
        assertEquals(50, quick.cookingTime());
        assertFalse(recipes.containsKey(id("pack:plank_ruby")));
        assertFalse(recipes.containsKey(id("pack:ruby_upgrade")));
        assertTrue(warned(report, "plank_ruby", "rather than made without it"));
        assertTrue(warned(report, null, "smithing recipes were skipped"));
    }
}