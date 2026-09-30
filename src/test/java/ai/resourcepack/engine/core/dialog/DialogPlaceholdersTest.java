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
}
