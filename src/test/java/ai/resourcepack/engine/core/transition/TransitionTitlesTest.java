package ai.resourcepack.engine.core.transition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The title that plays a transition is its character in the trigger colour, as legacy hex. */
class TransitionTitlesTest {

    @Test
    void aTriggerColourIsWrittenAsLegacyHex() {
        // Studio's stingerColor(0, "in"): red 236, green 4, blue 252.
        assertEquals("§x§e§c§0§4§f§c", TransitionTitles.legacyHex("#ec04fc"));
        assertEquals("§x§e§c§0§4§f§c", TransitionTitles.legacyHex("#EC04FC"));
        assertEquals("§x§e§c§0§4§f§c", TransitionTitles.title("#ec04fc", ""));
    }

    @Test
    void onlyAWholeHexColourIsOne() {
        assertTrue(TransitionTitles.isColor("#ec04fc"));
        assertFalse(TransitionTitles.isColor("ec04fc"));
        assertFalse(TransitionTitles.isColor("#f40"));
        assertFalse(TransitionTitles.isColor(null));
        assertThrows(IllegalArgumentException.class, () -> TransitionTitles.legacyHex("red"));
    }

    @Test
    void aBareSoundIsVanillas() {
        assertEquals("minecraft:entity.player.attack.sweep", TransitionTitles.soundKey("entity.player.attack.sweep"));
        assertEquals("studio:whoosh", TransitionTitles.soundKey("studio:whoosh"));
    }
}
