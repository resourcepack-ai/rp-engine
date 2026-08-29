package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.font.IconDefinitions;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /** What cannot come across is said out loud, with the count. */
    @Test
    void blocksAreRefusedRatherThanDroppedQuietly() throws IOException {
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
                """);

        assertTrue(load().diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.WARNING
                        && d.message().contains("blocks were skipped")));
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
}
