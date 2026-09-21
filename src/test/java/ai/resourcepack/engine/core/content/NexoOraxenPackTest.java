package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.block.BlockDefinitions;
import ai.resourcepack.engine.core.item.ItemDefinitions;
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
}
