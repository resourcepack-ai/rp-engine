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
import ai.resourcepack.engine.core.font.Gifs;
import ai.resourcepack.engine.core.font.IconDefinitions;
import ai.resourcepack.engine.core.pack.PackFiles;
import ai.resourcepack.engine.core.block.BlockDefinitions;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.model.ModelDefinitions;
import ai.resourcepack.engine.core.recipe.RecipeDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import ai.resourcepack.engine.core.sound.SoundDefinitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.core.item.ItemAssets;
import ai.resourcepack.engine.core.pack.PackBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /** An items file, which is what marks a folder with no pack.yml as one of theirs. */
    private void anItemsFile() throws IOException {
        write("pack/items.yml", """
                ruby:
                  material: PAPER
                  Pack: { model: pack:ruby }
                """);
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
    void staticGlyphsBecomeIconsGifsAnimateAndSheetsSplit() throws IOException {
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
        Path gif = content.resolve("pack/assets/textures/font/gifs/animated.gif");
        Files.createDirectories(gif.getParent());
        Files.write(gif, Gifs.frames(4, 8, 8, 10));

        var icons = IconDefinitions.parse(load(), PackFiles.folder(content)).icons();
        var ruby = icons.get(ContentId.parse("pack:ruby").orElseThrow());
        assertEquals("ui/ruby", ruby.file());
        assertEquals(11, ruby.height());
        assertEquals(9, ruby.ascent());
        // The 2x2 sheet is four icons, one per cell, and the GIF is one more:
        // an animated icon of its four frames, no longer skipped.
        assertEquals(6, icons.size());
        assertEquals(4, icons.get(ContentId.parse("pack:grid_4").orElseThrow()).cell());
        IconInfo animated = icons.get(id("pack:animated"));
        assertEquals("gifs/animated.gif", animated.file());
        assertEquals(4, animated.frames());
        assertEquals(10, animated.fps());
        assertFalse(warned(load(), null, "Animated GIF glyphs"));
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
    void multiBitmapGlyphsBecomeCellsAndAnimatedOnesAnimate() throws IOException {
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

        // Oraxen's strip is the whole animation now, one glyph per frame,
        // not its first frame - and its chat placeholder came with it.
        IconInfo spinner = icons.get(id("pack:spinner"));
        assertEquals(16, spinner.rows());
        assertEquals(1, spinner.columns());
        assertEquals(1, spinner.cell());
        assertEquals(16, spinner.frames());
        assertEquals(16, spinner.fps());
        assertEquals(List.of(":spinner:"), spinner.aliases());
        assertFalse(warned(report, "spinner", "first frame"));
        assertFalse(warned(report, "spinner", "skipped"));
    }

    @Test
    void aNexoGifWithAFrameCountAnimatesAndItsOffsetIsExplained() throws IOException {
        anItemsFile();
        write("pack/glyphs/gifs.yml", """
                necoflap:
                  gif: pack:gifs/necoflap.gif
                  frame_count: 3
                  offset: 2
                  ascent: 8
                  height: 10
                  placeholders: [":neco:"]
                  permission: pack.glyph.neco
                """);
        Path gif = content.resolve("pack/assets/textures/font/gifs/necoflap.gif");
        Files.createDirectories(gif.getParent());
        Files.write(gif, Gifs.frames(6, 4, 4, 20));

        LoadReport report = load();
        IconInfo neco = IconDefinitions.parse(report, PackFiles.folder(content)).icons().get(id("pack:necoflap"));

        assertEquals("gifs/necoflap.gif", neco.file());
        assertEquals(3, neco.frames());
        assertEquals(5, neco.fps());
        assertEquals(10, neco.height());
        assertEquals(List.of(":neco:"), neco.aliases());
        assertEquals("pack.glyph.neco", neco.permission().orElseThrow());
        assertTrue(warned(report, "necoflap", "offset skipped"));
        assertFalse(warned(report, "necoflap", "placeholders"));
    }

    @Test
    void chatKeysCarryAndWhatCannotIsStillNamed() throws IOException {
        anItemsFile();
        write("pack/glyphs/chat.yml", """
                heart:
                  texture: pack:heart
                  placeholders: ["<3", ":heart:"]
                  permission: pack.heart
                  tabcomplete: true
                  is_emoji: true
                  char: "\\uE123"
                  font: pack:emoji
                crown:
                  texture: pack:crown
                  chat:
                    placeholders: [":crown:"]
                    permission: pack.crown
                    tabcomplete: true
                """);

        LoadReport report = load();
        var icons = IconDefinitions.parse(report).icons();

        assertEquals(List.of("<3", ":heart:"), icons.get(id("pack:heart")).aliases());
        assertEquals("pack.heart", icons.get(id("pack:heart")).permission().orElseThrow());
        assertEquals(List.of(":crown:"), icons.get(id("pack:crown")).aliases());
        assertEquals("pack.crown", icons.get(id("pack:crown")).permission().orElseThrow());
        // Still dropped, and still said: no warning names what is now carried.
        assertTrue(warned(report, "heart", "tabcomplete, is_emoji, char, font skipped"));
        assertTrue(warned(report, "crown", "chat.tabcomplete skipped"));
        assertFalse(report.diagnostics().stream().anyMatch(d -> d.message().contains("placeholders")
                || d.message().contains("permission")));
    }

    @Test
    void aReferenceGlyphIsThatCellOfItsSheet() throws IOException {
        anItemsFile();
        write("pack/glyphs/faces.yml", """
                faces:
                  texture: pack:faces
                  height: 9
                  ascent: 7
                  rows: 2
                  columns: 2
                grin:
                  reference: faces
                  index: 3
                  placeholders: [":D"]
                wrong:
                  reference: faces
                  index: 9
                nowhere:
                  reference: missing
                  index: 1
                """);

        LoadReport report = load();
        var icons = IconDefinitions.parse(report).icons();
        IconInfo grin = icons.get(id("pack:grin"));

        assertEquals("faces", grin.file());
        assertEquals(3, grin.cell());
        assertEquals(2, grin.rows());
        assertEquals(2, grin.columns());
        assertEquals(9, grin.height());
        assertEquals(7, grin.ascent());
        assertEquals(List.of(":D"), grin.aliases());
        assertFalse(icons.containsKey(id("pack:wrong")));
        assertFalse(icons.containsKey(id("pack:nowhere")));
        assertTrue(warned(report, "wrong", "index: 9"));
        assertTrue(warned(report, "nowhere", "references missing"));
    }

    @Test
    void placeholdersOnAMultiBitmapGlyphAreExplainedAndItsPermissionReachesEveryCell() throws IOException {
        anItemsFile();
        write("pack/glyphs/sheet.yml", """
                faces:
                  texture: pack:faces
                  rows: 1
                  columns: 2
                  placeholders: [":faces:"]
                  permission: pack.faces
                """);

        LoadReport report = load();
        var icons = IconDefinitions.parse(report).icons();

        assertEquals("pack.faces", icons.get(id("pack:faces_1")).permission().orElseThrow());
        assertEquals("pack.faces", icons.get(id("pack:faces_2")).permission().orElseThrow());
        assertTrue(icons.get(id("pack:faces_1")).aliases().isEmpty());
        assertTrue(warned(report, "faces", "placeholders skipped"));
    }

    @Test
    void aFileOfOurOwnGifIconsIsNotMistakenForNexo() throws IOException {
        write("mine/pack.yml", "{}\n");
        write("mine/fonts/a.yml", """
                dance:
                  gif: dance.gif
                  height: 11
                plain: {}
                """);
        Path gif = content.resolve("mine/assets/textures/font/dance.gif");
        Files.createDirectories(gif.getParent());
        Files.write(gif, Gifs.frames(2, 4, 4, 10));

        var icons = IconDefinitions.parse(load(), PackFiles.folder(content)).icons();

        // Read as Nexo, `plain` would be dropped for having no texture.
        assertEquals(2, icons.get(id("mine:dance")).frames());
        assertTrue(icons.containsKey(id("mine:plain")));
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
        assertTrue(warned(report, "plank_ruby", "rather than made without it"));
        RecipeInfo upgrade = recipes.get(id("pack:ruby_upgrade"));
        assertEquals(RecipeInfo.Type.SMITHING, upgrade.type());
        assertEquals("NETHERITE_UPGRADE_SMITHING_TEMPLATE", upgrade.template().orElseThrow());
        assertEquals("pack:ruby", upgrade.base().orElseThrow());
        assertEquals("NETHERITE_INGOT", upgrade.addition().orElseThrow());
    }

    @Test
    void smithingTrimsAndUpgradesThatDropTheirData() throws IOException {
        anItemsFile();
        // Nexo's own examples: copy_components off, and a trim on a paper template.
        write("pack/recipes/smithing/smithing_recipes.yml", """
                forest_sword_upgrade:
                  template: { minecraft_type: NETHERITE_UPGRADE_SMITHING_TEMPLATE }
                  base: { nexo_item: forest_sword }
                  addition: { minecraft_type: NETHERITE_INGOT }
                  result: { nexo_item: forest_axe }
                  copy_components: false
                paper_template_trim:
                  template: { minecraft_type: PAPER }
                  base: { minecraft_type: IRON_CHESTPLATE }
                  addition: { minecraft_type: AMETHYST_SHARD }
                  trim_pattern: minecraft:silence
                """);

        var recipes = RecipeDefinitions.parse(load()).recipes();

        RecipeInfo upgrade = recipes.get(id("pack:forest_sword_upgrade"));
        assertEquals("pack:forest_axe", upgrade.result());
        assertFalse(upgrade.copyData());
        RecipeInfo trim = recipes.get(id("pack:paper_template_trim"));
        assertEquals(RecipeInfo.Type.SMITHING_TRIM, trim.type());
        assertEquals("minecraft:silence", trim.pattern().orElseThrow());
        assertEquals("PAPER", trim.template().orElseThrow());
    }

    @Test
    void nexoBrewing() throws IOException {
        anItemsFile();
        write("pack/recipes/brewing/brewing_recipes.yml", """
                diamond:
                  result: { minecraft_type: DIAMOND }
                  input: { minecraft_type: GLASS_BOTTLE }
                  ingredient: { nexo_item: rainbow_ingot }
                """);

        RecipeInfo brew = RecipeDefinitions.parse(load()).recipes().get(id("pack:diamond"));

        assertEquals(RecipeInfo.Type.BREWING, brew.type());
        assertEquals("GLASS_BOTTLE", brew.base().orElseThrow());
        assertEquals(java.util.List.of("pack:rainbow_ingot"), brew.ingredients());
        assertEquals("DIAMOND", brew.result());
    }

    @Test
    void oraxenAnvilChargesNothingUnlessToldTo() throws IOException {
        anItemsFile();
        write("pack/recipes/anvil.yml", """
                repair_obsidian_sword:
                  permission: oraxen.recipe.repair_obsidian_sword
                  experience_cost: 5
                  base: { oraxen_item: damaged_obsidian_sword }
                  addition: { oraxen_item: obsidian_ingot, amount: 2 }
                  result: { oraxen_item: obsidian_sword }
                polish:
                  base: { oraxen_item: dull_gem }
                  result: { oraxen_item: gem }
                """);

        var recipes = RecipeDefinitions.parse(load()).recipes();

        RecipeInfo sword = recipes.get(id("pack:repair_obsidian_sword"));
        assertEquals(RecipeInfo.Type.ANVIL, sword.type());
        assertEquals("pack:damaged_obsidian_sword", sword.base().orElseThrow());
        assertEquals("pack:obsidian_ingot", sword.addition().orElseThrow());
        assertEquals(2, sword.additionAmount());
        assertEquals(5, sword.cost());
        RecipeInfo polish = recipes.get(id("pack:polish"));
        assertTrue(polish.addition().isEmpty(), "no addition means the second slot stays empty");
        assertEquals(0, polish.cost());
    }

    @Test
    void nexoAnvilRepairsWithoutAResult() throws IOException {
        anItemsFile();
        write("pack/recipes/anvil/anvil_recipes.yml", """
                mend_forest_axe:
                  input: { nexo_item: forest_axe }
                  material: { minecraft_type: OAK_LOG }
                  repair: 100
                reforge:
                  input: { nexo_item: forest_axe }
                  material: { nexo_item: ruby }
                  result: { nexo_item: forest_sword }
                  cost: 3
                """);

        var recipes = RecipeDefinitions.parse(load()).recipes();

        RecipeInfo mend = recipes.get(id("pack:mend_forest_axe"));
        assertTrue(mend.isRepair());
        assertEquals(100, mend.repairPoints());
        assertEquals(1, mend.cost(), "Nexo's default is a level");
        RecipeInfo reforge = recipes.get(id("pack:reforge"));
        assertEquals("pack:forest_sword", reforge.result());
        assertEquals(3, reforge.cost());
    }

    @Test
    void cauldronAndGrindstoneAreStillSkippedByName() throws IOException {
        anItemsFile();
        write("pack/recipes/grindstone.yml", """
                extract_ruby:
                  base: { oraxen_item: ruby_sword }
                  result: { oraxen_item: ruby }
                """);

        LoadReport report = load();

        assertTrue(RecipeDefinitions.parse(report).recipes().isEmpty());
        assertTrue(warned(report, null, "grindstone recipes were skipped"));
    }

    // ---- armour ---------------------------------------------------------------

    @TempDir Path out;

    private void bytes(String path, byte[] data) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, data);
    }

    private Map<String, byte[]> build(LoadReport report) throws IOException {
        BuildReport built = new PackBuilder().with(new ItemAssets()).build(content, out, report);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(built.pack("main").orElseThrow().file()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    void armourLayerArtIsFoundByNameAndServedWhereTheGameReadsIt() throws IOException {
        // Oraxen's component armour: the set named by equippable.model, its
        // art found by file name beside the item icons.
        write("oraxen_pack/items/armors.yml", """
                emerald_helmet:
                  material: PAPER
                  components:
                    equippable: { slot: HEAD, model: oraxen:emerald }
                  pack: { textures: [default/armors/emerald_helmet] }
                emerald_leggings:
                  material: PAPER
                  components:
                    equippable: { slot: LEGS, model: oraxen:emerald }
                  pack: { textures: [default/armors/emerald_leggings] }
                ruby_boots:
                  material: PAPER
                  pack: { textures: [default/armors/ruby_boots] }
                ruby_chestplate:
                  material: CHAINMAIL_CHESTPLATE
                  trim_pattern: oraxen:ruby
                  pack: { textures: [default/armors/ruby_chestplate] }
                magic_elytra:
                  material: ELYTRA
                  components:
                    equippable: { slot: CHEST, model: oraxen:magic_elytra }
                  pack: { textures: [default/armors/magic_elytra_icon] }
                crown:
                  material: PAPER
                  components:
                    equippable: { slot: HEAD }
                  pack: { model: item/crown }
                fancy_boots:
                  material: PAPER
                  pack: { textures: [item/fancy_boots] }
                """);
        bytes("oraxen_pack/assets/textures/default/armors/emerald_armor_layer_1.png", new byte[] {1});
        bytes("oraxen_pack/assets/textures/default/armors/emerald_armor_layer_2.png", new byte[] {2});
        bytes("oraxen_pack/assets/textures/default/armors/ruby_armor_layer_1.png", new byte[] {3});

        LoadReport report = load();
        var items = ItemDefinitions.parse(report).items();
        assertEquals("head", items.get(id("oraxen_pack:emerald_helmet")).armor().orElseThrow());
        assertEquals("legs", items.get(id("oraxen_pack:emerald_leggings")).armor().orElseThrow());
        // No component at all: armour because its id names a piece and the
        // art exists, which is when both plugins assign one themselves.
        assertEquals("feet", items.get(id("oraxen_pack:ruby_boots")).armor().orElseThrow());
        // Trim-based armour is the same art.
        assertEquals("chest", items.get(id("oraxen_pack:ruby_chestplate")).armor().orElseThrow());
        assertTrue(items.get(id("oraxen_pack:magic_elytra")).armor().isEmpty());
        assertTrue(warned(report, "magic_elytra", "wings"));
        // A 3D helmet, which has no layer art: a hat.
        assertTrue(items.get(id("oraxen_pack:crown")).hat());
        assertTrue(items.get(id("oraxen_pack:crown")).armor().isEmpty());
        assertTrue(items.get(id("oraxen_pack:fancy_boots")).armor().isEmpty(), "no art, no component, no armour");
        assertFalse(warned(report, "emerald_helmet", "no layer art"));

        Map<String, byte[]> zip = build(report);
        String equipment = "assets/oraxen_pack/textures/entity/equipment/";
        assertArrayEquals(new byte[] {1}, zip.get(equipment + "humanoid/emerald_helmet.png"));
        assertArrayEquals(new byte[] {2}, zip.get(equipment + "humanoid_leggings/emerald_leggings.png"));
        assertArrayEquals(new byte[] {3}, zip.get(equipment + "humanoid/ruby_boots.png"));
        assertArrayEquals(new byte[] {3}, zip.get(equipment + "humanoid/ruby_chestplate.png"));
    }

    @Test
    void nexosWrittenCustomArmorLayersWinAndAForeignOneFallsBackToTheName() throws IOException {
        write("nexo_pack/items/forest.yml", """
                forest_helmet:
                  material: PAPER
                  Pack:
                    CustomArmor:
                      layer1: nexo:item/nexo_armors/forest_armor_layer_1
                      layer2: nexo:item/nexo_armors/forest_armor_layer_2
                    texture: nexo_pack:item/nexo_armors/forest_helmet
                  Components:
                    equippable: { slot: HEAD, asset_id: nexo:forest }
                oak_leggings:
                  material: PAPER
                  Pack:
                    CustomArmor:
                      layer1: armors/oak_body.png
                      layer2: armors/oak_legs.png
                    texture: item/oak_leggings
                """);
        bytes("nexo_pack/assets/textures/item/nexo_armors/forest_armor_layer_1.png", new byte[] {7});
        bytes("nexo_pack/assets/textures/armors/oak_legs.png", new byte[] {8});

        LoadReport report = load();
        assertFalse(warned(report, "forest_helmet", "outside this pack's namespace"),
                "the same file was found by its name");
        Map<String, byte[]> zip = build(report);
        String equipment = "assets/nexo_pack/textures/entity/equipment/";
        assertArrayEquals(new byte[] {7}, zip.get(equipment + "humanoid/forest_helmet.png"));
        assertArrayEquals(new byte[] {8}, zip.get(equipment + "humanoid_leggings/oak_leggings.png"));
    }

    // ---- their behaviour ---------------------------------------------------

    private static java.util.List<String> steps(ItemInfo item, ai.resourcepack.engine.api.ItemAction.Trigger trigger) {
        return item.actions(trigger).stream().map(Object::toString).toList();
    }

    @Test
    void nexoCommandsAndClickActionsBecomeActions() throws IOException {
        write("nexo_pack/items.yml", """
                wand:
                  itemname: Wand
                  material: STICK
                  ItemFlags:
                    - HIDE_ENCHANTS
                  Mechanics:
                    commands:
                      cooldown: 5s
                      permission: my.wand
                      one_usage: true
                      console:
                        - "effect give %p% speed 5"
                      player:
                        - "/spawn"
                      opped_player:
                        - "give diamond_sword 1"
                    soulbound:
                      lose_chance: 0
                bell:
                  itemname: Bell
                  material: PAPER
                  Pack:
                    model: nexo_pack:item/bell
                  Mechanics:
                    furniture:
                      clickActions:
                        - conditions: []
                          actions:
                            - '[console] say <player> rang the bell'
                            - '{source=AMBIENT volume=0.5 pitch=1.2} [sound] minecraft:block.bell.use'
                            - '[message] <gold>Ding!'
                        - conditions:
                            - '#player.world.name == "world"'
                          actions:
                            - '[actionbar] only in the overworld'
                      storage:
                        rows: 3
                      jukebox:
                        volume: 1
                """);

        LoadReport report = load();
        var items = ItemDefinitions.parse(report).items();
        ItemInfo wand = items.get(id("nexo_pack:wand"));
        var right = ai.resourcepack.engine.api.ItemAction.Trigger.RIGHT_CLICK;
        assertEquals(java.util.List.of("permission: my.wand", "cooldown: 5.0", "console: effect give {player} speed 5",
                "run: spawn", "take: 1"), steps(wand, right));
        assertEquals(steps(wand, right), steps(wand, ai.resourcepack.engine.api.ItemAction.Trigger.LEFT_CLICK));
        assertTrue(wand.keepOnDeath());
        assertEquals(java.util.List.of("HIDE_ENCHANTS"), wand.stats().flags());
        assertTrue(warned(report, "wand", "opped_player"));

        ItemInfo bell = items.get(id("nexo_pack:bell"));
        assertEquals(java.util.List.of("console: say {player} rang the bell",
                        "sound: minecraft:block.bell.use 0.5 1.2", "message: &6Ding!"),
                steps(bell, ai.resourcepack.engine.api.ItemAction.Trigger.INTERACT));
        assertTrue(warned(report, "bell", "player.world.name"));
        assertTrue(warned(report, "bell", "furniture storage was skipped"));
        assertTrue(warned(report, "bell", "furniture jukebox was skipped"));
    }

    @Test
    void oraxenEventsCustomAndLegacyFoodBecomeActions() throws IOException {
        write("oraxen_pack/items.yml", """
                lamp:
                  displayname: Lamp
                  material: PAPER
                  mechanics:
                    furniture:
                      events:
                        - click: RIGHT
                          actions:
                            - command: "say <player> lit it"
                              executor: console
                            - message: "<yellow>Click"
                              conditions:
                                - 'player.hasPermission("lamp.chat")'
                        - click: LEFT
                          actions:
                            - command: "say left"
                pie:
                  displayname: Pie
                  material: PAPER
                  mechanics:
                    food:
                      hunger: 6
                      saturation: 4
                      effects:
                        SPEED:
                          duration: 10
                          amplifier: 0
                      replacement:
                        minecraft_type: BOWL
                    custom:
                      hello:
                        event: "CLICK:right:all"
                        cooldown: 2000
                        conditions:
                          - '#player.hasPermission("pie.hello")'
                        actions:
                          - "[message] hello"
                      dead:
                        event: "DEATH"
                        actions:
                          - "[message] gone"
                    hat: {}
                """);

        LoadReport report = load();
        var items = ItemDefinitions.parse(report).items();
        ItemInfo lamp = items.get(id("oraxen_pack:lamp"));
        assertEquals(java.util.List.of("console: say {player} lit it", "permission: lamp.chat", "message: &eClick"),
                steps(lamp, ai.resourcepack.engine.api.ItemAction.Trigger.INTERACT));
        assertTrue(warned(report, "lamp", "left-click event"));

        ItemInfo pie = items.get(id("oraxen_pack:pie"));
        assertEquals(6, pie.stats().food().orElseThrow().nutrition());
        assertEquals(java.util.List.of("effect: SPEED 10 1", "console: give {player} minecraft:bowl"),
                steps(pie, ai.resourcepack.engine.api.ItemAction.Trigger.CONSUME));
        assertEquals(java.util.List.of("permission: pie.hello", "cooldown: 2.0", "message: hello"),
                steps(pie, ai.resourcepack.engine.api.ItemAction.Trigger.RIGHT_CLICK));
        assertTrue(pie.hat());
        assertTrue(warned(report, "pie", "DEATH"));
    }

    @Test
    void aNexoCustomBlocksClickActionsAreItsInteract() throws IOException {
        write("nexo_pack/blocks.yml", """
                button:
                  itemname: Button
                  material: PAPER
                  Pack:
                    model: nexo_pack:block/button
                  Mechanics:
                    custom_block:
                      type: NOTEBLOCK
                      custom_variation: 3
                      clickActions:
                        - conditions:
                            - '#player.hasPermission("button.press")'
                          actions:
                            - '[console] say pressed'
                """);

        var block = ai.resourcepack.engine.core.block.BlockDefinitions.parse(load()).blocks()
                .get(id("nexo_pack:button"));
        assertEquals(java.util.List.of("permission: button.press", "console: say pressed"),
                block.actions().get(ai.resourcepack.engine.api.ItemAction.Trigger.INTERACT).stream()
                        .map(Object::toString).toList());
    }

    @Test
    void cooldownsAreReadInEitherPluginsUnits() {
        assertEquals(5d, NexoOraxenActions.cooldown("5s").orElseThrow());
        assertEquals(0.5, NexoOraxenActions.cooldown("10t").orElseThrow());
        assertEquals(90d, NexoOraxenActions.cooldown("1m30s").orElseThrow());
        assertEquals(2d, NexoOraxenActions.cooldown("2000").orElseThrow(), "a bare number is Oraxen's milliseconds");
        assertTrue(NexoOraxenActions.cooldown("0").isEmpty());
    }
}