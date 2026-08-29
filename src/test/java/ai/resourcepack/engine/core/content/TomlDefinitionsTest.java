package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TOML is a spelling, not a format.
 *
 * <p>Every one of these asserts that a definition written in TOML arrives at
 * the parsers as the same thing the YAML would have — which is the whole
 * claim, and the only thing that could quietly stop being true.
 */
class TomlDefinitionsTest {

    @TempDir
    Path content;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(content);
        write("mypack/pack.yml", "{}\n");
    }

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

    @Test
    void aTomlFileDeclaresContent() throws IOException {
        write("mypack/items/gems.toml", """
                [ruby]
                material = "DIAMOND"
                name = "Ruby"
                glow = true
                stack = 16
                """);

        ItemInfo ruby = item("mypack:ruby");

        assertEquals("DIAMOND", ruby.material());
        assertEquals("Ruby", ruby.name().orElseThrow());
        assertTrue(ruby.glow());
        assertEquals(16, ruby.maxStack().orElseThrow());
    }

    @Test
    void aNestedTableIsANestedBlock() throws IOException {
        write("mypack/items/chairs.toml", """
                [chair]
                material = "PAPER"
                model = "chair"

                [chair.place]
                facing = "diagonal"
                seat = 0.5
                """);

        // `place` is read by ModelDefinitions off the same node the YAML form
        // produces; if the nesting were lost this would not be a model at all.
        assertEquals("chair", item("mypack:chair").model().orElseThrow());
    }

    @Test
    void aListIsAList() throws IOException {
        write("mypack/items/lore.toml", """
                [ruby]
                material = "DIAMOND"
                lore = ["one", "two"]
                """);

        assertEquals(2, item("mypack:ruby").lore().size());
    }

    @Test
    void bothSpellingsLiveInOneFolder() throws IOException {
        write("mypack/items/a.toml", "[ruby]\nmaterial = \"DIAMOND\"\n");
        write("mypack/items/b.yml", "sapphire:\n  material: DIAMOND\n");

        assertEquals(2, ItemDefinitions.parse(load()).items().size());
    }

    /**
     * The one that catches people: a slash means something to TOML, so an id
     * with one in it has to be quoted. The message says so.
     */
    @Test
    void anIdWithASlashNeedsQuoting() throws IOException {
        write("mypack/items/weapons.toml", """
                ["weapons/sword"]
                material = "IRON_SWORD"
                """);

        assertEquals("IRON_SWORD", item("mypack:weapons/sword").material());
    }

    @Test
    void aBrokenFileNamesTheFileAndSaysWhy() throws IOException {
        write("mypack/items/broken.toml", "[ruby\nmaterial = \"DIAMOND\"\n");

        LoadReport loaded = load();

        assertTrue(loaded.diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR
                        && d.message().contains("TOML")
                        && d.message().contains("quoted")));
    }
}
