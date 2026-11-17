package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A player's face in an overlay's value (Heads): measured as eight, sent as the game's face component. */
class OverlayHeadsTest {

    private static OverlayInfo overlay(String text) {
        return OverlayInfo.pushed(ContentId.parse("test:hud").orElseThrow(), "canvas", "",
                        OverlayInfo.Slot.BOSS_BAR, "#fe0001", "", "", List.of(),
                        List.of(new OverlayInfo.OverlayRun("", 0, text, "", "#f00008").withShadow(false),
                                new OverlayInfo.OverlayRun("", 60, "after", "", "#f00010")))
                .withCursor(257, "abcdefghij", "ABCDEFGHIJ");
    }

    @Test
    void aFaceMovesTheCursorEightAndNoMore() {
        String face = Heads.token("\"Kiwi\"", "#F00108");
        assertEquals(8, TextWidth.of(face));
        assertEquals(TextWidth.of("ab") + 8, TextWidth.of("a" + face + "b"));
    }

    @Test
    void theJsonLineDrawsTheFaceBetweenItsWords() {
        String face = Heads.token("{\"texture\":\"entity/player/wide/steve\"}", "#f00108");
        JsonArray extra = JsonParser.parseString(Overlays.composeJson(overlay("{v}"), Map.of("v", "Hi " + face + " there"), null))
                .getAsJsonObject().getAsJsonArray("extra");
        JsonObject run = null;
        for (var part : extra) {
            JsonObject o = part.getAsJsonObject();
            if ("#f00008".equals(o.has("color") ? o.get("color").getAsString() : null)) run = o;
        }
        // The run keeps its own colour and shadow; its words and the face are its children, in order.
        assertEquals("", run.get("text").getAsString());
        assertEquals(0, run.get("shadow_color").getAsInt());
        JsonArray parts = run.getAsJsonArray("extra");
        assertEquals("Hi ", parts.get(0).getAsJsonObject().get("text").getAsString());
        JsonObject head = parts.get(1).getAsJsonObject();
        assertEquals("player", head.get("object").getAsString());
        assertEquals("entity/player/wide/steve", head.getAsJsonObject("player").get("texture").getAsString());
        assertEquals("#f00108", head.get("color").getAsString());
        assertEquals(" there", parts.get(2).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void aLineThatCannotCarryAFaceGetsASpaceAsWide() {
        String face = Heads.token("\"Kiwi\"", "#ffffff");
        String flat = Heads.flat("a" + face + "b");
        assertFalse(Heads.has(flat));
        assertEquals(TextWidth.of("a" + face + "b"), TextWidth.of(flat));
    }

    @Test
    void aFaceTypedByAPlayerIsOnlyText() {
        // The token's own characters, as somebody could type them into a name or a message, with
        // JSON that is not even a profile: no face, no exception, the characters left as they are.
        String typed = "￹{\"broken￺#ffffff￻";
        assertEquals("a" + typed + "b", Heads.flat("a" + typed + "b"));
        assertFalse(Overlays.composeJson(overlay("{v}"), Map.of("v", "x" + typed), null).contains("\"object\""));
        // A well-formed one without the server's key is text too.
        String real = Heads.token("\"Kiwi\"", "#ffffff");
        String forged = "￹\"Kiwi\"" + real.substring(real.indexOf('￺'));
        assertEquals(forged, Heads.flat(forged));
        assertFalse(Overlays.composeJson(overlay("{v}"), Map.of("v", forged), null).contains("\"object\""));
    }

    @Test
    void aProfileOrColourThatIsNotOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Heads.token("Kiwi", "#ffffff"));
        assertThrows(IllegalArgumentException.class, () -> Heads.token("\"Kiwi\"", "red"));
    }
}
