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
}
