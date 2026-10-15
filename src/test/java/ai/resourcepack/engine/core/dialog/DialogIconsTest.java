package ai.resourcepack.engine.core.dialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Studio dialog's markers for this server's icons.
 *
 * <p>The properties the open path leans on: a marker becomes the icon's
 * character in the default font, untinted and with no shadow; the icon and
 * whatever space follows it come to exactly the marker's room, whether the icon
 * is narrower, as wide or wider; an id nothing knows is left a blank space; a
 * marker written with escaped spaces is found too; and a dialog with no marker
 * comes back as the very string it was.
 */
class DialogIconsTest {

    /** A marker as Studio's dialog builder writes it: spaces summing to the room, then the insertion. */
    private static String marker(int room, String id, boolean escaped) {
        String spaces = DialogIcons.spaces(room);
        if (escaped) {
            StringBuilder e = new StringBuilder();
            for (char c : spaces.toCharArray()) {
                e.append(String.format("\\u%04x", (int) c));
            }
            spaces = e.toString();
        }
        return "{\"text\":\"" + spaces + "\",\"font\":\"minecraft:dialog_space\",\"insertion\":\"rp:icon:" + room + ":" + id + "\"}";
    }

    private static String dialog(String... runs) {
        return "{\"type\":\"minecraft:notice\",\"title\":\"Status\",\"body\":[{\"type\":\"minecraft:plain_message\","
                + "\"contents\":{\"text\":\"\",\"extra\":[{\"text\":\"\\ue004\",\"font\":\"minecraft:dialog_space\"},"
                + String.join(",", runs) + "]},\"width\":400}]}";
    }

    private static JsonArray extra(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("body").get(0).getAsJsonObject()
                .getAsJsonObject("contents").getAsJsonArray("extra");
    }

    /** How far a space-font run moves the pen. */
    private static int spaceAdvance(String text) {
        int sum = 0;
        for (char c : text.toCharArray()) {
            if (c >= DialogIcons.BACK && c < DialogIcons.BACK + DialogIcons.STEPS) {
                sum -= 1 << (c - DialogIcons.BACK);
            } else if (c >= DialogIcons.SPACE && c < DialogIcons.SPACE + DialogIcons.STEPS) {
                sum += 1 << (c - DialogIcons.SPACE);
            } else {
                throw new AssertionError("not a space: " + (int) c);
            }
        }
        return sum;
    }

    private static final Map<String, DialogIcons.Icon> ICONS = Map.of(
            "status:thirst", new DialogIcons.Icon("\uE101", 10),
            "status:hunger", new DialogIcons.Icon("\uE102", 7),
            "status:wide", new DialogIcons.Icon("\uE103", 14));

    private static Optional<DialogIcons.Icon> lookup(String id) {
        return Optional.ofNullable(ICONS.get(id));
    }

    /** The icon's run and how far it and its space move the pen, given the icon's own advance. */
    private static int advanceOf(JsonObject run, Map<String, Integer> glyphs) {
        int sum = glyphs.getOrDefault(run.get("text").getAsString(), 0);
        if (run.has("extra")) {
            for (JsonElement child : run.getAsJsonArray("extra")) {
                sum += spaceAdvance(child.getAsJsonObject().get("text").getAsString());
            }
        }
        return sum;
    }

    @Test
    void anIconComesToItsMarkersRoomWhateverItsOwnWidth() {
        Map<String, Integer> glyphs = Map.of("\uE101", 10, "\uE102", 7, "\uE103", 14);
        for (String id : new String[] {"status:thirst", "status:hunger", "status:wide"}) {
            String out = DialogIcons.fill(dialog(marker(10, id, false)), DialogIconsTest::lookup);
            JsonObject run = extra(out).get(1).getAsJsonObject();
            assertEquals(ICONS.get(id).character(), run.get("text").getAsString(), id);
            assertEquals("minecraft:default", run.get("font").getAsString());
            assertEquals("white", run.get("color").getAsString());
            assertEquals(0, run.get("shadow_color").getAsInt());
            assertEquals(10, advanceOf(run, glyphs), id + " moves the pen as far as its marker did");
            assertFalse(out.contains(DialogIcons.INSERTION), "no marker is left");
        }
    }

    @Test
    void anIconAsWideAsItsRoomHasNoSpaceAfterIt() {
        JsonObject run = extra(DialogIcons.fill(dialog(marker(10, "status:thirst", false)), DialogIconsTest::lookup)).get(1).getAsJsonObject();
        assertFalse(run.has("extra"));
    }

    @Test
    void anIdNothingKnowsIsLeftBlank() {
        String json = dialog(marker(10, "status:unknown", false));
        assertSame(json, DialogIcons.fill(json, DialogIconsTest::lookup));
    }

    @Test
    void escapedSpacesAreFoundToo() {
        String out = DialogIcons.fill(dialog(marker(9, "status:hunger", true)), DialogIconsTest::lookup);
        assertEquals("\uE102", extra(out).get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void everyMarkerIsSwappedAndTheRestIsUntouched() {
        String before = "{\"text\":\"Thirst\"}";
        String out = DialogIcons.fill(dialog(before, marker(10, "status:thirst", false), marker(8, "status:hunger", false)), DialogIconsTest::lookup);
        JsonArray runs = extra(out);
        assertEquals("Thirst", runs.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("\uE101", runs.get(2).getAsJsonObject().get("text").getAsString());
        assertEquals("\uE102", runs.get(3).getAsJsonObject().get("text").getAsString());
        assertTrue(DialogIcons.any(dialog(marker(10, "status:thirst", false))));
    }

    @Test
    void aDialogWithNoMarkerIsTheSameString() {
        String json = dialog("{\"text\":\"hello\"}");
        assertFalse(DialogIcons.any(json));
        assertSame(json, DialogIcons.fill(json, DialogIconsTest::lookup));
    }

    @Test
    void spacesSumToTheirAdvanceBothWays() {
        for (int n = -300; n <= 300; n++) {
            assertEquals(n, spaceAdvance(DialogIcons.spaces(n)), "spaces(" + n + ")");
        }
    }
}
