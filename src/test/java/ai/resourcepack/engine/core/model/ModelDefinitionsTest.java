package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placed model is a property of an item rather than a thing beside one, because
 * an id is unique across the whole registry and {@code mypack:chair} cannot be
 * both. These tests are as much about that decision as about the parsing.
 */
class ModelDefinitionsTest {

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

    /** An item definition with whatever place block the test needs. */
    private void chair(String placeableBlock) throws IOException {
        write("mypack/items/a.yml",
                "chair:\n  material: PAPER\n  model: chair\n" + placeableBlock);
    }

    private ModelDefinitions.Result parse() {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl())
                .load(content, ContentSource.AUTHORED);
        return ModelDefinitions.parse(loaded, ItemDefinitions.parse(loaded).items());
    }

    private static ModelInfo one(ModelDefinitions.Result result, String id) {
        return result.model().get(ContentId.parse(id).orElseThrow());
    }

    @Test
    void readsAPlacedModel() throws IOException {
        chair("  place:\n    facing: diagonal\n    scale: 1.5\n"
                + "    width: 0.9\n    height: 1.2\n    solid: true\n");

        ModelInfo model = one(parse(), "mypack:chair");

        assertEquals(ModelInfo.Facing.DIAGONAL, model.facing());
        assertEquals(1.5f, model.scale());
        assertEquals(0.9f, model.width());
        assertEquals(1.2f, model.height());
        assertTrue(model.solid());
    }

    // ---- light, surface and drops ---------------------------------------

    @Test
    void aPieceCanGiveOffLightAndSayWhatItSticksTo() throws IOException {
        chair("  place:\n    light: 14\n    surface: wall\n    drop: mypack:shard\n");

        ModelInfo model = one(parse(), "mypack:chair");

        assertEquals(14, model.light());
        assertEquals(ModelInfo.Surface.WALL, model.surface());
        assertEquals("mypack:shard", model.drop().orElseThrow().toString());
    }

    @Test
    void aPieceUsuallySaysNoneOfThat() throws IOException {
        chair("  place: {}\n");

        ModelInfo model = one(parse(), "mypack:chair");

        assertEquals(0, model.light(), "no light block is placed");
        assertEquals(ModelInfo.Surface.FLOOR, model.surface());
        assertTrue(model.drop().isEmpty(), "it gives back the item it was placed from");
    }

    @Test
    void aLightOutsideTheRangeIsClampedRatherThanRefused() throws IOException {
        chair("  place:\n    light: 40\n");

        assertEquals(15, one(parse(), "mypack:chair").light());
    }

    @Test
    void aSurfaceNobodyRecognisesLeavesItOnTheFloor() throws IOException {
        chair("  place:\n    surface: sideways\n");

        assertEquals(ModelInfo.Surface.FLOOR, one(parse(), "mypack:chair").surface());
    }

    @Test
    void aSurfaceDecidesWhichFaceAPlacementIsAllowedAgainst() {
        // The rule the placement listener asks. A torch goes on a wall and a
        // chandelier under a ceiling, and getting this backwards is somebody's
        // lamp stuck to the underside of a floor.
        assertTrue(ModelInfo.Surface.FLOOR.accepts(org.bukkit.block.BlockFace.UP));
        assertFalse(ModelInfo.Surface.FLOOR.accepts(org.bukkit.block.BlockFace.NORTH));

        assertTrue(ModelInfo.Surface.WALL.accepts(org.bukkit.block.BlockFace.NORTH));
        assertFalse(ModelInfo.Surface.WALL.accepts(org.bukkit.block.BlockFace.UP));
        assertFalse(ModelInfo.Surface.WALL.accepts(org.bukkit.block.BlockFace.DOWN));

        assertTrue(ModelInfo.Surface.CEILING.accepts(org.bukkit.block.BlockFace.DOWN));
        assertFalse(ModelInfo.Surface.CEILING.accepts(org.bukkit.block.BlockFace.UP));

        assertTrue(ModelInfo.Surface.ANY.accepts(org.bukkit.block.BlockFace.DOWN));
        assertTrue(ModelInfo.Surface.ANY.accepts(org.bukkit.block.BlockFace.UP));
    }

    @Test
    void theItemAndThePlacedModelAreTheSameId() throws IOException {
        chair("  place: {}\n");

        ModelInfo model = one(parse(), "mypack:chair");

        // One chair, one id. They cannot disagree about which model to use
        // because there is only one of each.
        assertEquals(model.id(), model.item());
    }

    @Test
    void anItemWithNoPlaceableBlockCannotBePlaced() throws IOException {
        chair("");

        assertTrue(parse().model().isEmpty());
    }

    @Test
    void theDefaultsAreAWholeBlockFacingCardinal() throws IOException {
        chair("  place: {}\n");

        ModelInfo model = one(parse(), "mypack:chair");

        assertEquals(ModelInfo.Facing.CARDINAL, model.facing());
        assertEquals(1f, model.scale());
        assertEquals(1f, model.width());
        assertEquals(1f, model.height());
        assertFalse(model.solid(), "walk-through by default: a display entity has no collision");
    }

    @Test
    void anItemThatDidNotParseCannotBePlaced() throws IOException {
        write("mypack/items/a.yml", "chair:\n  material: NOT_A_THING\n  place: {}\n");

        ModelDefinitions.Result result = parse();

        // The bad material already has a diagnostic of its own. Saying it
        // twice helps nobody.
        assertTrue(result.model().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void anUnknownFacingWarnsAndFallsBackToCardinal() throws IOException {
        chair("  place:\n    facing: sideways\n");

        ModelDefinitions.Result result = parse();

        assertEquals(ModelInfo.Facing.CARDINAL, one(result, "mypack:chair").facing());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("cardinal"));
    }

    @Test
    void everyFacingIsAccepted() throws IOException {
        for (ModelInfo.Facing facing : ModelInfo.Facing.values()) {
            chair("  place:\n    facing: " + facing.name().toLowerCase() + "\n");
            assertEquals(facing, one(parse(), "mypack:chair").facing());
        }
    }

    @Test
    void anImpossibleHitboxIsClampedRatherThanRefused() throws IOException {
        chair("  place:\n    width: 0\n    height: 400\n");

        ModelDefinitions.Result result = parse();
        ModelInfo model = one(result, "mypack:chair");

        // A hitbox of 0 is a model nobody can break: it would be
        // permanent with no way to find out why.
        assertTrue(model.width() > 0f);
        assertEquals(16f, model.height());
        assertEquals(2, result.diagnostics().size());
    }

    @Test
    void aSizeThatIsNotANumberWarnsAndUsesTheDefault() throws IOException {
        chair("  place:\n    scale: big\n");

        ModelDefinitions.Result result = parse();

        assertEquals(1f, one(result, "mypack:chair").scale());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    @Test
    void aPieceCanBeFoundByTheItemThatPlacesIt() throws IOException {
        chair("  place: {}\n");

        ModelDefinitions.Result result = parse();

        assertEquals(ContentId.parse("mypack:chair").orElseThrow(),
                result.byItem(ContentId.parse("mypack:chair").orElseThrow()).orElseThrow().id());
        assertTrue(result.byItem(ContentId.parse("mypack:ruby").orElseThrow()).isEmpty());
    }

    @Test
    void nothingLoadedMeansNothingParsed() {
        assertTrue(ModelDefinitions.parse(null, null).model().isEmpty());
        assertTrue(ModelDefinitions.parse(LoadReport.empty(), null).model().isEmpty());
    }
}
