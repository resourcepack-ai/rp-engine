package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A progress bar is the one run whose LENGTH the pack cannot know.
 *
 * <p>Everything else in an overlay is a string somebody wrote or a glyph
 * somebody baked. A bar is a number resolved a tick before the line is sent and
 * then turned into pixels, so the engine is the only place the arithmetic can
 * happen — which makes it the only place it can be wrong. Three things are
 * asserted here and each has its own failure:
 *
 * <ul>
 *   <li>The fill lands on the exact pixel. Greedy over powers of two reaches
 *       every whole length, so a health bar moves smoothly; a rounding to the
 *       nearest rectangle would make it jump in eights.</li>
 *   <li>The run's ADVANCE is the pixels it drew, not the length of the string
 *       it drew them with. The string is glyphs and shift characters, so
 *       measuring it would put every label after the bar somewhere else.</li>
 *   <li>The background is always full length whatever the value is, because the
 *       fill is drawn over it rather than beside it.</li>
 * </ul>
 */
class ShaderBarTest {

    private static final String PLUS = "abcdefghij";
    private static final String MINUS = "ABCDEFGHIJ";

    /** The rectangles, widest first, as Studio allocates them. */
    private static final List<OverlayInfo.OverlayRun.Bar.Glyph> GLYPHS = List.of(
            new OverlayInfo.OverlayRun.Bar.Glyph("", 64),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 32),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 16),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 8),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 4),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 2),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 1));

    private static final int TOTAL = 100;

    private OverlayInfo overlay(String value, String max) {
        return OverlayInfo.pushed(ContentId.parse("test:hud").orElseThrow(), "canvas", "",
                        OverlayInfo.Slot.ACTION_BAR, "#fe0001", "", "", List.of(),
                        List.of(
                                new OverlayInfo.OverlayRun("", 0, "", "", "#f00008", 0, Map.of(),
                                        new OverlayInfo.OverlayRun.Bar(GLYPHS, TOTAL, value, max, false)),
                                new OverlayInfo.OverlayRun("", 0, "", "", "#f00010", 0, Map.of(),
                                        new OverlayInfo.OverlayRun.Bar(GLYPHS, TOTAL, value, max, true)),
                                new OverlayInfo.OverlayRun("", 140, "after", "", "#f00018")))
                .withCursor(257, PLUS, MINUS);
    }

    /** How many pixels a component moved the cursor, counting glyphs and shifts. */
    private static int pixels(String drawn) {
        int width = 0;
        for (int i = 0; i < drawn.length(); i++) {
            String character = String.valueOf(drawn.charAt(i));
            int step = PLUS.indexOf(drawn.charAt(i));
            int back = MINUS.indexOf(drawn.charAt(i));
            if (step >= 0) {
                width += 1 << step;
            } else if (back >= 0) {
                width -= 1 << back;
            } else {
                width += GLYPHS.stream()
                        .filter(glyph -> glyph.character().equals(character))
                        .mapToInt(glyph -> glyph.px() + 1)
                        .findFirst()
                        .orElse(0);
            }
        }
        return width;
    }

    /** The bar runs as the composer produced them, in order. */
    private List<TextComponent> barParts(String value, String max) {
        BaseComponent[] parts = Overlays.compose(overlay(value, max), Map.of("mana", value, "manaMax", max), null);
        List<TextComponent> out = new java.util.ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            TextComponent part = (TextComponent) parts[i];
            if (part.getColorRaw() != null && !part.getText().equals("after")) {
                out.add(part);
            }
        }
        return out;
    }

    @Test void theFillLandsOnTheExactPixel() {
        // Every tenth, and the two ends. Greedy over powers of two reaches each
        // one exactly — anything less would make a bar move in visible steps.
        for (int percent = 0; percent <= 100; percent += 10) {
            List<TextComponent> parts = barParts(String.valueOf(percent), "100");
            int drawn = percent == 0 ? 0 : pixels(parts.get(1).getText());
            assertEquals(percent, drawn, "a bar at " + percent + "% should be " + percent + " pixels");
        }
    }

    @Test void theBackgroundIsAlwaysFullLength() {
        for (String value : List.of("0", "37", "100", "9999")) {
            assertEquals(TOTAL, pixels(barParts(value, "100").get(0).getText()),
                    "the fill is drawn OVER the background, so the background never shortens");
        }
    }

    @Test void anEmptyFillDrawsNothingRatherThanAnEmptyComponent() {
        assertEquals(1, barParts("0", "100").size(), "a zero-pixel fill is skipped, not sent blank");
    }

    @Test void aValuePastFullIsClampedAndSoIsOneBelowEmpty() {
        assertEquals(TOTAL, pixels(barParts("250", "100").get(1).getText()));
        assertEquals(1, barParts("-40", "100").size(), "below empty draws nothing");
    }

    @Test void aMaxOfZeroDrawsAnEmptyBarRatherThanDividing() {
        assertEquals(1, barParts("50", "0").size());
    }

    @Test void theLabelAfterABarLandsWhereItWasPut() {
        // The whole point of the run's advance being the PIXELS rather than the
        // string: the string is glyphs and shift characters, and measuring it
        // against vanilla's widths would put this label somewhere else entirely.
        for (String value : List.of("0", "5", "63", "100")) {
            BaseComponent[] parts = Overlays.compose(overlay(value, "100"), Map.of(), null);
            int cursor = 257;
            for (int i = 1; i < parts.length; i++) {
                TextComponent part = (TextComponent) parts[i];
                if (part.getColorRaw() == null) {
                    cursor += pixels(part.getText());
                    continue;
                }
                if (part.getText().equals("after")) {
                    assertEquals(140, cursor, "at " + value + "%");
                    cursor += TextWidth.of(part.getText());
                } else {
                    cursor = pixels(part.getText());
                }
            }
        }
    }

    @Test void aBarSurvivesTheBossBarToo() {
        String legacy = Overlays.legacyComposed(overlay("50", "100"), Map.of(), null);
        assertTrue(legacy.contains(GLYPHS.get(1).character()), "a 32-pixel rectangle is in a 50-pixel fill");
        assertTrue(legacy.contains("§x§f§0§0§0§1§0"), "the fill run keeps its own colour");
    }
}
