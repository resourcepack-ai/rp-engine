package ai.resourcepack.engine.core.stinger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The title that plays a stinger is its character in the trigger colour, as legacy hex. */
class StingerTitlesTest {

    @Test
    void aTriggerColourIsWrittenAsLegacyHex() {
        // Studio's stingerColor(0, "in"): red 236, green 4, blue 252.
        assertEquals("§x§e§c§0§4§f§c", StingerTitles.legacyHex("#ec04fc"));
        assertEquals("§x§e§c§0§4§f§c", StingerTitles.legacyHex("#EC04FC"));
        assertEquals("§x§e§c§0§4§f§c", StingerTitles.title("#ec04fc", ""));
    }

    @Test
    void onlyAWholeHexColourIsOne() {
        assertTrue(StingerTitles.isColor("#ec04fc"));
        assertFalse(StingerTitles.isColor("ec04fc"));
        assertFalse(StingerTitles.isColor("#f40"));
        assertFalse(StingerTitles.isColor(null));
        assertThrows(IllegalArgumentException.class, () -> StingerTitles.legacyHex("red"));
    }

    @Test
    void aBareSoundIsVanillas() {
        assertEquals("minecraft:entity.player.attack.sweep", StingerTitles.soundKey("entity.player.attack.sweep"));
        assertEquals("studio:whoosh", StingerTitles.soundKey("studio:whoosh"));
    }
}
