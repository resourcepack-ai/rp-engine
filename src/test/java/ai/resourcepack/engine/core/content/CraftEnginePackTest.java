package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.McVersion;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.api.RecipeInfo;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.core.block.BlockAssets;
import ai.resourcepack.engine.core.block.BlockDefinitions;
import ai.resourcepack.engine.core.block.BlockStates;
import ai.resourcepack.engine.core.font.FontAssets;
import ai.resourcepack.engine.core.font.IconDefinitions;
import ai.resourcepack.engine.core.item.ItemAssets;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.model.ModelDefinitions;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.recipe.RecipeDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import ai.resourcepack.engine.core.sound.SoundAssets;
import ai.resourcepack.engine.core.sound.SoundDefinitions;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CraftEngine content folders and files, copied into a content folder without
 * conversion. The fixtures are written for these tests in the shape of
 * CraftEngine's own example content.
 */
class CraftEnginePackTest {
    @TempDir Path content;
    @TempDir Path out;

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void bytes(String path, byte[] data) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, data);
    }

    /** A PNG header of a given size, which is all anything here reads of one. */
    private static byte[] png(int width, int height) {
        byte[] data = new byte[33];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
        System.arraycopy(signature, 0, data, 0, signature.length);
        data[16] = (byte) (width >>> 24);
        data[17] = (byte) (width >>> 16);
        data[18] = (byte) (width >>> 8);
        data[19] = (byte) width;
        data[20] = (byte) (height >>> 24);
        data[21] = (byte) (height >>> 16);
        data[22] = (byte) (height >>> 8);
        data[23] = (byte) height;
        return data;
    }

    private LoadReport load() {
        return load(null);
    }

    private LoadReport load(McVersion version) {
        return new ContentFolderLoader(new ContentRegistryImpl(), version).load(content, ContentSource.AUTHORED);
    }

    private static ContentId id(String id) {
        return ContentId.parse(id).orElseThrow();
    }

    private static boolean warned(LoadReport report, String where, String text) {
        return report.diagnostics().stream().anyMatch(d -> d.message().contains(text)
                && (where == null || d.where().map(where::equals).orElse(false)));
    }

    private static ItemInfo item(LoadReport report, String id) {
        return ItemDefinitions.parse(report).items().get(id(id));
    }

    private static ContentDefinition definition(LoadReport report, String id) {
        return report.definitions().stream().filter(d -> d.id().toString().equals(id)).findFirst().orElse(null);
    }

    private static ModelInfo placed(LoadReport report, String id) {
        var items = ItemDefinitions.parse(report).items();
        return ModelDefinitions.parse(report, items).model().get(id(id));
    }

    private static String errors(LoadReport report) {
        StringBuilder text = new StringBuilder();
        for (Diagnostic d : report.diagnostics()) {
            if (d.severity() == Diagnostic.Severity.ERROR) text.append(d).append('\n');
        }
        return text.toString();
    }

    /** The default_templates pack CraftEngine ships, the parts these tests use, in our own words. */
    private void templates() throws IOException {
        write("default_templates/pack.yml", """
                author: test
                version: 1.0
                namespace: default
                """);
        write("default_templates/configuration/templates/settings.yml", """
                templates#settings:
                  default:sound/stone:
                    sounds:
                      break: minecraft:block.stone.break
                      place: minecraft:block.stone.place
                  default:settings/solid_1x1x1:
                    is_suffocating: true
                    can_occlude: true
                  default:settings/ore:
                    template:
                      - default:sound/stone
                      - default:settings/solid_1x1x1
                    overrides:
                      hardness: ${hardness:-3.0}
                      correct_tools: ["#minecraft:pickaxes"]
                      required_break_power: ${break_power}
                templates#loot:
                  default:loot_table/self:
                    pools:
                      - rolls: 1
                        entries:
                          - type: item
                            item: ${__NAMESPACE__}:${__ID__}
                  default:loot_table/ore:
                    pools:
                      - rolls: 1
                        entries:
                          - type: alternatives
                            children:
                              - type: item
                                item: ${ore_block}
                                conditions:
                                  - type: enchantment
                                    predicate: minecraft:silk_touch>=1
                              - type: item
                                item: ${ore_drop}
                                functions:
                                  - type: apply_bonus
                                    enchantment: minecraft:fortune
                  default:loot_table/furniture:
                    pools:
                      - rolls: 1
                        entries:
                          - type: furniture_item
                            item: ${item}
                """);
    }

    @Test
    void aWholePackFolderLoadsWithItsTemplatesLanguageAndArt() throws IOException {
        templates();
        write("gems/pack.yml", """
                author: test
                version: 1.0
                description: Gems
                namespace: gems
                """);
        write("gems/configuration/items/ruby.yml", """
                items:
                  gems:ruby:
                    material: diamond
                    data:
                      item_name: <!i><#E0115F><lang:item.gems.ruby>
                      lore:
                        - <gray>Shiny
                    texture: minecraft:item/custom/ruby
                  gems:ruby_sword:
                    material: golden_sword
                    data:
                      item-name: <red>Ruby Sword
                      max_damage: 300
                      enchantments:
                        minecraft:sharpness: 3
                      attribute_modifiers:
                        - type: minecraft:attack_damage
                          amount: 7
                          operation: add_value
                          slot: mainhand
                    settings:
                      tags: [minecraft:swords]
                    texture: minecraft:item/custom/ruby_sword
                  gems:ruby_ore:
                    data:
                      item_name: <lang:item.gems.ruby_ore>
                    model: minecraft:block/custom/ruby_ore
                    behavior:
                      type: block_item
                      block: gems:ruby_ore
                blocks:
                  gems:ruby_ore:
                    loot:
                      template: default:loot_table/ore
                      arguments:
                        ore_drop: gems:ruby
                        ore_block: gems:ruby_ore
                    settings:
                      template: default:settings/ore
                      arguments:
                        break_power: 2
                    state:
                      auto_state: solid
                      texture: minecraft:block/custom/ruby_ore
                """);
        write("gems/configuration/lang.yml", """
                lang#items:
                  en_us:
                    item.gems.ruby: Ruby
                    item.gems.ruby_ore: Ruby Ore
                  fr_fr:
                    item.gems.ruby: Rubis
                """);
        bytes("gems/resourcepack/assets/minecraft/textures/item/custom/ruby.png", png(16, 16));
        bytes("gems/resourcepack/assets/minecraft/textures/item/custom/ruby_sword.png", png(16, 16));
        bytes("gems/resourcepack/assets/minecraft/textures/block/custom/ruby_ore.png", png(16, 16));

        LoadReport report = load();
        assertEquals("", errors(report));

        ItemInfo ruby = item(report, "gems:ruby");
        assertEquals("DIAMOND", ruby.material());
        assertEquals("&x&e&0&1&1&5&fRuby", ruby.name().orElseThrow(), "the lang text, and the hex colour");
        assertEquals(List.of("&7Shiny"), ruby.lore());
        assertEquals("minecraft:item/custom/ruby", ruby.texture());

        ItemInfo sword = item(report, "gems:ruby_sword");
        assertEquals("&cRuby Sword", sword.name().orElseThrow(), "item-name in kebab case is the same key");
        assertEquals(300, sword.stats().maxDamage().orElseThrow());
        assertEquals(3, sword.stats().enchantments().get("sharpness"));
        assertTrue(sword.model().orElseThrow().contains("minecraft:item/handheld"),
                "a sword's generated model is held like a sword");
        assertTrue(warned(report, "gems:ruby_sword", "the settings tags"));

        BlockInfo ore = BlockDefinitions.parse(report).blocks().get(id("gems:ruby_ore"));
        assertNotNull(ore, "the item and the block it places are one id, the block");
        assertNull(item(report, "gems:ruby_ore"));
        assertEquals("Ruby Ore", ore.name().orElseThrow(), "the item's name comes with the block");
        assertEquals(3.0f, ore.hardness(), "the template argument's default");
        assertEquals("pickaxe", ore.tool().orElseThrow(), "correct_tools plus a break power");
        assertEquals(id("gems:ruby"), ore.drop().orElseThrow(), "the alternative without silk touch");
        assertEquals("minecraft:block.stone.place", ore.sound().orElseThrow());
        assertTrue(ore.model().contains("minecraft:block/cube_all"), "one texture is a cube_all model");
        assertTrue(warned(report, "gems:ruby_ore", "silk touch") || warned(report, "gems:ruby_ore", "alternatives"));
        assertTrue(warned(report, null, "fr_fr"));
    }

    @Test
    void theResourcePackFolderIsCopiedAsWrittenAndTheArtResolves() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/items.yml", """
                items:
                  gems:ruby:
                    texture: minecraft:item/custom/ruby
                  gems:lamp:
                    model: minecraft:item/custom/lamp
                  gems:crate:
                    model: gems:item/crate
                    behavior:
                      type: block_item
                      block:
                        state:
                          auto_state: solid
                          model:
                            path: gems:block/crate
                  gems:pillar:
                    behavior:
                      type: block_item
                      block:
                        state:
                          auto_state: mushroom_stem
                          textures:
                            - minecraft:block/custom/pillar_top
                            - minecraft:block/custom/pillar_side
                """);
        bytes("gems/resourcepack/assets/minecraft/textures/item/custom/ruby.png", png(16, 16));
        write("gems/resourcepack/assets/minecraft/models/item/custom/lamp.json", """
                {"parent": "item/generated", "textures": {"layer0": "item/custom/lamp"}}
                """);
        bytes("gems/resourcepack/assets/minecraft/textures/item/custom/lamp.png", png(16, 16));
        write("gems/resourcepack/assets/gems/models/block/crate.json", """
                {"elements": [{"from": [0,0,0], "to": [16,16,16], "faces": {}}],
                 "textures": {"all": "gems:block/crate"}}
                """);
        bytes("gems/resourcepack/assets/gems/textures/block/crate.png", png(16, 16));

        LoadReport report = load();
        assertEquals("", errors(report));

        BlockStates states = new BlockStates(out.resolve("state").toFile());
        for (BlockInfo block : BlockDefinitions.parse(report).blocks().values()) states.numberFor(block);
        BuildReport built = new PackBuilder().with(new ItemAssets()).with(new BlockAssets(states))
                .build(content, out, report);
        Map<String, String> zip = read(built);
        assertTrue(built.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
                built.diagnostics().toString());

        assertTrue(zip.containsKey("assets/minecraft/textures/item/custom/ruby.png"), "copied as written");
        assertTrue(zip.get("assets/gems/models/item/ruby.json").contains("minecraft:item/custom/ruby"));
        // A model out of a real resource pack: a bare texture means minecraft:.
        assertTrue(zip.get("assets/gems/models/item/lamp.json").contains("\"minecraft:item/custom/lamp\""),
                zip.get("assets/gems/models/item/lamp.json"));
        assertTrue(zip.containsKey("assets/minecraft/models/item/custom/lamp.json"),
                "and the original stays, because anything else in that pack may name it");
        assertTrue(zip.get("assets/gems/models/block/crate.json").contains("gems:block/crate"));
        String pillar = zip.get("assets/gems/models/block/pillar.json");
        assertTrue(pillar.contains("minecraft:block/cube_column"), pillar);
        assertTrue(pillar.contains("\"end\":\"minecraft:block/custom/pillar_top\""), pillar);
        assertEquals(BlockInfo.Base.MUSHROOM_STEM,
                BlockDefinitions.parse(report).blocks().get(id("gems:pillar")).base());
    }

    @Test
    void versionKeysPickTheBlockForTheRunningServer() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/items.yml", """
                items:
                  gems:always:
                    material: paper
                    data:
                      max_stack_size:
                        $$>=1.21.4: 16
                        $$fallback: 32
                  $$>=1.21.4#trident:
                    gems:trident:
                      material: trident
                  $$1.20.1~1.21.3:
                    gems:old_only:
                      material: paper
                equipments:
                  $$>=1.21.2:
                    gems:ruby:
                      type: component
                      humanoid: minecraft:entity/equipment/humanoid/ruby
                  $$<1.21.2:
                    gems:ruby:
                      type: trim
                      humanoid: minecraft:entity/equipment/humanoid/ruby_old
                """);
        LoadReport modern = load(McVersion.of(1, 21, 4));
        assertEquals(16, item(modern, "gems:always").maxStack().orElseThrow());
        assertNotNull(item(modern, "gems:trident"));
        assertNull(item(modern, "gems:old_only"));

        LoadReport old = load(McVersion.of(1, 21, 1));
        assertEquals(32, item(old, "gems:always").maxStack().orElseThrow(), "nothing matched, so the fallback");
        assertNull(item(old, "gems:trident"));
        assertNotNull(item(old, "gems:old_only"));

        LoadReport newest = load(McVersion.of(26, 1));
        assertNull(item(newest, "gems:old_only"), "26.1 is newer than every 1.21");
    }

    @Test
    void deepKeysAndTemplatesMergeTheWayCraftEngineMergesThem() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/templates.yml", """
                templates:
                  gems:tool:
                    material: ${material:-iron_pickaxe}
                    data:
                      item_name: ${name}
                      lore: [Base]
                    settings:
                      tags: [a]
                  gems:named:
                    data:
                      item_name: <gold>${__ID__^}
                items:
                  gems:pick:
                    template: gems:tool
                    arguments:
                      name: Pick
                    merges:
                      data:
                        lore: [Extra]
                  gems:replaced:
                    template: gems:tool
                    arguments:
                      name: Replaced
                      material: golden_pickaxe
                    overrides:
                      data:
                        max_stack_size: 4
                  gems:shout:
                    template: gems:named
                  gems:deep:
                    material: paper
                    data::max_stack_size: 8
                  gems:broken:
                    template: gems:nowhere
                  gems:needs_default:
                    template: default:settings/ore
                  gems:unfilled:
                    template: gems:tool
                """);
        LoadReport report = load();
        ItemInfo pick = item(report, "gems:pick");
        assertEquals("IRON_PICKAXE", pick.material(), "the argument's default");
        assertEquals("Pick", pick.name().orElseThrow());
        assertEquals(List.of("Base", "Extra"), pick.lore(), "merges append lists");

        ItemInfo replaced = item(report, "gems:replaced");
        assertEquals("GOLDEN_PICKAXE", replaced.material());
        assertTrue(replaced.name().isEmpty(), "overrides replace a whole top-level key");
        assertEquals(4, replaced.maxStack().orElseThrow());

        assertEquals("&6Shout", item(report, "gems:shout").name().orElseThrow(), "${__ID__^} capitalised");
        assertEquals(8, item(report, "gems:deep").maxStack().orElseThrow(), "data::max_stack_size");

        assertNull(item(report, "gems:broken"));
        assertTrue(warned(report, "gems:broken", "uses the template gems:nowhere"));
        assertTrue(warned(report, "gems:needs_default", "default_templates"),
                "a default: template says where CraftEngine keeps them");
        assertTrue(warned(report, "gems:unfilled", "${name}"));
    }

    @Test
    void templateArgumentsOfTheParentWinAndTypedArgumentsAreEvaluated() {
        CraftEngineTemplates templates = new CraftEngineTemplates();
        templates.define("t:inner", Map.of("value", "${size}", "other", "${kind}"));
        templates.define("t:outer", Map.of("template", "t:inner", "arguments", Map.of("size", "inner")));
        Object expanded = templates.expand(Map.of("template", "t:outer",
                "arguments", Map.of("size", "outer",
                        "kind", Map.of("type", "when", "source", "b", "when", Map.of("a", "x", "b", "y")))),
                "ns", "id");
        Map<?, ?> map = (Map<?, ?>) expanded;
        assertEquals("outer", map.get("value"), "an argument from further out wins");
        assertEquals("y", map.get("other"));

        Object number = templates.expand(Map.of("v", "${n}",
                        "template", List.of(), "arguments", Map.of("n", Map.of("type", "expression",
                        "expression", "2 * (3 + 4)", "value_type", "int"))), "ns", "id");
        assertNotNull(number);
    }

    @Test
    void versionKeysFollowCraftEnginesComparison() {
        int v1214 = CraftEngineYaml.number(McVersion.of(1, 21, 4));
        assertTrue(CraftEngineYaml.matches(">=1.21.2", v1214));
        assertFalse(CraftEngineYaml.matches("<1.21.2", v1214));
        assertTrue(CraftEngineYaml.matches("1.21.2~1.21.4", v1214));
        assertTrue(CraftEngineYaml.matches("1.21.4", v1214));
        assertTrue(CraftEngineYaml.matches(">=1.21.4#trident", v1214), "a # suffix only makes the key unique");
        assertFalse(CraftEngineYaml.matches(">=26.1", v1214));
        assertTrue(CraftEngineYaml.matches(">=26.1", CraftEngineYaml.number(McVersion.of(26, 2))));
    }

    @Test
    void furnitureBecomesAPlacedModelWithItsHitboxSeatLightAndFacing() throws IOException {
        templates();
        write("decor/pack.yml", "namespace: decor\n");
        write("decor/configuration/furniture.yml", """
                items:
                  decor:chair:
                    data:
                      item_name: Chair
                    model: minecraft:item/custom/chair
                    behavior:
                      type: furniture_item
                      rules:
                        ground:
                          rotation: four
                          alignment: center
                      furniture:
                        settings:
                          item: decor:chair
                          sounds:
                            break: minecraft:block.wood.break
                        variants:
                          ground:
                            elements:
                              - item: decor:chair
                                display_transform: NONE
                                translation: 0,0.5,0
                            hitboxes:
                              - position: 0,0,0
                                type: interaction
                                width: 0.7
                                height: 1.2
                                seats:
                                  - 0,0.3,-0.1 0 true
                        loot:
                          template: default:loot_table/furniture
                          arguments:
                            item: decor:chair
                  decor:bench:
                    model: minecraft:item/custom/bench
                    behavior:
                      type: furniture_item
                      furniture: decor:bench
                  decor:lamp:
                    model: minecraft:item/custom/lamp
                    behavior:
                      type: furniture_item
                      rules:
                        ground: {rotation: sixteen}
                      furniture:
                        behaviors:
                          type: glowing_furniture
                          lights:
                            - 0,0,0 14
                        variants:
                          ground:
                            elements:
                              - item: decor:lamp
                                translation: 0,0.5,0
                                scale: 2
                          wall:
                            elements:
                              - item: decor:lamp
                          ground_lit:
                            elements:
                              - item: decor:lamp
                furniture:
                  decor:bench:
                    variants:
                      ground:
                        elements:
                          - item: decor:other_model
                            translation: 0,0,0
                        hitboxes:
                          - position: 0,0,0
                            type: shulker
                            seats:
                              - 0,0,-0.1 90 true
                              - 1,0,-0.1 90 true
                """);
        LoadReport report = load();
        assertEquals("", errors(report));

        ModelInfo chair = placed(report, "decor:chair");
        assertNotNull(chair);
        DefinitionNode place = definition(report, "decor:chair").body().node("place").orElseThrow();
        assertEquals("cardinal", place.string("facing").orElseThrow());
        assertEquals("floor", place.string("surface").orElseThrow());
        assertEquals(0.7, place.decimal("width").orElseThrow());
        assertEquals(1.2, place.decimal("height").orElseThrow());
        DefinitionNode seat = place.node("seat").orElseThrow();
        assertEquals(0.5, seat.decimal("y").orElseThrow(), 1e-9, "0.3 + the 0.2 between their seat and ours");
        assertEquals(-0.1, seat.decimal("z").orElseThrow());
        assertFalse(place.has("drop"), "furniture_item drops the piece itself");
        assertTrue(warned(report, "decor:chair", "seat height"));
        assertTrue(warned(report, "decor:chair", "furniture settings sounds"));

        DefinitionNode bench = definition(report, "decor:bench").body().node("place").orElseThrow();
        assertTrue(bench.bool("solid").orElseThrow(), "a shulker hitbox is solid");
        assertEquals("free", bench.string("facing").orElseThrow(), "CraftEngine's default rotation is any");
        assertTrue(warned(report, "decor:bench", "has 2 seats"));
        assertTrue(warned(report, "decor:bench", "the seat's own yaw"));
        assertTrue(warned(report, "decor:bench", "shows decor:other_model's model"));
        assertTrue(warned(report, "decor:bench", "translated by 0,0,0"));

        DefinitionNode lamp = definition(report, "decor:lamp").body().node("place").orElseThrow();
        assertEquals(14, lamp.integer("light").orElseThrow());
        assertEquals("any", lamp.string("surface").orElseThrow(), "ground and wall variants");
        assertEquals(2.0, lamp.decimal("scale").orElseThrow());
        assertEquals("diagonal", lamp.string("facing").orElseThrow());
        assertTrue(warned(report, "decor:lamp", "rotation sixteen"));
        assertTrue(warned(report, "decor:lamp", "ground_lit"));
    }

    @Test
    void imagesBecomeIconsAndGridsBecomeOneIconPerCell() throws IOException {
        write("chat/pack.yml", "namespace: chat\n");
        write("chat/configuration/emoji.yml", """
                images:
                  chat:emojis:
                    ascent: 9
                    font: minecraft:default
                    file: minecraft:font/image/emojis.png
                    grid_size: 2,2
                  chat:coin:
                    height: 8
                    file: minecraft:font/image/coin
                    char: \\ue001
                    font: chat:hud
                  chat:space:
                    height: -3
                    ascent: -5000
                    file: minecraft:font/offset/space
                  chat:first_face:
                    ref: chat:emojis:0:1
                emoji:
                  chat:smile:
                    image: chat:emojis:1:0
                    permission: emoji.smile
                    keywords:
                      - ':smile:'
                      - ':)'
                  chat:coin:
                    image: chat:coin
                    keywords: [':coin:']
                """);
        bytes("chat/resourcepack/assets/minecraft/textures/font/image/emojis.png", png(22, 22));

        LoadReport report = load();
        assertEquals("", errors(report));
        var icons = IconDefinitions.parse(report).icons();
        IconInfo cell = icons.get(id("chat:emojis_1_0"));
        assertNotNull(cell, icons.keySet().toString());
        assertEquals("minecraft:font/image/emojis", cell.file());
        assertEquals(11, cell.height(), "22 pixels over 2 rows, read off the PNG");
        assertEquals(9, cell.ascent());
        assertEquals(2, cell.rows());
        assertEquals(3, cell.cell(), "row 1, column 0 of 2 columns is the third cell");
        assertEquals(2, icons.get(id("chat:first_face")).cell(), "a ref is the cell it points at");
        assertEquals(icons.get(id("chat:emojis_1_0")).cell(), icons.get(id("chat:smile")).cell());
        assertNotNull(icons.get(id("chat:coin")), "a keyword naming the image itself adds nothing");
        assertNull(icons.get(id("chat:space")));
        assertTrue(warned(report, "chat:space", "negative"));
        assertTrue(warned(report, "chat:smile", ":)"));
        assertTrue(warned(report, "chat:smile", "emoji.smile"));
        assertTrue(warned(report, "chat:coin", "chat:hud"));
        assertTrue(warned(report, null, "pins its character"));
    }

    @Test
    void recipesComeAcrossWithTheirOwnIdsAndVanillaMaterials() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/recipes.yml", """
                items:
                  gems:ruby:
                    texture: minecraft:item/ruby
                  gems:ruby_block:
                    texture: minecraft:item/ruby_block
                recipes:
                  gems:ruby_block:
                    type: shaped
                    pattern: [AAA, AAA, AAA]
                    ingredients:
                      A: gems:ruby
                    result: {id: gems:ruby_block, count: 1}
                  gems:ruby_from_block:
                    type: crafting_shapeless
                    ingredients: [gems:ruby_block]
                    result: {id: gems:ruby, count: 9}
                  gems:ruby_smelt:
                    type: smelting
                    experience: 1.0
                    ingredient: minecraft:redstone_block
                    result: gems:ruby
                  gems:planks:
                    type: shaped
                    pattern: [A]
                    ingredients:
                      A: '#minecraft:planks'
                    result: gems:ruby
                  gems:either:
                    type: shapeless
                    ingredients:
                      - [minecraft:stick, minecraft:bone]
                    result: gems:ruby
                  gems:upgrade:
                    type: smithing_transform
                    base: minecraft:diamond_sword
                    result: gems:ruby
                  gems:stones:
                    type: stonecutting
                    ingredient: stone
                    result: {id: minecraft:stone_bricks, count: 2}
                """);
        LoadReport report = load();
        var recipes = RecipeDefinitions.parse(report).recipes();
        RecipeInfo block = recipes.get(id("gems:ruby_block"));
        assertNotNull(block, recipes.keySet().toString());
        assertEquals("gems:ruby", block.keys().get("A"));
        assertEquals(9, recipes.get(id("gems:ruby_from_block")).amount());
        RecipeInfo smelt = recipes.get(id("gems:ruby_smelt"));
        assertEquals("REDSTONE_BLOCK", smelt.ingredients().get(0));
        assertEquals(80, smelt.cookingTime(), "CraftEngine's own default");
        assertEquals("STONE", recipes.get(id("gems:stones")).ingredients().get(0), "a bare id is minecraft's");
        assertEquals("STONE_BRICKS", recipes.get(id("gems:stones")).result());
        assertNull(recipes.get(id("gems:planks")));
        assertNull(recipes.get(id("gems:either")));
        assertNull(recipes.get(id("gems:upgrade")));
        assertTrue(warned(report, "gems:planks", "#minecraft:planks"));
        assertTrue(warned(report, "gems:upgrade", "has no template, addition"));
    }

    @Test
    void smithingBrewingAndTransformRecipes() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        // The upgrade is CraftEngine's own default topaz bow, verbatim.
        write("gems/configuration/recipes.yml", """
                items:
                  gems:topaz:
                    texture: minecraft:item/topaz
                  gems:topaz_bow:
                    texture: minecraft:item/topaz_bow
                  gems:tonic:
                    texture: minecraft:item/tonic
                recipes:
                  gems:topaz_bow:
                    type: smithing_transform
                    base: minecraft:bow
                    addition: gems:topaz
                    template_type: gems:topaz
                    result:
                      id: gems:topaz_bow
                      count: 1
                  gems:plain_bow:
                    type: smithing_transform
                    base: minecraft:bow
                    addition: gems:topaz
                    template-type: minecraft:netherite_upgrade_smithing_template
                    merge_components: false
                    result: gems:topaz_bow
                  gems:topaz_trim:
                    type: smithing_trim
                    template_type: gems:topaz
                    base: minecraft:iron_chestplate
                    addition: minecraft:amethyst_shard
                    pattern: silence
                  gems:tonic:
                    type: brewing
                    container: minecraft:potion
                    ingredient: gems:topaz
                    result: gems:tonic
                  gems:topaz_wrap:
                    type: shaped_transform
                    pattern: [AAA, ABA, AAA]
                    ingredients:
                      A: gems:topaz
                      B:
                        item: minecraft:bow
                        source: true
                    result: gems:topaz_bow
                  gems:dyed:
                    type: dye
                    target: gems:topaz_bow
                    dye: minecraft:red_dye
                """);

        LoadReport report = load();
        var recipes = RecipeDefinitions.parse(report).recipes();

        RecipeInfo bow = recipes.get(id("gems:topaz_bow"));
        assertNotNull(bow, recipes.keySet().toString());
        assertEquals(RecipeInfo.Type.SMITHING, bow.type());
        assertEquals("gems:topaz", bow.template().orElseThrow());
        assertEquals("BOW", bow.base().orElseThrow());
        assertEquals("gems:topaz", bow.addition().orElseThrow());
        assertEquals("gems:topaz_bow", bow.result());
        assertTrue(bow.copyData());
        RecipeInfo plain = recipes.get(id("gems:plain_bow"));
        assertEquals("NETHERITE_UPGRADE_SMITHING_TEMPLATE", plain.template().orElseThrow(), "kebab case too");
        assertFalse(plain.copyData(), "merge_components: false");

        RecipeInfo trim = recipes.get(id("gems:topaz_trim"));
        assertEquals(RecipeInfo.Type.SMITHING_TRIM, trim.type());
        assertEquals("minecraft:silence", trim.pattern().orElseThrow());

        RecipeInfo tonic = recipes.get(id("gems:tonic"));
        assertEquals(RecipeInfo.Type.BREWING, tonic.type());
        assertEquals("POTION", tonic.base().orElseThrow());
        assertEquals(List.of("gems:topaz"), tonic.ingredients());

        RecipeInfo wrap = recipes.get(id("gems:topaz_wrap"));
        assertEquals(RecipeInfo.Type.SHAPED, wrap.type());
        assertEquals("BOW", wrap.keys().get("B"));
        assertTrue(warned(report, "gems:topaz_wrap", "made fresh"));

        assertNull(recipes.get(id("gems:dyed")));
        assertTrue(warned(report, "gems:dyed", "dyes an item"));
    }

    @Test
    void soundsAndJukeboxSongsAndCategories() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/sounds.yml", """
                sounds:
                  gems:chime:
                    subtitle: subtitles.gems.chime
                    sounds:
                      - name: gems:bells/chime
                        volume: 0.5
                        stream: true
                      - gems:bells/chime2
                  gems:boom:
                    sounds:
                      - custom/boom
                  minecraft:block.stone.break:
                    replace: true
                    sounds: [custom/stone]
                jukebox_songs:
                  gems:song:
                    sound: gems:chime
                    length: 10
                categories:
                  gems:all:
                    name: Gems
                    list: [gems:chime]
                lang:
                  en_us:
                    subtitles.gems.chime: A chime rings
                """);
        bytes("gems/resourcepack/assets/gems/sounds/bells/chime.ogg", new byte[] {1});
        bytes("gems/resourcepack/assets/minecraft/sounds/custom/boom.ogg", new byte[] {1});

        LoadReport report = load();
        var sounds = SoundDefinitions.parse(report).sounds();
        SoundInfo chime = sounds.get(id("gems:chime"));
        assertEquals("bells/chime", chime.file());
        assertEquals("A chime rings", chime.subtitle().orElseThrow());
        assertTrue(chime.stream());
        assertEquals("minecraft:custom/boom", sounds.get(id("gems:boom")).file(), "a bare name is minecraft's");
        assertTrue(warned(report, "minecraft:block.stone.break", "replaces a vanilla sound"));
        assertTrue(warned(report, "gems:chime", "picks one of 2"));
        assertTrue(warned(report, null, "jukebox_songs entry skipped"));
        assertTrue(warned(report, null, "categories entry skipped"));

        BuildReport built = new PackBuilder().with(new SoundAssets()).build(content, out, report);
        assertTrue(built.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
                built.diagnostics().toString());
        assertTrue(read(built).get("assets/gems/sounds.json").contains("\"minecraft:custom/boom\""));
    }

    @Test
    void armourTakesItsSlotAndItsEquipmentsLayerArt() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/armour.yml", """
                items:
                  gems:ruby_helmet:
                    material: golden_helmet
                    settings:
                      equipment:
                        asset_id: gems:ruby
                    texture: minecraft:item/custom/ruby_helmet
                  gems:ruby_leggings:
                    material: golden_leggings
                    data:
                      equippable:
                        slot: legs
                        asset_id: gems:ruby
                  gems:crown:
                    material: paper
                    data:
                      equippable:
                        slot: head
                        asset_id: minecraft:gold
                equipments:
                  gems:ruby:
                    type: component
                    humanoid: minecraft:entity/equipment/humanoid/ruby
                    humanoid_leggings: minecraft:entity/equipment/humanoid_leggings/ruby
                    wings: minecraft:entity/equipment/wings/ruby
                """);
        LoadReport report = load();
        ItemInfo helmet = item(report, "gems:ruby_helmet");
        assertEquals("head", helmet.armor().orElseThrow(), "no slot: the material's");
        assertEquals("minecraft:ruby", helmet.armorTexture().orElseThrow());
        ItemInfo leggings = item(report, "gems:ruby_leggings");
        assertEquals("legs", leggings.armor().orElseThrow());
        assertEquals("minecraft:ruby", leggings.armorTexture().orElseThrow());
        assertEquals("minecraft:gold", item(report, "gems:crown").armorTexture().orElseThrow());
        assertTrue(warned(report, "gems:ruby", "wings"));

        bytes("gems/resourcepack/assets/minecraft/textures/entity/equipment/humanoid/ruby.png", png(64, 32));
        BuildReport built = new PackBuilder().with(new ItemAssets()).build(content, out, report);
        assertTrue(read(built).get("assets/gems/equipment/ruby_helmet.json").contains("\"minecraft:ruby\""));
    }

    @Test
    void trimArmourDrawnFromAnyTextureIsServedAtTheEquipmentPath() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/armour.yml", """
                items:
                  gems:jade_boots:
                    material: chainmail_boots
                    settings:
                      equipment:
                        asset_id: gems:jade
                equipments:
                  gems:jade:
                    type: trim
                    humanoid: minecraft:custom/armor/jade_layer_1
                """);
        bytes("gems/resourcepack/assets/minecraft/textures/custom/armor/jade_layer_1.png", png(64, 32));
        LoadReport report = load();
        assertEquals("feet", item(report, "gems:jade_boots").armor().orElseThrow());

        BuildReport built = new PackBuilder().with(new ItemAssets()).build(content, out, report);
        assertTrue(read(built).containsKey("assets/gems/textures/entity/equipment/humanoid/jade_boots.png"));
    }

    @Test
    void complexModelDefinitionsWearTheirDefaultModelAndSayWhatSwitches() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/items.yml", """
                items:
                  gems:trident:
                    material: trident
                    model:
                      type: minecraft:select
                      property: minecraft:display_context
                      cases:
                        - when: [gui, ground]
                          model:
                            type: minecraft:model
                            path: minecraft:item/custom/trident
                      fallback:
                        type: minecraft:condition
                        property: minecraft:using_item
                        on_true:
                          type: minecraft:model
                          path: minecraft:item/custom/trident_throwing
                        on_false:
                          type: minecraft:model
                          path: minecraft:item/custom/trident_in_hand
                  gems:generated:
                    model:
                      type: minecraft:model
                      path: minecraft:item/custom/generated
                      generation:
                        parent: minecraft:item/generated
                        textures:
                          layer0: item/custom/generated
                  gems:bow:
                    material: bow
                    textures:
                      - minecraft:item/custom/bow
                      - minecraft:item/custom/bow_pulling_0
                      - minecraft:item/custom/bow_pulling_1
                      - minecraft:item/custom/bow_pulling_2
                  gems:events:
                    material: paper
                    events:
                      - on: right_click
                        type: particle
                        particle: minecraft:heart
                    behavior:
                      type: compostable_item
                """);
        LoadReport report = load();
        assertEquals("minecraft:item/custom/trident_in_hand", item(report, "gems:trident").model().orElseThrow());
        assertTrue(warned(report, "gems:trident", "select minecraft:display_context"));
        String generated = item(report, "gems:generated").model().orElseThrow();
        assertTrue(generated.contains("\"layer0\":\"minecraft:item/custom/generated\""), generated);
        String bow = item(report, "gems:bow").model().orElseThrow();
        assertTrue(bow.contains("minecraft:item/bow"), bow);
        assertTrue(warned(report, "gems:bow", "only the first"));
        assertTrue(warned(report, "gems:events", "particle"));
        assertTrue(warned(report, "gems:events", "compostable_item"));
    }

    @Test
    void oneCraftEngineFileInsideAPackOfOursLoads() throws IOException {
        write("mypack/pack.yml", "name: Mine\n");
        write("mypack/items/native.yml", "plain:\n  material: STICK\n");
        write("mypack/items/craftengine.yml", """
                items:
                  mypack:ce_item:
                    material: paper
                    data:
                      item_name: From CraftEngine
                """);
        write("mypack/loose.yml", """
                items:
                  ce_bare:
                    material: paper
                """);
        LoadReport report = load();
        assertEquals("", errors(report));
        assertNotNull(item(report, "mypack:plain"));
        assertEquals("From CraftEngine", item(report, "mypack:ce_item").name().orElseThrow());
        assertNotNull(item(report, "mypack:ce_bare"), "a bare id is the pack's namespace, here the folder");
    }

    @Test
    void aFolderNamedDifferentlyFromItsNamespaceSaysSoAndReferencesStillResolve() throws IOException {
        write("default_assets/pack.yml", "namespace: default\n");
        write("default_assets/configuration/items.yml", """
                items:
                  default:topaz:
                    texture: minecraft:item/custom/topaz
                recipes:
                  default:topaz_block:
                    type: shapeless
                    ingredients: [default:topaz, default:topaz]
                    result: default:topaz
                """);
        LoadReport report = load();
        assertNotNull(item(report, "default_assets:topaz"));
        assertEquals("default_assets:topaz",
                RecipeDefinitions.parse(report).recipes().get(id("default_assets:topaz_block")).result());
        assertTrue(warned(report, null, "Rename the folder to default"));
    }

    @Test
    void disabledPacksAndEntriesAreSkippedAndAPackWithoutPackYmlLoads() throws IOException {
        write("off/pack.yml", "namespace: off\nenable: false\n");
        write("off/configuration/items.yml", "items:\n  off:thing:\n    material: paper\n");
        write("bare/configuration/items.yml", """
                items:
                  bare:thing:
                    material: paper
                  bare:parked:
                    enable: false
                    material: paper
                """);
        LoadReport report = load();
        assertNull(item(report, "off:thing"));
        assertNotNull(item(report, "bare:thing"));
        assertNull(item(report, "bare:parked"));
    }

    /** What Studio's CraftEngine export writes, read back. */
    @Test
    void studiosCraftEngineExportRoundTrips() throws IOException {
        write("shop/pack.yml", """
                author: Studio
                version: 1.0.0
                description: Shop
                namespace: shop
                enable: true
                """);
        write("shop/configuration/items.yml", """
                items:
                  shop:ruby:
                    material: paper
                    data:
                      item_name: <!i>Ruby
                    model: shop:item/ruby
                  shop:stool:
                    material: paper
                    data:
                      item_name: <!i>Stool
                    model: shop:item/stool
                    behavior:
                      type: furniture_item
                      furniture:
                        settings:
                          item: shop:stool
                        variants:
                          ground:
                            elements:
                              - item: shop:stool
                                display_transform: NONE
                                translation: 0,0.5,0
                            hitboxes:
                              - position: 0,0,0
                                type: interaction
                                width: 1
                                height: 0.6
                                seats:
                                  - 0,0.3,0
                        loot:
                          pools:
                            - rolls: 1
                              entries:
                                - type: furniture_item
                  shop:marble:
                    material: paper
                    data:
                      item_name: <!i>Marble
                    model: shop:item/marble
                    behavior:
                      type: block_item
                      block: shop:marble
                blocks:
                  shop:marble:
                    settings:
                      hardness: 1.5
                    loot:
                      pools:
                        - rolls: 1
                          entries:
                            - type: item
                              item: shop:marble
                    state:
                      auto_state: solid
                      model:
                        path: shop:item/marble
                """);
        write("shop/configuration/images.yml", """
                images:
                  shop:heart:
                    file: shop:font/heart.png
                    char: "\\ue000"
                    font: minecraft:default
                    height: 9
                    ascent: 7
                emoji:
                  shop:heart:
                    image: shop:heart
                    keywords: [':heart:']
                """);
        write("shop/configuration/categories.yml", """
                categories:
                  shop:all:
                    name: Shop
                    icon: shop:ruby
                    list: [shop:ruby, shop:stool, shop:marble]
                """);
        for (String model : List.of("ruby", "stool", "marble")) {
            write("shop/resourcepack/assets/shop/models/item/" + model + ".json", """
                    {"elements": [{"from": [0,0,0], "to": [16,8,16], "faces": {}}],
                     "textures": {"0": "shop:item/%s"}}
                    """.formatted(model));
            bytes("shop/resourcepack/assets/shop/textures/item/" + model + ".png", png(16, 16));
        }
        bytes("shop/resourcepack/assets/shop/textures/font/heart.png", png(9, 9));

        LoadReport report = load();
        assertEquals("", errors(report));
        assertEquals("Ruby", item(report, "shop:ruby").name().orElseThrow());
        assertEquals("shop:item/ruby", item(report, "shop:ruby").model().orElseThrow());
        DefinitionNode stool = definition(report, "shop:stool").body().node("place").orElseThrow();
        assertEquals(0.6, stool.decimal("height").orElseThrow());
        assertEquals(0.5, stool.decimal("seat").orElseThrow(), 1e-9);
        BlockInfo marble = BlockDefinitions.parse(report).blocks().get(id("shop:marble"));
        assertEquals("Marble", marble.name().orElseThrow());
        assertFalse(marble.drop().isPresent(), "it drops itself");
        IconInfo heart = IconDefinitions.parse(report).icons().get(id("shop:heart"));
        assertEquals("shop:font/heart", heart.file());
        assertTrue(warned(report, null, "categories entry skipped"));
        assertFalse(warned(report, "shop:heart", "skipped"), "the :heart: keyword is the icon itself");

        BlockStates states = new BlockStates(out.resolve("state").toFile());
        for (BlockInfo block : BlockDefinitions.parse(report).blocks().values()) states.numberFor(block);
        BuildReport built = new PackBuilder().with(new ItemAssets()).with(new BlockAssets(states))
                .with(new FontAssets()).build(content, out, report);
        assertTrue(built.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
                built.diagnostics().toString());
        Map<String, String> zip = read(built);
        assertTrue(zip.get("assets/shop/models/item/stool.json").contains("shop:item/stool"));
        assertTrue(zip.get("assets/shop/models/block/marble.json").contains("shop:item/marble"));
        assertTrue(zip.get("assets/minecraft/font/default.json").contains("shop:font/heart.png"));
    }

    @Test
    void ourOwnFormatTakesANamespacedModelAndAnInlineOne() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/a.yml", """
                vanilla_look:
                  material: PAPER
                  model: minecraft:item/custom/look
                inline:
                  material: PAPER
                  model:
                    parent: minecraft:item/handheld
                    textures:
                      layer0: mypack:item/inline
                worn:
                  material: PAPER
                  armor: head
                  armor-texture: minecraft:gold
                """);
        write("mypack/overrides/models/item/custom/look.json", """
                {"parent": "item/generated", "textures": {"layer0": "item/custom/look"}}
                """);
        bytes("mypack/assets/textures/item/inline.png", png(16, 16));
        LoadReport report = load();
        BuildReport built = new PackBuilder().with(new ItemAssets()).build(content, out, report);
        Map<String, String> zip = read(built);
        assertTrue(zip.get("assets/mypack/models/item/vanilla_look.json").contains("minecraft:item/custom/look"));
        assertTrue(zip.get("assets/mypack/models/item/inline.json").contains("minecraft:item/handheld"));
        assertTrue(zip.get("assets/mypack/equipment/worn.json").contains("\"minecraft:gold\""));
        assertTrue(built.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
                built.diagnostics().toString());
    }

    @Test
    void recognitionNeverTakesOneOfOurOwnFiles() {
        assertFalse(CraftEngine.looksLikeOne(DefinitionNode.of(Map.of("items",
                Map.of("material", "PAPER")))), "an item called items");
        assertTrue(CraftEngine.looksLikeOne(DefinitionNode.of(Map.of("items",
                Map.of("ns:thing", Map.of("material", "paper"))))));
        assertTrue(CraftEngine.looksLikeOne(DefinitionNode.of(Map.of("lang#items",
                Map.of("en_us", Map.of("a", "b"))))));
        assertFalse(CraftEngine.looksLikeOne(DefinitionNode.of(Map.of("ruby",
                Map.of("material", "DIAMOND")))));
        assertFalse(CraftEngine.looksLikeOne(DefinitionNode.of(Map.of("info", Map.of("namespace", "x"),
                "items", Map.of("ruby", Map.of("display_name", "Ruby"))))), "an ItemsAdder file");
    }

    @Test
    void textConversion() {
        CraftEngineText.Converted converted = CraftEngineText.convert(
                "<!i><gradient:#FF0000:#00FF00>Hot</gradient> <bold><lang:k></bold> <i18n:missing>",
                key -> key.equals("k") ? "<yellow>Cold" : null);
        assertEquals("&x&f&f&0&0&0&0Hot &l&eCold missing", converted.text());
        assertTrue(converted.dropped().contains("gradient"));
        assertTrue(converted.unresolved().contains("missing"));
    }

    private static Map<String, String> read(BuildReport report) throws IOException {
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

    // ---- events -------------------------------------------------------------

    private static java.util.List<String> steps(java.util.List<ai.resourcepack.engine.api.ItemAction> actions) {
        return actions == null ? java.util.List.of() : actions.stream().map(Object::toString).toList();
    }

    @Test
    void eventsOnItemsBlocksAndFurnitureBecomeActions() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/things.yml", """
                items:
                  gems:wand:
                    material: stick
                    events:
                      - on: right_click
                        conditions:
                          - type: permission
                            permission: gems.wand
                        functions:
                          - type: command
                            command: "effect give <arg:player.name> speed 5"
                          - type: play_sound
                            sound: minecraft:entity.player.levelup
                            volume: 0.5
                          - type: particle
                            particle: minecraft:heart
                      - on: consume
                        type: potion_effect
                        potion_effect: minecraft:regeneration
                        duration: 100
                        amplifier: 1
                      - on: step
                        type: message
                        message: "stepped"
                  gems:bell:
                    material: paper
                    model: minecraft:item/custom/bell
                    behavior:
                      type: furniture_item
                      furniture:
                        variants:
                          ground:
                            elements:
                              - item: gems:bell
                        events:
                          right_click:
                            - type: command
                              command: "say <arg:player.name> rang"
                              as_player: true
                            - type: message
                              message: "<gold>Ding"
                              overlay: true
                          break:
                            - type: run
                              delay: 20
                              functions:
                                - type: message
                                  message: later
                blocks:
                  gems:button:
                    state:
                      auto_state: note_block
                      model:
                        path: minecraft:block/custom/button
                    events:
                      - on: right_click
                        functions:
                          - type: message
                            message: "<red>pressed"
                          - type: cancel_event
                        conditions:
                          - type: is_sneaking
                      - on: break
                        functions:
                          - type: command
                            command: "say broken"
                """);

        LoadReport report = load();
        ItemInfo wand = item(report, "gems:wand");
        assertEquals(java.util.List.of("permission: gems.wand", "console: effect give {player} speed 5",
                        "sound: minecraft:entity.player.levelup 0.5"),
                steps(wand.actions(ai.resourcepack.engine.api.ItemAction.Trigger.RIGHT_CLICK)));
        assertEquals(java.util.List.of("effect: REGENERATION 5 2"),
                steps(wand.actions(ai.resourcepack.engine.api.ItemAction.Trigger.CONSUME)));
        assertTrue(warned(report, "gems:wand", "particle"));
        assertTrue(warned(report, "gems:wand", "step"));

        ItemInfo bell = item(report, "gems:bell");
        assertEquals(java.util.List.of("run: say {player} rang", "actionbar: &6Ding"),
                steps(bell.actions(ai.resourcepack.engine.api.ItemAction.Trigger.INTERACT)));
        assertTrue(warned(report, "gems:bell", "delay"));

        var button = ai.resourcepack.engine.core.block.BlockDefinitions.parse(report).blocks().get(id("gems:button"));
        assertTrue(steps(button.actions().get(ai.resourcepack.engine.api.ItemAction.Trigger.INTERACT)).isEmpty(),
                "a condition that is not a permission is a branch");
        assertEquals(java.util.List.of("console: say broken"),
                steps(button.actions().get(ai.resourcepack.engine.api.ItemAction.Trigger.REMOVE)));
        assertTrue(warned(report, "gems:button", "is_sneaking"));
    }

    @Test
    void entitiesSayWhyThereIsNoMobInThem() throws IOException {
        write("gems/pack.yml", "namespace: gems\n");
        write("gems/configuration/entities.yml", """
                entities:
                  minecraft:zombie:
                    settings:
                      attributes:
                        minecraft:max_health: 40
                      tags:
                        - gems:undead
                """);
        LoadReport report = load();
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.message().contains("1 entities entry skipped")
                && d.message().contains("no model, name or spawn")), report.diagnostics().toString());
        assertTrue(report.definitions().stream().noneMatch(d -> d.kind() == ai.resourcepack.engine.api.ContentKind.ENTITY));
    }
}
