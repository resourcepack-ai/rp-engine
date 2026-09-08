package ai.resourcepack.engine.core.font;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link OverlayRuntime#fill} — the one place a live server value reaches an
 * overlay.
 *
 * <p>Worth its own test because every other route into an overlay is compiled
 * into the pack: a shape's geometry is GLSL and cannot move at runtime, so this
 * substitution is the whole of what {@code Overlays.set} can do. If it is
 * wrong, the feature's only dynamic part is wrong.
 */
class OverlayFillTest {

    @Test
    void fillsAPlaceholderFromTheValues() {
        assertEquals("Mana 42", OverlayRuntime.fill("Mana {mana}", Map.of("mana", "42")));
    }

    @Test
    void fillsSeveralInOneLine() {
        assertEquals("7/20 hearts",
                OverlayRuntime.fill("{hp}/{max} hearts", Map.of("hp", "7", "max", "20")));
    }

    /**
     * An unset placeholder is a gap, never the literal braces.
     *
     * <p>A HUD with a hole in it reads as "no value yet"; one with
     * {@code {mana}} in it reads as a broken pack, and that is the one people
     * report.
     */
    @Test
    void anUnsetPlaceholderBecomesEmpty() {
        assertEquals("Mana ", OverlayRuntime.fill("Mana {mana}", Map.of()));
    }

    /**
     * An unclosed brace is text.
     *
     * <p>Somebody either wrote it on purpose or wrote it wrong, and eating the
     * rest of the line is worse than showing it either way.
     */
    @Test
    void anUnclosedBraceIsLeftAlone() {
        assertEquals("Mana {mana", OverlayRuntime.fill("Mana {mana", Map.of("mana", "42")));
    }

    @Test
    void textWithNoPlaceholderIsUntouched() {
        assertEquals("Ready", OverlayRuntime.fill("Ready", Map.of("mana", "42")));
    }

    @Test
    void nullAndEmptyAreEmpty() {
        assertEquals("", OverlayRuntime.fill(null, Map.of()));
        assertEquals("", OverlayRuntime.fill("", Map.of()));
    }

    /** A value containing a brace must not be re-scanned as another placeholder. */
    @Test
    void aValueIsNotItselfExpanded() {
        assertEquals("{max}", OverlayRuntime.fill("{a}", Map.of("a", "{max}", "max", "20")));
    }
}
