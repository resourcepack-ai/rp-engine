package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.font.IconDefinitions;
import ai.resourcepack.engine.core.block.BlockDefinitions;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.recipe.RecipeDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void staticGlyphsBecomeIconsAndAnimatedOrMultiBitmapGlyphsExplainThemselves() throws IOException {
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
        assertEquals(1, icons.size());
        assertTrue(load().diagnostics().stream().anyMatch(d -> d.message().contains("Animated GIF glyphs")));
        assertTrue(load().diagnostics().stream().anyMatch(d -> d.message().contains("Multi-bitmap glyphs")));
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
    }
}
