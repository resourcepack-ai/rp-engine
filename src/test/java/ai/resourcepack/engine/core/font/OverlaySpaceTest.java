package ai.resourcepack.engine.core.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Overlays.space} and {@code TextWidth} have to agree to the pixel: a
 * plugin lays a value out with one and the engine places the runs with the
 * other, so a pixel of disagreement is a name drawn a pixel into the icon
 * beside it.
 */
class OverlaySpaceTest {

    @Test
    void everySpaceMeasuresAsWhatItWasAskedFor() {
        for (int px = -300; px <= 300; px++) {
            assertEquals(px, TextWidth.of(Overlays.space(px)), "space(" + px + ")");
        }
    }

    @Test
    void zeroIsNothing() {
        assertEquals("", Overlays.space(0));
    }

    @Test
    void anIconMeasuresAsItsRecordedWidth() {
        int codepoint = 0xE7F0;
        TextWidth.glyph(codepoint, 18);
        String icon = new String(Character.toChars(codepoint));
        assertEquals(19, TextWidth.of(icon));
        // An icon, then a step back over it and one pixel more: the panel trick.
        assertEquals(18, TextWidth.of(icon + Overlays.space(-1)));
        assertEquals(0, TextWidth.of(icon + Overlays.space(-19)));
    }
}
