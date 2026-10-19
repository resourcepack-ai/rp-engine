package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a plugin can change about an overlay while it is on screen: whether a
 * run is drawn at all, which of its named colours it is in, and a bar's length
 * and thresholds. All of it is the engine choosing what text to send and in
 * which mark, from the same values a {@code {name}} placeholder reads.
 */
class OverlayLiveTest {

    private static final String PLUS = "abcdefghij";
    private static final String MINUS = "ABCDEFGHIJ";

    private static final List<OverlayInfo.OverlayRun.Bar.Glyph> GLYPHS = List.of(
            new OverlayInfo.OverlayRun.Bar.Glyph("", 64),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 32),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 16),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 8),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 4),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 2),
            new OverlayInfo.OverlayRun.Bar.Glyph("", 1));

    private static OverlayInfo overlay(OverlayInfo.OverlayRun... runs) {
        return OverlayInfo.pushed(ContentId.parse("test:hud").orElseThrow(), "canvas", "",
                        OverlayInfo.Slot.ACTION_BAR, "#fe0001", "", "", List.of(), List.of(runs))
                .withCursor(257, PLUS, MINUS);
    }

    /** The coloured parts the composer sent, skipping the canvas and the shifts. */
    private static List<TextComponent> drawn(OverlayInfo info, Map<String, String> values) {
        BaseComponent[] parts = Overlays.compose(info, values, null);
        List<TextComponent> out = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            TextComponent part = (TextComponent) parts[i];
            if (part.getColorRaw() != null) {
                out.add(part);
            }
        }
        return out;
    }

    private static String color(TextComponent part) {
        return part.getColorRaw().getName().toLowerCase();
    }

    private static int pixels(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            String character = String.valueOf(text.charAt(i));
            int step = PLUS.indexOf(text.charAt(i));
            int back = MINUS.indexOf(text.charAt(i));
            if (step >= 0) {
                width += 1 << step;
            } else if (back >= 0) {
                width -= 1 << back;
            } else {
                width += GLYPHS.stream().filter(g -> g.character().equals(character)).mapToInt(g -> g.px() + 1).findFirst().orElse(0);
            }
        }
        return width;
    }

    private static OverlayInfo.OverlayRun label(String text, int x, String mark) {
        return new OverlayInfo.OverlayRun("", x, text, "", mark);
    }

    @Test void aRunWhoseConditionFailsIsNotSent() {
        OverlayInfo info = overlay(
                label("VIP", 0, "#f00008").live("{rank} == \"vip\"", List.of(), ""),
                label("after", 40, "#f00010"));
        assertEquals(List.of("after"), drawn(info, Map.of("rank", "member")).stream().map(TextComponent::getText).toList());
        assertEquals(List.of("VIP", "after"), drawn(info, Map.of("rank", "vip")).stream().map(TextComponent::getText).toList());
        assertEquals(List.of("after"), drawn(info, Map.of()).stream().map(TextComponent::getText).toList(),
                "nothing set is not vip");
    }

    @Test void aLabelAfterAHiddenOneLandsWhereItWasPut() {
        OverlayInfo info = overlay(
                label("Hidden words", 0, "#f00008").live("false", List.of(), ""),
                label("after", 40, "#f00010"));
        BaseComponent[] parts = Overlays.compose(info, Map.of(), null);
        int cursor = 257;
        for (int i = 1; i < parts.length; i++) {
            TextComponent part = (TextComponent) parts[i];
            if (part.getColorRaw() == null) {
                cursor += pixels(part.getText());
            } else {
                assertEquals("after", part.getText());
                assertEquals(40, cursor);
                cursor += TextWidth.of(part.getText());
            }
        }
    }

    @Test void aNamedStateSwitchesTheMarkAndAnUnknownOneKeepsTheOwn() {
        OverlayInfo info = overlay(label("Status", 0, "#f00008").live("",
                List.of(new OverlayInfo.OverlayRun.State("poisoned", "#f00010"),
                        new OverlayInfo.OverlayRun.State("frozen", "#f00018")),
                "status"));
        assertEquals("#f00010", color(drawn(info, Map.of("status", "poisoned")).get(0)));
        assertEquals("#f00018", color(drawn(info, Map.of("status", "FROZEN")).get(0)), "matched without case");
        assertEquals("#f00008", color(drawn(info, Map.of("status", "burning")).get(0)));
        assertEquals("#f00008", color(drawn(info, Map.of()).get(0)));
    }

    private static OverlayInfo.OverlayRun barRun(boolean fill, String mark, String length, List<OverlayInfo.OverlayRun.Bar.Shade> shades) {
        return new OverlayInfo.OverlayRun("", 0, "", "", mark, 0, Map.of(),
                new OverlayInfo.OverlayRun.Bar(GLYPHS, 100, "mana", "100", fill, 0, 0, shades).withLength(length));
    }

    @Test void aBarsLengthComesFromAValueAndBothRunsAgree() {
        OverlayInfo info = overlay(barRun(false, "#f00008", "len", List.of()), barRun(true, "#f00010", "len", List.of()));
        List<TextComponent> parts = drawn(info, Map.of("mana", "50", "len", "40"));
        assertEquals(40, pixels(parts.get(0).getText()), "the background is the value's length");
        assertEquals(20, pixels(parts.get(1).getText()), "half of a 40-pixel bar");
        assertEquals(100, pixels(drawn(info, Map.of("mana", "50")).get(0).getText()), "unanswered keeps the pack's length");
        assertEquals(512, pixels(drawn(info, Map.of("mana", "0", "len", "9999")).get(0).getText()), "clamped");
    }

    @Test void aThresholdMovesWithItsValue() {
        List<OverlayInfo.OverlayRun.Bar.Shade> shades = List.of(
                new OverlayInfo.OverlayRun.Bar.Shade(0.25, "#f00018", "low"),
                new OverlayInfo.OverlayRun.Bar.Shade(1, "#f00010"));
        OverlayInfo info = overlay(barRun(true, "#f00010", "", shades));
        assertEquals("#f00010", color(drawn(info, Map.of("mana", "50")).get(0)), "50% is above 25%");
        assertEquals("#f00018", color(drawn(info, Map.of("mana", "50", "low", "60")).get(0)), "50% is under a threshold moved to 60%");
    }

    @Test void aBarsNamedStateWinsOverItsThresholds() {
        List<OverlayInfo.OverlayRun.Bar.Shade> shades = List.of(
                new OverlayInfo.OverlayRun.Bar.Shade(0.25, "#f00018"),
                new OverlayInfo.OverlayRun.Bar.Shade(1, "#f00010"));
        OverlayInfo info = overlay(barRun(true, "#f00010", "", shades)
                .live("", List.of(new OverlayInfo.OverlayRun.State("frozen", "#f00020")), "effect"));
        assertEquals("#f00018", color(drawn(info, Map.of("mana", "10")).get(0)));
        assertEquals("#f00020", color(drawn(info, Map.of("mana", "10", "effect", "frozen")).get(0)));
    }

    @Test void theLegacyBossBarHonoursItToo() {
        OverlayInfo info = overlay(label("VIP", 0, "#f00008").live("{rank} == \"vip\"", List.of(), ""));
        assertFalse(Overlays.legacyComposed(info, Map.of(), null).contains("VIP"));
        assertTrue(Overlays.legacyComposed(info, Map.of("rank", "vip"), null).contains("VIP"));
    }
}
