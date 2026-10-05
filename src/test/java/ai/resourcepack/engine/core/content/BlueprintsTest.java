package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A folder of ModelEngine blueprints, dropped in as it came.
 *
 * <p>Their content format is the {@code .bbmodel} files and nothing else —
 * there is no YAML to translate, because the save file carries everything. So
 * the whole of "support" is that each blueprint gets an id somebody can give,
 * place or bind, named after the file.
 */
class BlueprintsTest {

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

    private Map<ContentId, ItemInfo> items() {
        return ItemDefinitions.parse(load()).items();
    }

    /** The file only has to be a .bbmodel by name here; the builder reads it. */
    private void blueprint(String path) throws IOException {
        write(path, "{\"meta\":{},\"elements\":[],\"outliner\":[],\"textures\":[]}\n");
    }

    @Test
    void everyBlueprintBecomesAnItemThatWearsIt() throws IOException {
        write("mypack/pack.yml", "{}\n");
        blueprint("mypack/blueprints/golem.bbmodel");
        blueprint("mypack/blueprints/dragon.bbmodel");

        Map<ContentId, ItemInfo> items = items();

        assertEquals(2, items.size());
        ItemInfo golem = items.get(ContentId.parse("mypack:golem").orElseThrow());
        assertEquals("golem", golem.model().orElseThrow());
    }

    @Test
    void aSubfolderOrganisesAndDoesNotReachTheId() throws IOException {
        write("mypack/pack.yml", "{}\n");
        blueprint("mypack/blueprints/bosses/dragon.bbmodel");

        assertTrue(items().containsKey(ContentId.parse("mypack:dragon").orElseThrow()));
    }

    /** A pack of theirs has no pack.yml either, and should still load. */
    @Test
    void aBlueprintFolderIsNotEnoughOnItsOwn() throws IOException {
        blueprint("mypack/blueprints/golem.bbmodel");

        // No pack.yml and no ItemsAdder config: this is a folder somebody
        // dropped in the wrong place, and saying so beats claiming a namespace
        // for it.
        assertTrue(load().hasErrors());
    }

    /** Something written by hand wins over something derived from a file name. */
    @Test
    void anItemOfYourOwnUnderTheSameIdWins() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/golem.yml", "golem:\n  material: DIAMOND\n  model: golem\n");
        blueprint("mypack/blueprints/golem.bbmodel");

        LoadReport loaded = load();

        assertEquals("DIAMOND", ItemDefinitions.parse(loaded).items()
                .get(ContentId.parse("mypack:golem").orElseThrow()).material());
        // And the duplicate is reported rather than silently dropped.
        assertTrue(loaded.diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR
                        && d.message().contains("Already defined")));
    }

    @Test
    void aFileNameThatIsNotAnIdIsSaidRatherThanSkipped() throws IOException {
        write("mypack/pack.yml", "{}\n");
        blueprint("mypack/blueprints/My Golem.bbmodel");

        assertTrue(load().diagnostics().stream()
                .anyMatch(d -> d.message().contains("file name is its id")));
    }
}
