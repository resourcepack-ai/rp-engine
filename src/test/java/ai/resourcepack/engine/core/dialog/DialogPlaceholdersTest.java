package ai.resourcepack.engine.core.dialog;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dialog's placeholders, filled.
 *
 * <p>The properties the open path leans on: only names inside strings are ever
 * matched (a dialog's own braces never are), a value lands escaped as the inside
 * of a JSON string, a name nothing answers is left exactly as written, and a
 * value is never read a second time — so a player called "{player}" stays one.
 */
class DialogPlaceholdersTest {

    private static Function<String, Optional<String>> from(Map<String, String> values) {
        return name -> Optional.ofNullable(values.get(name));
    }

    @Test
    void fillsATitleACommandAndATooltip() {
        String json = "{\"type\":\"minecraft:notice\",\"title\":\"Punish {target}\","
                + "\"action\":{\"label\":\"Mute\",\"action\":{\"type\":\"run_command\",\"command\":\"mute {target} 1h\"}},"
                + "\"body\":[{\"type\":\"plain_message\",\"contents\":{\"hover_event\":{\"value\":\"Mute {target}?\"}}}]}";
        String out = DialogPlaceholders.fill(json, from(Map.of("target", "Steve")));
        assertTrue(out.contains("\"Punish Steve\""));
        assertTrue(out.contains("\"mute Steve 1h\""));
        assertTrue(out.contains("\"Mute Steve?\""));
        assertFalse(out.contains("{target}"));
    }

    @Test
    void neverTouchesTheJsonsOwnBraces() {
        String json = "{\"a\":{},\"b\":[{\"c\":1}],\"d\":\"{}\"}";
        assertEquals(json, DialogPlaceholders.fill(json, name -> Optional.of("X")));
        assertFalse(DialogPlaceholders.any(json));
    }

    @Test
    void leavesWhatNothingAnswersAsWritten() {
        String json = "{\"title\":\"{VIP} lounge for {player}\"}";
        String out = DialogPlaceholders.fill(json, from(Map.of("player", "Alex")));
        assertEquals("{\"title\":\"{VIP} lounge for Alex\"}", out);
    }

    @Test
    void escapesAValueAsTheInsideOfAString() {
        String out = DialogPlaceholders.fill("{\"title\":\"Hi {name}\"}", from(Map.of("name", "a\"b\\c\nd")));
        assertEquals("{\"title\":\"Hi a\\\"b\\\\c\\nd\"}", out);
    }

    @Test
    void dropsFormattingCodesACommandWouldBeRefusedOver() {
        String out = DialogPlaceholders.fill("{\"command\":\"msg {name} hi\"}", from(Map.of("name", "§cSteve§r")));
        assertEquals("{\"command\":\"msg Steve hi\"}", out);
    }

    @Test
    void readsEachValueOnce() {
        String out = DialogPlaceholders.fill("{\"title\":\"{a} and {b}\"}", from(Map.of("a", "{b}", "b", "B")));
        assertEquals("{\"title\":\"{b} and B\"}", out);
    }

    // A choice keeps the entry the value names — the last when none does —
    // which is how a Studio switch is drawn on for one player and off for the
    // next, and how its click toggles the state the player is looking at.

    @Test
    void aChoiceKeepsTheEntryItsValueNames() {
        String json = "{\"text\":\"{show_sidebar?off:|on:}\"}";
        assertTrue(DialogPlaceholders.any(json));
        assertEquals("{\"text\":\"\"}", DialogPlaceholders.fill(json, from(Map.of("show_sidebar", "off"))));
        assertEquals("{\"text\":\"\"}", DialogPlaceholders.fill(json, from(Map.of("show_sidebar", "ON"))));
    }

    @Test
    void aChoiceWithNoValueOrAnUnknownOneKeepsItsLastEntry() {
        String json = "{\"command\":\"rp var mode {mode?off:on|on:off}\"}";
        assertEquals("{\"command\":\"rp var mode off\"}", DialogPlaceholders.fill(json, name -> Optional.empty()));
        assertEquals("{\"command\":\"rp var mode off\"}", DialogPlaceholders.fill(json, from(Map.of("mode", "banana"))));
        assertEquals("{\"command\":\"rp var mode on\"}", DialogPlaceholders.fill(json, from(Map.of("mode", "off"))));
    }

    @Test
    void aChoiceInsertsItsEntryAsWritten() {
        // The entry came out of the JSON string it goes back into: escaping it
        // again would double every backslash in it.
        String json = "{\"text\":\"{v?a:x\\\"y|b:z}\"}";
        assertEquals("{\"text\":\"x\\\"y\"}", DialogPlaceholders.fill(json, from(Map.of("v", "a"))));
    }

    @Test
    void choicesAndPlainPlaceholdersFillInOnePass() {
        String json = "{\"title\":\"{target}: {mode?a:Alpha|b:Beta}\"}";
        assertEquals("{\"title\":\"Steve: Alpha\"}", DialogPlaceholders.fill(json, from(Map.of("target", "Steve", "mode", "a"))));
    }

    @Test
    void aChoiceWithNoWellFormedEntryIsLeftAsWritten() {
        String json = "{\"title\":\"{mode?nothing here}\"}";
        assertEquals(json, DialogPlaceholders.fill(json, from(Map.of("mode", "a"))));
    }

    @Test
    void variablesEncodeAndDecodeAndRefuseWhatTheyDoNotKeep() {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("show_sidebar", "off");
        values.put("opacity", "70");
        String encoded = DialogVariables.encode(values);
        assertEquals("show_sidebar=off;opacity=70", encoded);
        assertEquals(values, DialogVariables.decode(encoded));
        // Anything that could not have been written is skipped, not trusted.
        assertEquals(Map.of("ok", "1"), DialogVariables.decode("ok=1;Bad Name=2;x=a b;=3;noeq;y="));
        assertFalse(DialogVariables.NAME.matcher("9lives").matches());
        assertTrue(DialogVariables.VALUE.matcher("0.5").matches());
    }

    // Live values: a placeholder Studio drew into a dialog's picture, behind a
    // mark that says how big its words are and how much room they have. The
    // value has to stay measurable and inside that room, or the body line it is
    // on stops coming to its width and the picture under it moves.

    /** A mark for words of this size and weight, with this much room. */
    private static String mark(int scale, boolean bold, int room) {
        int mode = (scale == 2 ? 2 : 0) + (bold ? 1 : 0);
        return new String(Character.toChars(DialogPlaceholders.LIVE_MARK_BASE + mode * 512 + room));
    }

    /** How far the game's font moves the pen for a string, by the generated table. */
    private static int advance(String text, int scale, boolean bold) {
        return text.codePoints().map(cp -> DialogGlyphWidths.advance(cp, scale, bold)).sum();
    }

    @Test
    void theWidthTableIsTheGamesFont() {
        // 'A' is five pixels and the gap; 'i' and '!' one; a space four.
        assertEquals(6, DialogGlyphWidths.advance('A', 1, false));
        assertEquals(2, DialogGlyphWidths.advance('i', 1, false));
        assertEquals(4, DialogGlyphWidths.advance(' ', 1, false));
        // Twice the size doubles the ink and not the gap; bold adds one.
        assertEquals(11, DialogGlyphWidths.advance('A', 2, false));
        assertEquals(7, DialogGlyphWidths.advance(' ', 2, false));
        assertEquals(7, DialogGlyphWidths.advance('A', 1, true));
        // Accented letters and the small capitals server prefixes are written in.
        assertTrue(DialogGlyphWidths.covers('é') && DialogGlyphWidths.covers('ᴠ'));
        // What only unifont draws is not measured.
        assertFalse(DialogGlyphWidths.covers('日'));
        assertEquals(-1, DialogGlyphWidths.advance('日', 1, false));
    }

    @Test
    void aMarkedValueKeepsToWhatThePackCanMeasure() {
        String json = "{\"text\":\"Hi " + mark(1, false, 300) + "{name}\"}";
        String out = DialogPlaceholders.fill(json, from(Map.of("name", "Ana日本\nB")));
        assertEquals("{\"text\":\"Hi " + mark(1, false, 300) + "Ana???B\"}", out);
    }

    @Test
    void aMarkedValuesSpacesBecomeNoBreakSpaces() {
        // A real space on a picture's body line is where the client would break
        // the line, so a live value never carries one.
        String m = mark(1, false, 300);
        String out = DialogPlaceholders.fill("{\"text\":\"" + m + "{rank}\"}", from(Map.of("rank", "VIP Plus")));
        assertEquals("{\"text\":\"" + m + "VIP Plus\"}", out);
        assertFalse(out.contains(" "));
    }

    @Test
    void anUnmarkedValueIsLeftAlone() {
        String out = DialogPlaceholders.fill("{\"title\":\"Hi {name}\"}", from(Map.of("name", "Ana日本")));
        assertEquals("{\"title\":\"Hi Ana日本\"}", out);
    }

    @Test
    void aMarkedValueThatFitsIsUnchanged() {
        String json = "{\"text\":\"" + mark(1, false, 60) + "{coins}\"}";
        assertEquals("{\"text\":\"" + mark(1, false, 60) + "1,250\"}", DialogPlaceholders.fill(json, from(Map.of("coins", "1,250"))));
    }

    @Test
    void aMarkedValueTooLongForItsRoomIsCutWithAnEllipsis() {
        for (int scale : new int[] {1, 2}) {
            for (boolean bold : new boolean[] {false, true}) {
                int room = 40;
                String m = mark(scale, bold, room);
                String out = DialogPlaceholders.fill("{\"text\":\"" + m + "{name}\"}", from(Map.of("name", "Supercalifragilistic")));
                String value = out.substring(("{\"text\":\"" + m).length(), out.length() - 2);
                assertTrue(value.endsWith("…"), "cut short at scale " + scale + (bold ? " bold" : "") + ": " + value);
                assertTrue(advance(value, scale, bold) <= room, value + " is wider than its room");
                // And no shorter than it had to be: one more letter would not fit.
                String longer = value.substring(0, value.length() - 1) + "Supercalifragilistic".charAt(value.length() - 1) + "…";
                assertTrue(advance(longer, scale, bold) > room);
            }
        }
    }

    @Test
    void aMarkWithNoRoomLeavesNothing() {
        String m = mark(1, false, 0);
        assertEquals("{\"text\":\"" + m + "\"}", DialogPlaceholders.fill("{\"text\":\"" + m + "{name}\"}", from(Map.of("name", "Steve"))));
    }

    @Test
    void aMarkedNameNothingAnswersIsStillLeftAsWritten() {
        // Both copies of a live run then say exactly the same thing, so the
        // negative font still steps back over exactly what was drawn.
        String json = "{\"text\":\"" + mark(1, false, 100) + "{nobody}\"}";
        assertEquals(json, DialogPlaceholders.fill(json, name -> Optional.empty()));
    }

    @Test
    void aMarkedValueKeepsColourAndBoldInBoldWords() {
        String m = mark(1, true, 200);
        String out = DialogPlaceholders.fill("{\"text\":\"" + m + "{rank}\"}", from(Map.of("rank", "§6§lVIP")));
        assertEquals("{\"text\":\"" + m + "§6§lVIP\"}", out);
    }

    @Test
    void aMarkedValueKeepsOnlyColourInPlainWords() {
        // Bold, or any other code, would make the words and their twin differ in width.
        String m = mark(1, false, 200);
        String out = DialogPlaceholders.fill("{\"text\":\"" + m + "{rank}\"}", from(Map.of("rank", "§6§l§kVIP§")));
        assertEquals("{\"text\":\"" + m + "§6VIP\"}", out);
    }

    @Test
    void anUnmarkedValueStillLosesItsCodes() {
        String out = DialogPlaceholders.fill("{\"text\":\"{rank}\"}", from(Map.of("rank", "§6§lVIP")));
        assertEquals("{\"text\":\"VIP\"}", out);
    }

    // ------------------------------------------------------------ live heads

    /** A live head's marker exactly as Studio's dialog builder writes one: see its `liveHeadMarker`. */
    private static String marker(String color, String name) {
        return "{\"text\":\"\",\"font\":\"minecraft:dialog_space\",\"color\":\"" + color + "\",\"insertion\":\"rp:head:" + name + "\"}";
    }

    /** A body with a marker in it, the way the builder sets one: after the line's own runs, then a step back. */
    private static String body(String marker) {
        return "{\"type\":\"minecraft:notice\",\"title\":\"Profile\",\"body\":[{\"type\":\"plain_message\",\"contents\":{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\",\"font\":\"minecraft:dialog_space\"}," + marker
                + ",{\"text\":\"\",\"font\":\"minecraft:dialog_space\"}]}}]}";
    }

    @Test
    void aFilledHeadBecomesThePlayersFace() {
        String out = DialogPlaceholders.fill(body(marker("#fcdff8", "{target}")), from(Map.of("target", "Notch")), true);
        assertTrue(out.contains("{\"type\":\"object\",\"object\":\"player\",\"player\":\"Notch\",\"hat\":true,\"color\":\"#fcdff8\",\"shadow_color\":0}"), out);
        assertFalse(out.contains("rp:head:"), out);
        // Still a dialog: the swap is whole objects, never a broken one.
        com.google.gson.JsonParser.parseString(out);
    }

    @Test
    void aServerWithoutTheComponentKeepsTheMarker() {
        String out = DialogPlaceholders.fill(body(marker("#fcdff8", "{target}")), from(Map.of("target", "Notch")), false);
        assertTrue(out.contains(marker("#fcdff8", "Notch")), out);
        assertFalse(out.contains("\"object\""), out);
    }

    @Test
    void aHeadNothingNamesStaysEmpty() {
        String json = body(marker("#fce7f9", "{target}"));
        assertEquals(json, DialogPlaceholders.fill(json, name -> Optional.empty(), true));
    }

    @Test
    void aValueTheGameWouldRefuseAsANameStaysEmpty() {
        // The game refuses a WHOLE dialog over a name it will not take, so
        // anything that is not one leaves the slot empty instead.
        for (String value : new String[] {"Some Body", "ThisNameIsFarTooLongForMinecraft", "Zoë", "Bob\"s", "", "  "}) {
            String out = DialogPlaceholders.fill(body(marker("#fcdff8", "{target}")), from(Map.of("target", value)), true);
            assertFalse(out.contains("\"object\":\"player\""), value + " became a head: " + out);
            com.google.gson.JsonParser.parseString(out);
        }
    }

    @Test
    void everyHeadOnABodyIsSwappedWithItsOwnColour() {
        String json = body(marker("#fcdff8", "{player}") + "," + marker("#fcbffb", "{target}"));
        String out = DialogPlaceholders.fill(json, from(Map.of("player", "Alex_1", "target", "jeb_")), true);
        assertTrue(out.contains("\"player\":\"Alex_1\",\"hat\":true,\"color\":\"#fcdff8\""), out);
        assertTrue(out.contains("\"player\":\"jeb_\",\"hat\":true,\"color\":\"#fcbffb\""), out);
    }

    @Test
    void somethingThatOnlyLooksLikeAMarkerIsLeftAlone() {
        // A different colour or font is somebody's own text, whatever it says.
        String other = "{\"text\":\"\",\"font\":\"minecraft:default\",\"color\":\"#fcdff8\",\"insertion\":\"rp:head:Notch\"}";
        assertEquals(other, DialogPlaceholders.withHeads(other));
        String red = "{\"text\":\"\",\"font\":\"minecraft:dialog_space\",\"color\":\"#ff0000\",\"insertion\":\"rp:head:Notch\"}";
        assertEquals(red, DialogPlaceholders.withHeads(red));
    }

    @Test
    void aNameIsReadBackOutOfItsJsonEscapes() {
        assertEquals("Steve", DialogPlaceholders.playerName(DialogPlaceholders.unescape("\\u0053teve")));
        assertEquals(null, DialogPlaceholders.playerName(DialogPlaceholders.unescape("Bob\\\"s")));
        assertEquals(null, DialogPlaceholders.playerName("{target}"));
    }
}
