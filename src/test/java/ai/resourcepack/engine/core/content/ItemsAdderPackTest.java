package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.core.item.ItemAssets;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.core.sound.SoundAssets;
import ai.resourcepack.engine.core.sound.SoundDefinitions;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.EntityInfo;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.RecipeInfo;
import ai.resourcepack.engine.core.block.BlockDefinitions;
import ai.resourcepack.engine.core.entity.EntityDefinitions;
import ai.resourcepack.engine.core.font.IconDefinitions;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.recipe.RecipeDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/**
 * Somebody's ItemsAdder pack, dropped in as it came.
 *
 * <p>Every config here is written the way their wiki writes them, including
 * the things that are only true of their format — {@code display_name} on old
 * packs and {@code name} on new ones, a texture list rather than a texture, a
 * behaviour block. If this file ever needs "fixing up" before it loads, the
 * feature has not been built.
 */
class ItemsAdderPackTest {

    @TempDir
    Path content;

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private ItemInfo item(String id) {
        return ItemDefinitions.parse(load()).items().get(ContentId.parse(id).orElseThrow());
    }

    /** Their example, near enough verbatim. No pack.yml, because theirs have none. */
    private void theirPack() throws IOException {
        write("my_content/my_config.yml", """
                info:
                  namespace: my_content
                items:
                  ruby:
                    display_name: "&cRuby"
                    permission: mypack.ruby
                    lore:
                      - "&7Shiny."
                    max_stack_size: 16
                    enchants:
                      - ARROW_FIRE:1
                    durability:
                      max_durability: 200
                      unbreakable: false
                    attribute_modifiers:
                      mainhand:
                        attackDamage: 19
                    resource:
                      material: DIAMOND
                      generate: true
                      textures:
                        - item/ruby.png
                  lava_lamp:
                    name: "Lava Lamp"
                    resource:
                      material: PAPER
                      generate: false
                      model_path: lava_lamp
                    behaviours:
                      furniture:
                        entity: item_display
                        light_level: 7
                        solid: true
                  disabled_thing:
                    enabled: false
                    resource:
                      material: STONE
                font_images:
                  image_1:
                    path: font/image_1.png
                    scale_ratio: 9
                    y_position: 8
                """);
    }

    @Test
    void aPackWithNoPackYmlStillLoads() throws IOException {
        theirPack();

        assertEquals(1, load().packs().size());
    }

    @Test
    void anItemComesAcrossWithItsVanillaHalfIntact() throws IOException {
        theirPack();

        ItemInfo ruby = item("my_content:ruby");

        assertEquals("DIAMOND", ruby.material());
        assertEquals("&cRuby", ruby.name().orElseThrow());
        assertEquals(1, ruby.lore().size());
        assertEquals("mypack.ruby", ruby.permission().orElseThrow());
        assertEquals(16, ruby.maxStack().orElseThrow());
        assertEquals("item/ruby", ruby.texture());
        assertEquals(200, ruby.stats().maxDamage().orElseThrow());
        assertEquals(1, ruby.stats().enchantments().get("arrow_fire"));
    }

    @Test
    void configsInTheirConfigsFolderAreRead() throws IOException {
        // Their own layout: configs/ with files at any depth, and no loose
        // config at the pack root at all.
        write("my_content/configs/gems/ruby.yml", """
                info:
                  namespace: my_content
                items:
                  ruby:
                    display_name: Ruby
                    resource:
                      material: DIAMOND
                      generate: true
                      textures: [item/ruby.png]
                """);

        LoadReport report = load();

        assertEquals(1, report.packs().size(), "configs/ alone makes it a pack of theirs");
        assertEquals("Ruby", item("my_content:ruby").name().orElseThrow());
        assertFalse(report.diagnostics().stream().anyMatch(d -> d.message().contains("Not a content category")),
                report.diagnostics().toString());
    }

    @Test
    void theNewerNameKeyIsReadToo() throws IOException {
        theirPack();

        assertEquals("Lava Lamp", item("my_content:lava_lamp").name().orElseThrow());
    }

    @Test
    void aModelPathIsAModel() throws IOException {
        theirPack();

        assertEquals("lava_lamp", item("my_content:lava_lamp").model().orElseThrow());
    }

    @Test
    void furnitureIsAPlacedModel() throws IOException {
        theirPack();

        // place: is read off the same node ours produces, so the model layer
        // sees a solid piece that gives off light 7.
        assertTrue(load().definitions().stream()
                .anyMatch(d -> d.id().toString().equals("my_content:lava_lamp")
                        && d.body().node("place").isPresent()));
    }

    @Test
    void aDisabledItemIsNotLoaded() throws IOException {
        theirPack();

        assertFalse(ItemDefinitions.parse(load()).items()
                .containsKey(ContentId.parse("my_content:disabled_thing").orElseThrow()));
    }

    @Test
    void aFontImageIsAnIcon() throws IOException {
        theirPack();

        IconInfo icon = IconDefinitions.parse(load()).icons()
                .get(ContentId.parse("my_content:image_1").orElseThrow());

        assertEquals("image_1", icon.file());
        assertEquals(9, icon.height());
        assertEquals(8, icon.ascent());
    }

    @Test
    void wiresTransparentsDirectionsVariantsAndSwitchesComeAcross() throws IOException {
        write("my_content/blocks.yml", """
                info:
                  namespace: my_content
                items:
                  fern:
                    resource: { material: PAPER, model_path: block/fern }
                    behaviours:
                      block:
                        placed_model: { type: REAL_WIRE }
                  glass:
                    resource: { material: PAPER, model_path: block/glass }
                    behaviours:
                      block:
                        placed_model: { type: REAL_TRANSPARENT }
                        no_explosion: true
                  kiln:
                    resource: { material: PAPER, model_path: block/kiln }
                    behaviours:
                      block:
                        placed_model: { type: REAL_NOTE, directional_mode: FURNACE }
                  kiln_north:
                    resource: { material: PAPER, model_path: block/kiln_lit }
                    behaviours:
                      block:
                        placed_model: { type: REAL_NOTE }
                  cobbles:
                    resource: { material: PAPER, model_path: block/cobbles }
                    behaviours:
                      block:
                        placed_model: { type: REAL_NOTE }
                        custom_variants:
                          a: { model: "minecraft:block/cobblestone", y: 90 }
                          b: { model: "minecraft:block/mossy_cobblestone", weight: 2 }
                  lamp_off:
                    resource: { material: PAPER, model_path: block/lamp_off }
                    behaviours:
                      block:
                        placed_model: { type: REAL_NOTE }
                    events:
                      placed_block:
                        interact:
                          replace_block: { from: lamp_off, to: lamp_on }
                  lamp_on:
                    resource: { material: PAPER, model_path: block/lamp_on }
                    behaviours:
                      block:
                        placed_model: { type: REAL_NOTE }
                        light_level: 15
                """);

        LoadReport report = load();
        var blocks = BlockDefinitions.parse(report).blocks();
        assertEquals(BlockInfo.Base.TRIPWIRE, blocks.get(ContentId.parse("my_content:fern").orElseThrow()).base());
        BlockInfo glass = blocks.get(ContentId.parse("my_content:glass").orElseThrow());
        assertEquals(BlockInfo.Shape.GRATE, glass.shape());
        assertTrue(glass.behaviour().blastProof());
        BlockInfo kiln = blocks.get(ContentId.parse("my_content:kiln").orElseThrow());
        assertEquals(4, kiln.states().size());
        assertEquals("block/kiln_lit", kiln.appearanceOf("facing=north").model());
        assertTrue(!blocks.containsKey(ContentId.parse("my_content:kiln_north").orElseThrow()),
                "a face of a directional block is not a block of its own");
        BlockInfo cobbles = blocks.get(ContentId.parse("my_content:cobbles").orElseThrow());
        assertEquals(3, cobbles.states().size(), "a weight of two is two of the picks");
        assertEquals("my_content:lamp_on", blocks.get(ContentId.parse("my_content:lamp_off").orElseThrow())
                .behaviour().clicksInto().orElseThrow().toString());
        assertEquals(15, blocks.get(ContentId.parse("my_content:lamp_on").orElseThrow()).light());
    }

    /** Their blocks are ours now, so they come across rather than being refused. */
    @Test
    void aBlockComesAcross() throws IOException {
        write("my_content/blocks.yml", """
                info:
                  namespace: my_content
                items: {}
                blocks:
                  ruby_ore:
                    display_name: Ruby Ore
                    specific_properties:
                      block:
                        placed_model: ruby_ore
                        hardness: 3
                        break_tool: pickaxe
                """);

        BlockInfo ore = BlockDefinitions.parse(load()).blocks()
                .get(ContentId.parse("my_content:ruby_ore").orElseThrow());

        assertEquals("ruby_ore", ore.model());
        assertEquals(3f, ore.hardness());
        assertEquals("pickaxe", ore.tool().orElseThrow());
    }

    @Test
    void anEntityComesAcross() throws IOException {
        write("my_content/mobs.yml", """
                info:
                  namespace: my_content
                items: {}
                entities:
                  barman_robot:
                    display_name: "Barman Robot"
                    type: ZOMBIE
                    model_folder: entity/barman_robot
                    silent: true
                    max_health: 20
                """);

        EntityInfo robot = EntityDefinitions.parse(load()).entities()
                .get(ContentId.parse("my_content:barman_robot").orElseThrow());

        assertEquals("ZOMBIE", robot.type());
        assertEquals("Barman Robot", robot.name().orElseThrow());
        assertEquals(20, robot.health());
        assertTrue(robot.silent());
        // Their model is a folder of blueprints; the last segment is what it
        // is called, and is an item id here.
        assertEquals("barman_robot", robot.model().orElseThrow().path());
    }

    @Test
    void recipesComeAcross() throws IOException {
        write("my_content/recipes.yml", """
                info:
                  namespace: my_content
                items: {}
                recipes:
                  crafting_table:
                    deadmau5_hat:
                      pattern:
                      - BXB
                      - XBX
                      - XXX
                      ingredients:
                        B: LIGHT_BLUE_WOOL
                      result:
                        item: my_content:hat
                        amount: 1
                  cooking:
                    cooked_sausage:
                      ingredient:
                        item: my_content:sausage
                      machines:
                      - FURNACE
                      - SMOKER
                      exp: 1
                      cook_time: 200
                      result:
                        item: my_content:cooked
                """);

        LoadReport loaded = load();
        List<String> ids = loaded.definitions(ContentKind.RECIPE).stream()
                .map(d -> d.id().path()).toList();

        assertTrue(ids.contains("deadmau5_hat"), ids.toString());
        // One of theirs with two machines is two of ours, since a recipe here
        // is one type.
        assertTrue(ids.contains("cooked_sausage"), ids.toString());
        assertTrue(ids.contains("cooked_sausage_smoking"), ids.toString());
    }

    /** Their wiki's smithing and anvil_repair examples, and ItemsAdderAdditions' brewing one. */
    @Test
    void smithingAnvilRepairAndBrewingComeAcross() throws IOException {
        write("my_items/recipes.yml", """
                info:
                  namespace: my_items
                recipes:
                  smithing:
                    my_sword_modified_recipe:
                      enabled: true
                      permission_suffix: recipes_group_1.my_sword_modified_recipe
                      base: my_sword
                      addition: DIAMOND
                      template: EMERALD
                      result:
                        item: my_sword_modified
                        amount: 1
                  anvil_repair:
                    emerald_sword:
                      enabled: true
                      permission: iasurvival.swords.emerald_sword
                      ingredient: EMERALD_BLOCK
                      item: my_items:emerald_sword
                  brewing:
                    ruby_elixir:
                      base:
                        item: minecraft:awkward_potion
                      ingredient:
                        item: my_items:ruby_dust
                        consume: 1
                      result:
                        item: my_items:ruby_elixir
                      brew_time: 400
                      fuel_cost: 1
                """);

        LoadReport loaded = load();
        var recipes = RecipeDefinitions.parse(loaded).recipes();

        // A recipes-only file is still recognised as theirs.
        RecipeInfo smithing = recipes.get(ContentId.parse("my_items:my_sword_modified_recipe").orElseThrow());
        assertEquals(RecipeInfo.Type.SMITHING, smithing.type());
        assertEquals("EMERALD", smithing.template().orElseThrow());
        // A bare lowercase id is the file's own namespace, as theirs reads it.
        assertEquals("my_items:my_sword", smithing.base().orElseThrow());
        assertEquals("DIAMOND", smithing.addition().orElseThrow());
        assertEquals("my_items:my_sword_modified", smithing.result());

        RecipeInfo repair = recipes.get(ContentId.parse("my_items:emerald_sword").orElseThrow());
        assertEquals(RecipeInfo.Type.ANVIL, repair.type());
        assertTrue(repair.isRepair());
        assertEquals("my_items:emerald_sword", repair.base().orElseThrow());
        assertEquals("EMERALD_BLOCK", repair.addition().orElseThrow());
        assertEquals(0.25f, repair.repairFraction(), 1e-6);

        RecipeInfo brew = recipes.get(ContentId.parse("my_items:ruby_elixir").orElseThrow());
        assertEquals(RecipeInfo.Type.BREWING, brew.type());
        assertEquals("potion/awkward", brew.base().orElseThrow());
        assertEquals(List.of("my_items:ruby_dust"), brew.ingredients());
        assertTrue(loaded.diagnostics().stream().anyMatch(d -> d.message().contains("brew_time, fuel_cost")));
    }

    @Test
    void theirPotionNamesBecomeOurs() {
        assertEquals("potion/awkward", ItemsAdder.reference("minecraft:awkward_potion", "x"));
        assertEquals("splash_potion/healing", ItemsAdder.reference("minecraft:healing_splash_potion", "x"));
        assertEquals("potion/water", ItemsAdder.reference("minecraft:water_bottle", "x"));
        assertEquals("minecraft:potion", ItemsAdder.reference("minecraft:potion", "x"));
        assertEquals("GLASS_BOTTLE", ItemsAdder.reference("GLASS_BOTTLE", "x"));
        assertEquals("other:thing", ItemsAdder.reference("other:thing", "x"));
    }

    /** A letter with no ingredient is a blank in their pattern and a space in ours. */
    @Test
    void anUndefinedPatternLetterBecomesABlank() throws IOException {
        write("my_content/recipes.yml", """
                info:
                  namespace: my_content
                items: {}
                recipes:
                  crafting_table:
                    hat:
                      pattern:
                      - BXB
                      ingredients:
                        B: LIGHT_BLUE_WOOL
                      result:
                        item: my_content:hat
                """);

        String pattern = load().definitions(ContentKind.RECIPE).stream()
                .filter(d -> d.id().path().equals("hat"))
                .findFirst().orElseThrow()
                .body().strings("pattern").get(0);

        assertEquals("B B", pattern);
    }

    /** One of their files inside one of our packs, which is the migration path. */
    @Test
    void theirFileWorksInsideOneOfOurPacks() throws IOException {
        write("mypack/pack.yml", "name: Mine\n");
        write("mypack/items/ours.yml", "sapphire:\n  material: DIAMOND\n");
        write("mypack/theirs.yml", """
                info:
                  namespace: mypack
                items:
                  ruby:
                    resource:
                      material: DIAMOND
                """);

        assertEquals(2, ItemDefinitions.parse(load()).items().size());
    }

    @Test
    void aFolderNamedSomethingElseKeepsItsOwnNamespaceAndSaysSo() throws IOException {
        write("renamed/my_config.yml", """
                info:
                  namespace: my_content
                items:
                  ruby:
                    resource:
                      material: DIAMOND
                """);

        LoadReport loaded = load();

        assertTrue(loaded.definitions().stream()
                .anyMatch(d -> d.id().toString().equals("renamed:ruby")));
        assertTrue(loaded.diagnostics().stream()
                .anyMatch(d -> d.message().contains("the folder wins")));
    }

    // ---- armour --------------------------------------------------------------

    @TempDir
    Path out;

    private void bytes(String path, byte[] data) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, data);
    }

    private boolean warned(LoadReport report, String id, String text) {
        return report.diagnostics().stream().anyMatch(d -> d.message().contains(text)
                && d.where().map(id::equals).orElse(false));
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
    void currentArmourWearsItsEquipmentsLayersFromAnotherFile() throws IOException {
        // The wiki's own tutorial, with the set kept in its own file.
        write("my_armor_tutorial/equipments.yml", """
                info:
                  namespace: my_armor_tutorial
                equipments:
                  my_armor_1:
                    type: armor
                    layer_1: armor/my_armor_1/layer_1
                    layer_2: armor/my_armor_1/layer_2
                """);
        write("my_armor_tutorial/items.yml", """
                info:
                  namespace: my_armor_tutorial
                items:
                  my_armor_1_chestplate:
                    name: My Armor 1 Chestplate
                    material: IRON_CHESTPLATE
                    equipment:
                      id: my_armor_tutorial:my_armor_1
                  my_armor_1_leggings:
                    material: IRON_LEGGINGS
                    equipment:
                      id: my_armor_tutorial:my_armor_1
                  my_armor_1_helmet_3d:
                    material: IRON_HELMET
                    resource:
                      material: PAPER
                      model_path: armor/my_armor_1_helmet_3d
                    equipment: {}
                """);
        byte[] body = {1, 2, 3};
        byte[] legs = {4, 5, 6};
        bytes("my_armor_tutorial/textures/armor/my_armor_1/layer_1.png", body);
        bytes("my_armor_tutorial/textures/armor/my_armor_1/layer_2.png", legs);

        LoadReport report = load();
        var items = ItemDefinitions.parse(report).items();
        ItemInfo chest = items.get(ContentId.parse("my_armor_tutorial:my_armor_1_chestplate").orElseThrow());
        assertEquals("chest", chest.armor().orElseThrow(), "the slot follows the material");
        ItemInfo leggings = items.get(ContentId.parse("my_armor_tutorial:my_armor_1_leggings").orElseThrow());
        assertEquals("legs", leggings.armor().orElseThrow());
        // A helmet with no set shows its model on the head: a hat here.
        ItemInfo hat = items.get(ContentId.parse("my_armor_tutorial:my_armor_1_helmet_3d").orElseThrow());
        assertTrue(hat.armor().isEmpty());
        assertTrue(hat.hat());

        Map<String, byte[]> zip = build(report);
        assertArrayEquals(body, zip.get(
                "assets/my_armor_tutorial/textures/entity/equipment/humanoid/my_armor_1_chestplate.png"));
        assertArrayEquals(legs, zip.get(
                "assets/my_armor_tutorial/textures/entity/equipment/humanoid_leggings/my_armor_1_leggings.png"),
                "leggings are drawn from layer_2");
    }

    @Test
    void legacyArmourRenderingsComeAcrossAndSayWhatTheyLose() throws IOException {
        write("myitems/armor.yml", """
                info:
                  namespace: myitems
                armors_rendering:
                  myarmor:
                    color: "#d60000"
                    layer_1: armor/myarmor/layer_1.png
                    layer_2: armor/myarmor/layer_2
                    use_color: true
                legacy_armor_renderings:
                  shiny:
                    color: "#00d600"
                    layer_1: armor/shiny/layer_1
                    layer_2: armor/shiny/layer_2
                    animation:
                      interpolation: true
                items:
                  myarmor_boots:
                    resource:
                      generate: true
                      textures: [item/myarmor/boots]
                    specific_properties:
                      armor:
                        slot: FEET
                        custom_armor: myarmor
                  shiny_helmet:
                    specific_properties:
                      armor: { slot: head, custom_armor: shiny }
                  plain_chestplate:
                    specific_properties:
                      armor: { slot: chest, color: '#ff0001' }
                  lost_leggings:
                    specific_properties:
                      armor: { slot: legs, custom_armor: nowhere }
                """);
        bytes("myitems/resourcepack/assets/myitems/textures/armor/myarmor/layer_1.png", new byte[] {9});

        LoadReport report = load();
        var items = ItemDefinitions.parse(report).items();
        assertEquals("feet", items.get(ContentId.parse("myitems:myarmor_boots").orElseThrow()).armor().orElseThrow());
        assertTrue(warned(report, "myarmor_boots", "use_color"));
        assertTrue(warned(report, "shiny_helmet", "strip of frames"));
        ItemInfo plain = items.get(ContentId.parse("myitems:plain_chestplate").orElseThrow());
        assertEquals("minecraft:leather", plain.armorTexture().orElseThrow());
        assertTrue(warned(report, "plain_chestplate", "plain leather"));
        assertTrue(warned(report, "lost_leggings", "nowhere"));

        // Art kept in a resourcepack/ folder is found where it was copied.
        Map<String, byte[]> zip = build(report);
        assertArrayEquals(new byte[] {9},
                zip.get("assets/myitems/textures/entity/equipment/humanoid/myarmor_boots.png"));
    }

    // ---- sounds --------------------------------------------------------------

    private Map<String, byte[]> buildSounds(LoadReport report) throws IOException {
        BuildReport built = new PackBuilder().with(new SoundAssets()).build(content, out, report);
        assertFalse(built.hasErrors(), built.diagnostics().toString());
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
    void theirSoundsSectionBecomesRpEngineSounds() throws IOException {
        // The wiki's two examples, the subtitle in a language overwrite kept
        // in a second file.
        write("my_sounds/sounds.yml", """
                info:
                  namespace: my_sounds
                sounds:
                  sound_1:
                    path: sound_1
                    settings:
                      subtitle: sound.sound_1
                  song_1:
                    path: music/song_1.ogg
                    settings:
                      subtitle: "Song 1 Disc"
                      volume: 0.5
                      pitch: 1.5
                      weight: 1
                      stream: true
                      attenuation_distance: 10
                    jukebox:
                      enabled: true
                      description: "Song 1"
                """);
        write("my_sounds/lang.yml", """
                info:
                  namespace: my_sounds
                minecraft_lang_overwrite:
                  my_lang_overwrite:
                    entries:
                      sound.sound_1: "Sound 1"
                    languages:
                    - ALL
                """);
        bytes("my_sounds/sounds/sound_1.ogg", new byte[] {1});
        bytes("my_sounds/sounds/music/song_1.ogg", new byte[] {2});

        LoadReport report = load();
        var sounds = SoundDefinitions.parse(report).sounds();
        SoundInfo first = sounds.get(ContentId.parse("my_sounds:sound_1").orElseThrow());
        assertEquals("sound_1", first.file());
        assertEquals("Sound 1", first.subtitle().orElseThrow(), "the key, in the pack's own English");
        SoundInfo song = sounds.get(ContentId.parse("my_sounds:song_1").orElseThrow());
        assertEquals("music/song_1", song.file());
        assertEquals("Song 1 Disc", song.subtitle().orElseThrow());
        assertEquals(0.5f, song.volume());
        assertEquals(1.5f, song.pitch());
        assertTrue(song.stream());
        assertTrue(warned(report, "song_1", "weight, attenuation_distance, jukebox"));

        String json = new String(buildSounds(report).get("assets/my_sounds/sounds.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"my_sounds:music/song_1\""), json);
    }

    @Test
    void anOlderPacksSoundsJsonIsReadAndWhatIsNotReadStillShips() throws IOException {
        write("my_sounds/items.yml", """
                info:
                  namespace: my_sounds
                items:
                  ruby:
                    resource:
                      material: DIAMOND
                """);
        write("my_sounds/resourcepack/assets/my_sounds/sounds.json", """
                {
                  "music.song_1": {
                    "subtitle": "subtitles.my_sounds.song",
                    "sounds": [
                      "my_sounds:music/song_1_variant_1",
                      {"name": "my_sounds:music/song_1_variant_2", "stream": true}
                    ]
                  },
                  "door": {"sounds": [{"name": "my_sounds:door", "stream": true, "volume": 0.7}]},
                  "alias": {"sounds": [{"name": "minecraft:block.note_block.bell", "type": "event"}]}
                }
                """);
        write("my_sounds/resourcepack/assets/my_sounds/lang/en_us.json", """
                {"subtitles.my_sounds.song": "A song plays", "item.my_sounds.ruby": "Ruby"}
                """);
        bytes("my_sounds/resourcepack/assets/my_sounds/sounds/music/song_1_variant_1.ogg", new byte[] {1});
        bytes("my_sounds/resourcepack/assets/my_sounds/sounds/door.ogg", new byte[] {2});

        LoadReport report = load();
        var sounds = SoundDefinitions.parse(report).sounds();
        SoundInfo song = sounds.get(ContentId.parse("my_sounds:music.song_1").orElseThrow());
        assertEquals("music/song_1_variant_1", song.file());
        assertEquals("A song plays", song.subtitle().orElseThrow());
        SoundInfo door = sounds.get(ContentId.parse("my_sounds:door").orElseThrow());
        assertTrue(door.stream());
        assertEquals(0.7f, door.volume());
        assertTrue(warned(report, "music.song_1", "one of 2 files"));
        assertTrue(warned(report, "alias", "another sound event"));

        // The build's sounds.json and language file are written over theirs,
        // and keep what theirs said that ours does not.
        Map<String, byte[]> zip = buildSounds(report);
        String json = new String(zip.get("assets/my_sounds/sounds.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"door\""), json);
        assertTrue(json.contains("\"alias\""), "the event alias still ships: " + json);
        String lang = new String(zip.get("assets/my_sounds/lang/en_us.json"), StandardCharsets.UTF_8);
        assertTrue(lang.contains("item.my_sounds.ruby"), lang);
        assertTrue(lang.contains("A song plays"), lang);
    }

    // ---- their behaviour ---------------------------------------------------

    private static List<String> written(List<ai.resourcepack.engine.api.ItemAction> steps) {
        return steps.stream().map(Object::toString).toList();
    }

    private boolean warned(LoadReport loaded, String about) {
        return loaded.diagnostics().stream().anyMatch(d -> d.message().contains(about));
    }

    @Test
    void anItemsEventsBecomeItsActions() throws IOException {
        write("my_content/wand.yml", """
                info:
                  namespace: my_content
                items:
                  wand:
                    resource:
                      material: STICK
                    item_flags:
                      - HIDE_ATTRIBUTES
                    events:
                      interact:
                        right:
                          play_sound:
                            name: block.note_block.bell
                            volume: 1
                            pitch: 2
                          execute_commands:
                            greet:
                              command: "say hello {player}"
                              as_console: true
                            spawn:
                              command: "/spawn"
                        right_shift:
                          play_sound:
                            name: block.anvil.land
                      eat:
                        potion_effect:
                          type: SPEED
                          amplifier: 1
                          duration: 200
                        give_item:
                          item: 'minecraft:gold_ingot'
                          amount: 2
                        decrement_amount:
                          amount: 1
                      held:
                        play_sound:
                          name: block.anvil.land
                      attack:
                        play_particle:
                          name: heart
                """);

        LoadReport loaded = load();
        ItemInfo wand = ItemDefinitions.parse(loaded).items().get(ContentId.parse("my_content:wand").orElseThrow());

        assertEquals(List.of("sound: block.note_block.bell 1 2", "console: say hello {player}", "run: spawn"),
                written(wand.actions(ai.resourcepack.engine.api.ItemAction.Trigger.RIGHT_CLICK)));
        assertEquals(List.of("effect: SPEED 10 2", "console: give {player} minecraft:gold_ingot 2", "take: 1"),
                written(wand.actions(ai.resourcepack.engine.api.ItemAction.Trigger.CONSUME)));
        assertEquals(List.of("HIDE_ATTRIBUTES"), wand.stats().flags());
        assertTrue(warned(loaded, "interact.right_shift"), "a sneaking click has no trigger");
        assertTrue(warned(loaded, "held"));
        assertEquals(List.of("particle: heart 8"),
                written(wand.actions(ai.resourcepack.engine.api.ItemAction.Trigger.ATTACK)));
    }

    @Test
    void aFurnitureClickCommandIsItsInteract() throws IOException {
        write("my_content/lamps.yml", """
                info:
                  namespace: my_content
                items:
                  lamp:
                    resource:
                      material: PAPER
                      model_path: lamp
                    behaviours:
                      furniture:
                        light_level: 13
                    events:
                      placed_furniture:
                        interact:
                          execute_commands:
                            the_first_command:
                              command: help
                              as_console: false
                        break:
                          play_sound:
                            name: block.glass.break
                """);

        ItemInfo lamp = ItemDefinitions.parse(load()).items().get(ContentId.parse("my_content:lamp").orElseThrow());

        assertEquals(List.of("run: help"),
                written(lamp.actions(ai.resourcepack.engine.api.ItemAction.Trigger.INTERACT)));
        assertEquals(List.of("sound: block.glass.break"),
                written(lamp.actions(ai.resourcepack.engine.api.ItemAction.Trigger.REMOVE)));
    }

    @Test
    void aBlockWrittenAsAnItemIsABlockWithItsEventsAndLoot() throws IOException {
        write("my_content/blocks.yml", """
                info:
                  namespace: my_content
                items:
                  ruby_ore:
                    display_name: Ruby Ore
                    resource:
                      material: PAPER
                      generate: true
                      textures:
                        - block/ruby_ore.png
                    behaviours:
                      block:
                        placed_model:
                          type: REAL_NOTE
                          break_particles: BLOCK
                        hardness: 3
                        break_tools_whitelist:
                          - DIAMOND_PICKAXE
                        sound:
                          place:
                            name: block.stone.place
                    events:
                      placed_block:
                        interact:
                          execute_commands:
                            ring:
                              command: "say {player} poked the ore"
                              as_console: true
                        break:
                          drop_exp:
                            min_amount: 0
                            max_amount: 3
                """);
        write("my_content/loots.yml", """
                info:
                  namespace: my_content
                loots:
                  blocks:
                    ruby_ore:
                      type: my_content:ruby_ore
                      items:
                        ruby:
                          item: my_content:ruby
                          min_amount: 1
                          max_amount: 1
                          chance: 100
                  mobs:
                    zombie:
                      type: ZOMBIE
                """);

        LoadReport loaded = load();
        BlockInfo ore = BlockDefinitions.parse(loaded).blocks()
                .get(ContentId.parse("my_content:ruby_ore").orElseThrow());

        assertTrue(ore.model().contains("my_content:block/ruby_ore"), ore.model());
        assertEquals(3f, ore.hardness());
        assertEquals("pickaxe", ore.tool().orElseThrow());
        assertEquals("block.stone.place", ore.sound().orElseThrow());
        assertEquals("Ruby Ore", ore.name().orElseThrow());
        assertEquals("my_content:ruby", ore.drop().orElseThrow().toString());
        assertEquals(List.of("console: say {player} poked the ore"),
                written(ore.actions().get(ai.resourcepack.engine.api.ItemAction.Trigger.INTERACT)));
        assertTrue(warned(loaded, "drop_exp"));
        assertTrue(warned(loaded, "mobs loot"));
        assertFalse(ItemDefinitions.parse(loaded).items().containsKey(ContentId.parse("my_content:ruby_ore")
                .orElseThrow()), "a block is not also an item definition");
    }
}
