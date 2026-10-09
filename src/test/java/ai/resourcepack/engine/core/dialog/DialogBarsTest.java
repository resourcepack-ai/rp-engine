package ai.resourcepack.engine.core.dialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Studio dialog's live progress bars.
 *
 * <p>The properties the open path leans on: a marker becomes a fill exactly as
 * many pixels long as the value over the most says, in whole numbers and
 * decimals, with commas and a percent sign; each of its two runs comes to no
 * advance at all and never moves the pen further than the room and a pixel; a
 * value that is not a number fills nothing; and a dialog with no bar comes back
 * as the very string it was.
 */
class DialogBarsTest {

    private static String marker(String insertion) {
        return "{\"text\":\"\",\"font\":\"minecraft:dialog_bar_2\",\"color\":\"#55ff55\",\"insertion\":\"" + insertion + "\"}";
    }

    private static String dialog(String... runs) {
        return "{\"type\":\"minecraft:notice\",\"title\":\"XP\",\"body\":[{\"type\":\"minecraft:plain_message\","
                + "\"contents\":{\"text\":\"\",\"extra\":[{\"text\":\"\\ue004\",\"font\":\"minecraft:dialog_space\"},"
                + String.join(",", runs) + "]},\"width\":400}]}";
    }

    /** The runs a dialog's body carries after the pass. */
    private static JsonArray extra(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("body").get(0).getAsJsonObject()
                .getAsJsonObject("contents").getAsJsonArray("extra");
    }

    /** What a bar font's characters do to the pen: a rectangle 2^i wide advances 2^i + 1, a step back 2^i. */
    private static int step(char c) {
        int i;
        if (c >= DialogBars.BACK_BASE && c < DialogBars.BACK_BASE + DialogBars.STEPS) {
            return -(1 << (c - DialogBars.BACK_BASE));
        }
        if (c >= DialogBars.BODY_BASE && c < DialogBars.BODY_BASE + DialogBars.STEPS) {
            i = c - DialogBars.BODY_BASE;
        } else if (c >= DialogBars.RIM_BASE && c < DialogBars.RIM_BASE + DialogBars.STEPS) {
            i = c - DialogBars.RIM_BASE;
        } else {
            throw new AssertionError("an unexpected character " + (int) c);
        }
        return (1 << i) + 1;
    }

    /** A run's pixels drawn (the inked columns), its net advance, and the furthest the pen went. */
    private record Measured(int drawn, int net, int reach) {
    }

    private static Measured measure(String text) {
        int pen = 0;
        int reach = 0;
        int drawn = 0;
        for (char c : text.toCharArray()) {
            int s = step(c);
            if (s > 0) {
                drawn += s - 1;
            }
            pen += s;
            reach = Math.max(reach, pen);
        }
        return new Measured(drawn, pen, reach);
    }

    /** The two runs a marker became, checked for moving nothing; the fill's pixels. */
    private static int filled(String insertion) {
        JsonArray runs = extra(DialogBars.fill(dialog(marker(insertion))));
        if (runs.size() == 2) {
            assertEquals("", runs.get(1).getAsJsonObject().get("text").getAsString(), "nothing to fill leaves an empty text");
            return 0;
        }
        assertEquals(3, runs.size(), "the space, the fill and its highlight");
        JsonObject body = runs.get(1).getAsJsonObject();
        JsonObject rim = runs.get(2).getAsJsonObject();
        Measured b = measure(body.get("text").getAsString());
        Measured r = measure(rim.get("text").getAsString());
        assertEquals(0, b.net(), "the fill steps back over itself");
        assertEquals(0, r.net(), "the highlight steps back over itself");
        assertEquals(b.drawn(), r.drawn(), "the highlight is as long as the fill");
        assertEquals(b.drawn() + 1, b.reach(), "the pen goes no further than the fill and the pixel after it");
        assertEquals("minecraft:dialog_bar_2", body.get("font").getAsString());
        assertEquals(0, body.get("shadow_color").getAsInt(), "a fill has no shadow");
        assertEquals(0, rim.get("shadow_color").getAsInt(), "nor does its highlight");
        return b.drawn();
    }

    @Test
    void aDialogWithNoBarIsLeftAsItWas() {
        String json = dialog("{\"text\":\"Hello\"}");
        assertSame(json, DialogBars.fill(json));
    }

    @Test
    void theFillIsTheValueOverTheMostOfTheRoom() {
        assertEquals(106, filled("rp:bar:120:#aaffaa:37|42"), "37/42 of 120, rounded");
        assertEquals(60, filled("rp:bar:120:#aaffaa:1|2"));
        assertEquals(120, filled("rp:bar:120:#aaffaa:42|42"));
        assertEquals(386, filled("rp:bar:386:#aaffaa:9|9"), "the widest a bar can be");
    }

    @Test
    void wholeNumbersDecimalsCommasAndPercentsAllRead() {
        assertEquals(25, filled("rp:bar:100:#aaffaa:12.5|50"));
        assertEquals(25, filled("rp:bar:100:#aaffaa:1,250|5,000"));
        assertEquals(75, filled("rp:bar:100:#aaffaa:75%|100"));
        assertEquals(50, filled("rp:bar:100:#aaffaa: 0.5 |1.0"));
        assertEquals(33, filled("rp:bar:100:#aaffaa:.33|1"));
    }

    @Test
    void theFillIsKeptBetweenEmptyAndFull() {
        assertEquals(100, filled("rp:bar:100:#aaffaa:500|20"));
        assertEquals(0, filled("rp:bar:100:#aaffaa:-5|20"));
    }

    @Test
    void whatIsNotANumberFillsNothing() {
        assertEquals(0, filled("rp:bar:100:#aaffaa:{xp_points}|{xp_needed}"), "nothing answered the placeholders");
        assertEquals(0, filled("rp:bar:100:#aaffaa:5|0"), "a most of nothing");
        assertEquals(0, filled("rp:bar:100:#aaffaa:NaN|10"));
        assertEquals(0, filled("rp:bar:100:#aaffaa:Infinity|10"));
        assertEquals(0, filled("rp:bar:100:#aaffaa:0x10|20"));
        assertEquals(0, filled("rp:bar:100:#aaffaa:1.2k|5k"));
        assertEquals(0, filled("rp:bar:garbage"));
    }

    @Test
    void theNumbersArriveThroughTheOrdinaryPass() {
        String json = dialog(marker("rp:bar:200:#ffd0a0:{xp_points}|{xp_needed}"));
        String filled = DialogPlaceholders.fill(json, name -> Optional.ofNullable(Map.of("xp_points", "30", "xp_needed", "40").get(name)), false);
        JsonArray runs = extra(DialogBars.fill(filled));
        assertEquals(3, runs.size());
        assertEquals(150, measure(runs.get(1).getAsJsonObject().get("text").getAsString()).drawn());
        assertEquals("#55ff55", runs.get(1).getAsJsonObject().get("color").getAsString(), "the fill is the marker's colour");
        assertEquals("#ffd0a0", runs.get(2).getAsJsonObject().get("color").getAsString(), "the highlight is the insertion's");
    }

    @Test
    void aColourTheCodecWouldRefuseIsNeverWritten() {
        JsonArray runs = extra(DialogBars.fill(dialog(marker("rp:bar:100:not-a-colour:1|2"))));
        assertEquals("#55ff55", runs.get(2).getAsJsonObject().get("color").getAsString(), "the highlight falls back to the fill's colour");
        JsonArray bare = extra(DialogBars.fill(dialog("{\"text\":\"\",\"font\":\"minecraft:dialog_bar_0\",\"insertion\":\"rp:bar:100:#ffffff:1|2\"}")));
        assertEquals("white", bare.get(1).getAsJsonObject().get("color").getAsString(), "a marker with no colour fills white");
    }

    @Test
    void aRoomPastTheRectanglesIsKeptToThem() {
        DialogBars.Marker marker = DialogBars.marker(JsonParser.parseString(marker("rp:bar:9999:#ffffff:1|1")));
        assertEquals((1 << DialogBars.STEPS) - 1, marker.room());
        assertNull(DialogBars.marker(JsonParser.parseString("{\"text\":\"\",\"insertion\":\"rp:slot:inv/1\"}")));
    }

    @Test
    void everyLengthIsWrittenExactly() {
        for (int pixels = 0; pixels < 1 << DialogBars.STEPS; pixels++) {
            Measured m = measure(DialogBars.run(pixels, DialogBars.BODY_BASE));
            assertEquals(pixels, m.drawn(), "drawn " + pixels);
            assertEquals(0, m.net(), "net " + pixels);
            assertTrue(m.reach() <= pixels + 1, "reach " + pixels);
        }
    }

    // ------------------------------------------------------- vertical bars

    private static final String VFONT = "minecraft:dialog_vbar_3_10x60_0badf00d";

    private static String vmarker(String insertion) {
        return "{\"text\":\"\",\"font\":\"" + VFONT + "\",\"color\":\"white\",\"insertion\":\"" + insertion + "\"}";
    }

    /** How many rows a vertical marker fills: its one glyph's k, or 0 for the empty text it leaves. */
    private static int rose(String insertion) {
        JsonArray runs = extra(DialogBars.fill(dialog(vmarker(insertion))));
        assertEquals(2, runs.size(), "the space and one run");
        JsonObject run = runs.get(1).getAsJsonObject();
        String text = run.get("text").getAsString();
        if (text.isEmpty()) {
            return 0;
        }
        assertEquals(2, text.length(), "one glyph and the step back");
        assertEquals(DialogBars.VBAR_BACK, text.charAt(1));
        assertEquals(VFONT, run.get("font").getAsString(), "in the bar's own font");
        assertEquals("white", run.get("color").getAsString(), "its glyphs carry their colours");
        assertEquals(0, run.get("shadow_color").getAsInt());
        return text.charAt(0) - DialogBars.VBAR_BASE;
    }

    @Test
    void aVerticalBarRisesTheValueOverTheMostOfItsRoom() {
        assertEquals(30, rose("rp:vbar:60:1|2"));
        assertEquals(60, rose("rp:vbar:60:9|9"));
        assertEquals(52, rose("rp:vbar:60:1,652|1,900"), "1652/1900 of 60, rounded");
        assertEquals(60, rose("rp:vbar:60:500|20"), "kept to full");
        assertEquals(0, rose("rp:vbar:60:0|20"));
        assertEquals(0, rose("rp:vbar:60:{fuel}|{fuel_max}"), "nothing answered");
        assertEquals(0, rose("rp:vbar:garbage"));
        assertEquals(DialogBars.VBAR_MAX_ROOM, rose("rp:vbar:9999:1|1"), "kept to the glyphs a font has");
    }

    @Test
    void aVerticalBarIsNotReadAsOneAcross() {
        assertTrue(DialogBars.any(dialog(vmarker("rp:vbar:60:1|2"))));
        assertTrue(DialogBars.marker(JsonParser.parseString(vmarker("rp:vbar:60:1|2"))).vertical());
        assertTrue(!DialogBars.marker(JsonParser.parseString(marker("rp:bar:60:#ffffff:1|2"))).vertical());
    }

    @Test
    void verticalNumbersArriveThroughTheOrdinaryPass() {
        String json = dialog(vmarker("rp:vbar:64:{heat}|2000"));
        String filled = DialogPlaceholders.fill(json, name -> Optional.ofNullable(Map.of("heat", "1500").get(name)), false);
        JsonArray runs = extra(DialogBars.fill(filled));
        assertEquals(48, runs.get(1).getAsJsonObject().get("text").getAsString().charAt(0) - DialogBars.VBAR_BASE);
    }
}
