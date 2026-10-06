package ai.resourcepack.engine.core.dialog;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Studio list's rows: their values, the rows past the end, and the clicks of
 * the empty ones. Studio's verify:dialogs runs the same rules in JavaScript.
 */
class DialogRowsTest {

    private static Function<String, Optional<String>> values(Map<String, String> map) {
        return name -> Optional.ofNullable(map.get(name));
    }

    @Test
    void aListGivenWholeToTheOpenerIsItsRows() {
        Map<String, String> opened = Map.of("warps", "Spawn, Shop,Arena");
        assertEquals(Optional.of("Spawn"), DialogRows.item("warps_1", opened, values(Map.of())));
        assertEquals(Optional.of("Shop"), DialogRows.item("warps_2", opened, values(Map.of())));
        assertEquals(Optional.of("Arena"), DialogRows.item("warps_3", opened, values(Map.of())));
        // Past the end, and a field a comma list cannot carry: nothing, not unknown.
        assertEquals(Optional.of(""), DialogRows.item("warps_4", opened, values(Map.of())));
        assertEquals(Optional.of(""), DialogRows.item("warps_2_note", opened, values(Map.of())));
    }

    @Test
    void aRowPastAPublishedListIsEmpty() {
        Function<String, Optional<String>> published = values(Map.of("top_kills_count", "2"));
        assertEquals(Optional.of(""), DialogRows.item("top_kills_3", Map.of(), published));
        assertEquals(Optional.of(""), DialogRows.item("top_kills_3_name", Map.of(), published));
        // Within it, the item itself is somebody else's to answer.
        assertEquals(Optional.empty(), DialogRows.item("top_kills_2", Map.of(), published));
    }

    @Test
    void aNameThatIsNoListItemIsLeftAlone() {
        assertEquals(Optional.empty(), DialogRows.item("player", Map.of(), values(Map.of())));
        assertEquals(Optional.empty(), DialogRows.item("level_5", Map.of(), values(Map.of())));
        assertEquals(Optional.empty(), DialogRows.item("warps_0", Map.of("warps", "a"), values(Map.of())));
    }

    @Test
    void anEmptyRowLosesItsClickAndAFullOneKeepsIt() {
        String json = "{\"body\":{\"text\":\"\",\"extra\":["
                + "{\"text\":\" \",\"insertion\":\"rp:row:warps_1\",\"click_event\":{\"action\":\"run_command\",\"command\":\"warp Spawn\"},"
                + "\"hover_event\":{\"action\":\"show_text\",\"value\":\"Spawn\"}},"
                + "{\"text\":\" \",\"insertion\":\"rp:row:warps_2\",\"click_event\":{\"action\":\"run_command\",\"command\":\"warp \"},"
                + "\"hover_event\":{\"action\":\"show_text\",\"value\":\"\"}}]}}";
        String out = DialogRows.strip(json, values(Map.of("warps_1", "Spawn", "warps_2", "")));
        assertTrue(out.contains("warp Spawn"), out);
        assertFalse(out.contains("\"warp \""), out);
        assertFalse(out.contains("rp:row:"), "the row's address goes either way: " + out);
        assertEquals(1, out.split("click_event", -1).length - 1, out);
    }

    @Test
    void aChoiceStarMatchesAnyValueButNothing() {
        assertEquals(Optional.of("row"), DialogPlaceholders.choose("*:row|-:", Optional.of("Spawn")));
        assertEquals(Optional.of(""), DialogPlaceholders.choose("*:row|-:", Optional.of("")));
        assertEquals(Optional.of(""), DialogPlaceholders.choose("*:row|-:", Optional.empty()));
        // An exact key still wins where one is given first.
        assertEquals(Optional.of("on"), DialogPlaceholders.choose("on:on|*:any|-:", Optional.of("on")));
    }
}
