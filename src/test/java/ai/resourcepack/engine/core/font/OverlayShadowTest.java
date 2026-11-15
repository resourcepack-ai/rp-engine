package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import ai.resourcepack.engine.core.sync.StudioContent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A HUD run with {@code "shadow": false}: read from a {@code json:} HUD, and
 * sent with {@code shadow_color: 0} on that run's part and on no other.
 */
class OverlayShadowTest {

    private static final String PLUS = "abcdefghij";
    private static final String MINUS = "ABCDEFGHIJ";

    private static OverlayInfo overlay(OverlayInfo.OverlayRun... runs) {
        return OverlayInfo.pushed(ContentId.parse("test:hud").orElseThrow(), "canvas", "",
                        OverlayInfo.Slot.BOSS_BAR, "#fe0001", "", "", List.of(), List.of(runs))
                .withCursor(257, PLUS, MINUS);
    }

    private static OverlayInfo.OverlayRun run(int x, String text, String color) {
        return new OverlayInfo.OverlayRun("", x, text, "", color);
    }

    @Test
    void aRunHasItsShadowUnlessToldOtherwise() {
        OverlayInfo.OverlayRun plain = run(0, "a", "#f00008");
        assertTrue(plain.shadow());
        assertFalse(plain.withShadow(false).shadow());
        // Making it live keeps the setting, whichever order they come in.
        assertFalse(plain.withShadow(false).live("{x} == 1", List.of(), "").shadow());
    }

    @Test
    void onlyTheShadowlessRunCarriesShadowColour() {
        OverlayInfo info = overlay(
                run(0, "Shadowed", "#f00008"),
                run(40, "Flat", "#f00010").withShadow(false));
        JsonObject line = JsonParser.parseString(Overlays.composeJson(info, Map.of(), null)).getAsJsonObject();
        assertEquals("", line.get("text").getAsString());
        JsonArray extra = line.getAsJsonArray("extra");
        int flagged = 0;
        for (JsonElement part : extra) {
            JsonObject object = part.getAsJsonObject();
            String text = object.has("text") ? object.get("text").getAsString() : "";
            if (object.has("shadow_color")) {
                flagged++;
                assertEquals("Flat", text);
                assertEquals(0, object.get("shadow_color").getAsInt());
                // Everything else about the part is what compose() sends.
                assertEquals("#f00010", object.get("color").getAsString());
            }
        }
        assertEquals(1, flagged);
    }

    @Test
    void theJsonIsTheComposedLinePartForPart() {
        OverlayInfo info = overlay(run(0, "One", "#f00008").withShadow(false), run(30, "Two", "#f00010"));
        JsonArray extra = JsonParser.parseString(Overlays.composeJson(info, Map.of(), null))
                .getAsJsonObject().getAsJsonArray("extra");
        assertEquals(Overlays.compose(info, Map.of(), null).length, extra.size());
    }

    @Test
    void aJsonHudReadsShadowFalseAndNothingElse() {
        String json = "{\"title\":\"\\ue000\",\"slot\":\"boss_bar\",\"color\":\"#fe0001\",\"font\":\"x:hud\","
                + "\"advance\":10,\"shiftPlus\":\"" + PLUS + "\",\"shiftMinus\":\"" + MINUS + "\",\"runs\":["
                + "{\"shift\":\"\",\"x\":0,\"font\":\"\",\"color\":\"#f00008\",\"text\":\"a\"},"
                + "{\"shift\":\"\",\"x\":0,\"font\":\"\",\"color\":\"#f00010\",\"text\":\"b\",\"shadow\":false},"
                + "{\"shift\":\"\",\"x\":0,\"font\":\"\",\"color\":\"#f00018\",\"text\":\"c\",\"shadow\":true}]}";
        OverlayInfo hud = StudioContent.authoredHud(ContentId.parse("x:hud").orElseThrow(), json).orElseThrow();
        List<OverlayInfo.OverlayRun> runs = hud.runs();
        assertEquals(3, runs.size());
        assertTrue(runs.get(0).shadow());
        assertFalse(runs.get(1).shadow());
        assertTrue(runs.get(2).shadow());
    }
}
