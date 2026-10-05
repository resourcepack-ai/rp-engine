package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.ItemAction;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A block's own {@code actions:}, which are the actions of the item that
 * places it and of the block where it stands.
 */
class BlockDefinitionsTest {

    @TempDir
    Path content;

    @Test
    void aBlockCarriesItsActions() throws IOException {
        Path pack = content.resolve("mypack");
        Files.createDirectories(pack.resolve("blocks"));
        Files.writeString(pack.resolve("pack.yml"), "name: mine\n", StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("blocks/bells.yml"), """
                bell:
                  model: bell
                  actions:
                    interact:
                      - sound: minecraft:block.bell.use
                      - console: "say {player} rang it"
                    remove:
                      - message: "&7Silence."
                """, StandardCharsets.UTF_8);

        BlockDefinitions.Result result = BlockDefinitions.parse(
                new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED));
        BlockInfo bell = result.blocks().get(ContentId.parse("mypack:bell").orElseThrow());

        assertEquals(2, bell.actions().get(ItemAction.Trigger.INTERACT).size());
        assertEquals(ItemAction.Kind.MESSAGE, bell.actions().get(ItemAction.Trigger.REMOVE).get(0).kind());
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.message().contains("actions")),
                result.diagnostics().toString());
    }
}
