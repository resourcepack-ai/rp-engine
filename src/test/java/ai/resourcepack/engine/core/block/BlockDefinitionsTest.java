package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.EntityDrop;
import ai.resourcepack.engine.api.ItemAction;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    void aCropDropsByItsStage() throws IOException {
        Path pack = content.resolve("mypack");
        Files.createDirectories(pack.resolve("blocks"));
        Files.writeString(pack.resolve("pack.yml"), "name: mine\n", StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("blocks/crops.yml"), """
                tomato:
                  base: plant
                  stages: [tomato_stage_0, tomato_stage_1, tomato_stage_2]
                  grow: 2m
                  drop: mypack:tomato_seeds
                  drops:
                    tomato_stage_0: []
                    "2":
                      - mypack:tomato 1to3
                      - {item: mypack:tomato_seeds, min: 1, max: 2, chance: 0.5}
                    "age=9": mypack:tomato
                    stage_x: mypack:tomato
                    "age=1": exp 5
                pepper:
                  base: plant
                  stages: [pepper_0, pepper_1]
                  drops:
                    - minecraft:wheat_seeds
                """, StandardCharsets.UTF_8);

        BlockDefinitions.Result result = BlockDefinitions.parse(
                new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED));
        BlockInfo tomato = result.blocks().get(ContentId.parse("mypack:tomato").orElseThrow());

        // Stage 0 gives nothing, by its model's name.
        assertEquals(List.of(), tomato.dropsIn("age=0").orElseThrow());
        // Stage 1's only line was a mob's (exp), so it is an entry with nothing in it.
        assertEquals(List.of(), tomato.dropsIn("age=1").orElseThrow());
        // The ripe stage, by its number: both lines, as written.
        List<EntityDrop> ripe = tomato.dropsIn("age=2").orElseThrow();
        assertEquals(2, ripe.size());
        assertEquals("mypack:tomato", ripe.get(0).item());
        assertEquals(1, ripe.get(0).min());
        assertEquals(3, ripe.get(0).max());
        assertEquals(0.5, ripe.get(1).chance());
        assertEquals(2, ripe.get(1).max());
        // drop: is still what a state with no entry would give.
        assertEquals("mypack:tomato_seeds", tomato.drop().orElseThrow().toString());

        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("age=9")),
                result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("stage_x")),
                result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("lines are a mob's")),
                result.diagnostics().toString());

        BlockInfo pepper = result.blocks().get(ContentId.parse("mypack:pepper").orElseThrow());
        assertEquals("minecraft:wheat_seeds", pepper.dropsIn("age=1").orElseThrow().get(0).item());
        assertEquals("minecraft:wheat_seeds", pepper.dropsIn("age=0").orElseThrow().get(0).item());
    }

    @Test
    void theMostSpecificStateWins() {
        BlockInfo block = BlockInfo.of(ContentId.parse("mypack:x").orElseThrow(), null, "x", 0, null, null, null)
                .withStateDrops(List.of(
                        new BlockInfo.StateDrops("", List.of(EntityDrop.of("a", 1, 1, 1))),
                        new BlockInfo.StateDrops("age=2", List.of(EntityDrop.of("b", 1, 1, 1))),
                        new BlockInfo.StateDrops("age=2,lit=true", List.of(EntityDrop.of("c", 1, 1, 1)))));
        assertEquals("a", block.dropsIn("age=0,lit=true").orElseThrow().get(0).item());
        assertEquals("b", block.dropsIn("age=2,lit=false").orElseThrow().get(0).item());
        assertEquals("c", block.dropsIn("age=2,lit=true").orElseThrow().get(0).item());
        assertTrue(BlockInfo.of(ContentId.parse("mypack:y").orElseThrow(), null, "y", 0, null, null, null)
                .dropsIn("").isEmpty());
    }
}
