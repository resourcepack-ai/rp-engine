package ai.resourcepack.engine.core.storage;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.StorageSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One parser for everything that holds items. A placed model and a custom block
 * both go through this, so what it says here is what {@code rows: 4} means
 * everywhere.
 */
class StorageDefinitionsTest {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private Optional<StorageSpec> parse(Object storage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storage", storage);
        return StorageDefinitions.parse(DefinitionNode.of(body), "test.yml", "cabinet", diagnostics);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    void aFullBlockReadsEverySetting() {
        StorageSpec spec = parse(map("type", "personal", "rows", 5, "title", "&6Wardrobe",
                "open-sound", "mypack:creak", "close-sound", "minecraft:block.barrel.close")).orElseThrow();

        assertEquals(StorageSpec.Type.PERSONAL, spec.type());
        assertEquals(5, spec.rows());
        assertEquals(45, spec.size());
        assertEquals("&6Wardrobe", spec.title().orElseThrow());
        assertEquals("mypack:creak", spec.openSound().orElseThrow());
        assertEquals("minecraft:block.barrel.close", spec.closeSound().orElseThrow());
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    @Test
    void anEmptyBlockIsAThreeRowChestThatSoundsLikeOne() {
        StorageSpec spec = parse(map()).orElseThrow();

        assertEquals(StorageSpec.Type.CHEST, spec.type());
        assertEquals(3, spec.rows());
        assertTrue(spec.title().isEmpty(), "the holder's own name is used");
        assertEquals(StorageSpec.CHEST_OPEN, spec.openSound().orElseThrow());
        assertEquals(StorageSpec.CHEST_CLOSE, spec.closeSound().orElseThrow());
    }

    @Test
    void theShortFormsAreWhatPeopleWrite() {
        assertEquals(StorageSpec.chest(), parse(true).orElseThrow());
        assertTrue(parse(false).isEmpty(), "storage: false is no storage");
        assertEquals(StorageSpec.Type.SHULKER, parse("shulker").orElseThrow().type());
        assertEquals(StorageSpec.Type.ENDERCHEST, parse("ender_chest").orElseThrow().type(),
                "the spelling everybody reaches for first");
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    @Test
    void anEnderChestSoundsLikeOneAndABinIsSilent() {
        StorageSpec ender = parse("enderchest").orElseThrow();
        assertEquals(StorageSpec.ENDER_OPEN, ender.openSound().orElseThrow());
        assertEquals(StorageSpec.ENDER_CLOSE, ender.closeSound().orElseThrow());

        StorageSpec bin = parse("disposal").orElseThrow();
        assertTrue(bin.openSound().isEmpty());
        assertTrue(bin.closeSound().isEmpty());
    }

    @Test
    void aShulkerKeepsItsDefaultChestSounds() {
        assertEquals(StorageSpec.CHEST_OPEN, parse("shulker").orElseThrow().openSound().orElseThrow());
    }

    @Test
    void rowsOutsideOneToSixAreClampedAndSaidSo() {
        assertEquals(6, parse(map("rows", 9)).orElseThrow().rows());
        assertEquals(1, parse(map("rows", 0)).orElseThrow().rows());
        assertEquals(2, diagnostics.size());
    }

    @Test
    void rowsThatAreNotANumberFallBackToThree() {
        assertEquals(3, parse(map("rows", "lots")).orElseThrow().rows());
        assertEquals(1, diagnostics.size());
    }

    @Test
    void rowsOnAnEnderChestAreToldTheyDoNothing() {
        parse(map("type", "enderchest", "rows", 6));

        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).message().contains("enderchest"));
    }

    @Test
    void anUnknownTypeIsAChestAndAWarning() {
        assertEquals(StorageSpec.Type.CHEST, parse(map("type", "wardrobe")).orElseThrow().type());
        assertEquals(1, diagnostics.size());
    }

    @Test
    void aSoundCanBeSwitchedOff() {
        StorageSpec spec = parse(map("open-sound", "none", "close-sound", false)).orElseThrow();

        assertTrue(spec.openSound().isEmpty());
        assertTrue(spec.closeSound().isEmpty());
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void aSoundThatIsNotAKeyKeepsTheDefault() {
        StorageSpec spec = parse(map("open-sound", "chest open")).orElseThrow();

        assertEquals(StorageSpec.CHEST_OPEN, spec.openSound().orElseThrow());
        assertEquals(1, diagnostics.size());
    }

    @Test
    void noStorageKeyIsNoStorage() {
        assertTrue(StorageDefinitions.parse(DefinitionNode.of(map("light", 3)), "t", "w", diagnostics).isEmpty());
        assertTrue(StorageDefinitions.parse(null, "t", "w", diagnostics).isEmpty());
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void aListIsNotAStorageBlock() {
        assertFalse(parse(List.of("chest")).isPresent());
        assertEquals(1, diagnostics.size());
    }

    @Test
    void whichTypesKeepContentsOfTheirOwn() {
        // The ones whose contents spill (or travel) when the piece is broken.
        assertTrue(StorageSpec.Type.CHEST.keepsContents());
        assertTrue(StorageSpec.Type.SHULKER.keepsContents());
        assertFalse(StorageSpec.Type.PERSONAL.keepsContents(), "the player's, not the piece's");
        assertFalse(StorageSpec.Type.ENDERCHEST.keepsContents());
        assertFalse(StorageSpec.Type.DISPOSAL.keepsContents());
    }
}
