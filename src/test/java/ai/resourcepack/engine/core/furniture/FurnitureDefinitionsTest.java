package ai.resourcepack.engine.core.furniture;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.FurnitureInfo;
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
 * Furniture is a property of an item rather than a thing beside one, because
 * an id is unique across the whole registry and {@code mypack:chair} cannot be
 * both. These tests are as much about that decision as about the parsing.
 */
class FurnitureDefinitionsTest {

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

    /** An item definition with whatever furniture block the test needs. */
    private void chair(String furnitureBlock) throws IOException {
        write("mypack/items/a.yml",
                "chair:\n  material: PAPER\n  geometry: chair\n" + furnitureBlock);
    }

    private FurnitureDefinitions.Result parse() {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl())
                .load(content, ContentSource.AUTHORED);
        return FurnitureDefinitions.parse(loaded, ItemDefinitions.parse(loaded).items());
    }

    private static FurnitureInfo one(FurnitureDefinitions.Result result, String id) {
        return result.furniture().get(ContentId.parse(id).orElseThrow());
    }

    @Test
    void readsAPieceOfFurniture() throws IOException {
        chair("  furniture:\n    facing: diagonal\n    scale: 1.5\n"
                + "    width: 0.9\n    height: 1.2\n    solid: true\n");

        FurnitureInfo furniture = one(parse(), "mypack:chair");

        assertEquals(FurnitureInfo.Facing.DIAGONAL, furniture.facing());
        assertEquals(1.5f, furniture.scale());
        assertEquals(0.9f, furniture.width());
        assertEquals(1.2f, furniture.height());
        assertTrue(furniture.solid());
    }

    @Test
    void theItemAndTheFurnitureAreTheSameId() throws IOException {
        chair("  furniture: {}\n");

        FurnitureInfo furniture = one(parse(), "mypack:chair");

        // One chair, one id. They cannot disagree about which model to use
        // because there is only one of each.
        assertEquals(furniture.id(), furniture.item());
    }

    @Test
    void anItemWithNoFurnitureBlockIsJustAnItem() throws IOException {
        chair("");

        assertTrue(parse().furniture().isEmpty());
    }

    @Test
    void theDefaultsAreAWholeBlockFacingCardinal() throws IOException {
        chair("  furniture: {}\n");

        FurnitureInfo furniture = one(parse(), "mypack:chair");

        assertEquals(FurnitureInfo.Facing.CARDINAL, furniture.facing());
        assertEquals(1f, furniture.scale());
        assertEquals(1f, furniture.width());
        assertEquals(1f, furniture.height());
        assertFalse(furniture.solid(), "walk-through by default: a display entity has no collision");
    }

    @Test
    void anItemThatDidNotParseCannotBeFurniture() throws IOException {
        write("mypack/items/a.yml", "chair:\n  material: NOT_A_THING\n  furniture: {}\n");

        FurnitureDefinitions.Result result = parse();

        // The bad material already has a diagnostic of its own. Saying it
        // twice helps nobody.
        assertTrue(result.furniture().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void anUnknownFacingWarnsAndFallsBackToCardinal() throws IOException {
        chair("  furniture:\n    facing: sideways\n");

        FurnitureDefinitions.Result result = parse();

        assertEquals(FurnitureInfo.Facing.CARDINAL, one(result, "mypack:chair").facing());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("cardinal"));
    }

    @Test
    void everyFacingIsAccepted() throws IOException {
        for (FurnitureInfo.Facing facing : FurnitureInfo.Facing.values()) {
            chair("  furniture:\n    facing: " + facing.name().toLowerCase() + "\n");
            assertEquals(facing, one(parse(), "mypack:chair").facing());
        }
    }

    @Test
    void anImpossibleHitboxIsClampedRatherThanRefused() throws IOException {
        chair("  furniture:\n    width: 0\n    height: 400\n");

        FurnitureDefinitions.Result result = parse();
        FurnitureInfo furniture = one(result, "mypack:chair");

        // A hitbox of 0 is furniture nobody can break: the piece would be
        // permanent with no way to find out why.
        assertTrue(furniture.width() > 0f);
        assertEquals(16f, furniture.height());
        assertEquals(2, result.diagnostics().size());
    }

    @Test
    void aSizeThatIsNotANumberWarnsAndUsesTheDefault() throws IOException {
        chair("  furniture:\n    scale: big\n");

        FurnitureDefinitions.Result result = parse();

        assertEquals(1f, one(result, "mypack:chair").scale());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    @Test
    void aPieceCanBeFoundByTheItemThatPlacesIt() throws IOException {
        chair("  furniture: {}\n");

        FurnitureDefinitions.Result result = parse();

        assertEquals(ContentId.parse("mypack:chair").orElseThrow(),
                result.byItem(ContentId.parse("mypack:chair").orElseThrow()).orElseThrow().id());
        assertTrue(result.byItem(ContentId.parse("mypack:ruby").orElseThrow()).isEmpty());
    }

    @Test
    void nothingLoadedMeansNothingParsed() {
        assertTrue(FurnitureDefinitions.parse(null, null).furniture().isEmpty());
        assertTrue(FurnitureDefinitions.parse(LoadReport.empty(), null).furniture().isEmpty());
    }
}
