package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShaderPlacementTest {
    private final String plus = "abcdefghij", minus = "ABCDEFGHIJ";

    private OverlayInfo overlay() {
        return OverlayInfo.pushed(ContentId.parse("test:shader").orElseThrow(), "canvas", "",
                OverlayInfo.Slot.ACTION_BAR, "#fe0001", "minecraft:default", "", List.of(),
                List.of(new OverlayInfo.OverlayRun("", 10, "{value}", "", "#fd0002"),
                        new OverlayInfo.OverlayRun("", -900, "@t[]~", "", "#fd0003"),
                        new OverlayInfo.OverlayRun("", 80, "{unset}", "", "#fd0004")))
                .withCursor(257, plus, minus);
    }

    private int shiftWidth(String text) {
        return text.chars().map(c -> plus.indexOf(c) >= 0 ? 1 << plus.indexOf(c) : -(1 << minus.indexOf(c))).sum();
    }

    @Test void labelsAndValuesDoNotChangeTheCanvasOrigin() {
        for (String value : List.of("", "1", "123456789", "Wide text", "§l")) {
            BaseComponent[] parts = Overlays.compose(overlay(), Map.of("value", value));
            int cursor = 257;
            for (int i = 1; i < parts.length; i++) {
                TextComponent part = (TextComponent) parts[i];
                if (part.getColorRaw() == null) {
                    cursor += shiftWidth(part.getText());
                } else {
                    assertEquals(part.getText().equals("@t[]~") ? -900 : 10, cursor);
                    cursor += TextWidth.of(part.getText());
                }
            }
            assertEquals(257, cursor, "action-bar centering must be independent of text");
        }
    }

    @Test void shiftsBeyondOneAlphabetAreNotClamped() {
        for (int offset : List.of(-2048, -1024, 0, 1024, 4096)) {
            assertEquals(offset, shiftWidth(Overlays.shiftTo(overlay(), offset)));
        }
    }

    @Test void widthsMatchVanillasBitmap() {
        assertEquals(7, TextWidth.of("@"));
        assertEquals(7, TextWidth.of("~"));
        assertEquals(4, TextWidth.of("t"));
        assertEquals(16, TextWidth.of("(){}"));
        assertEquals(4, TextWidth.of(" "));
        assertEquals(3, TextWidth.of("l"));
    }
}
