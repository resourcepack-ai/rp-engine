package ai.resourcepack.engine.core.dialog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player's items in a Studio dialog's slots.
 *
 * <p>The properties the open path leans on: a marked slot gets the item's icon,
 * count and tooltip and an empty one gets nothing; what replaces a marker comes
 * to no advance at all, whatever the count; an item tooltip this server would
 * refuse is taken out and every other left alone; and a dialog with nothing to
 * do comes back as the very string it was.
 */
class DialogItemsTest {

    /** A live slot as Studio sends it: the clickable run on one line, the icon's marker after it. */
    private static final String BODY = "{\"type\":\"minecraft:notice\",\"title\":\"Backpack\",\"body\":[{\"type\":\"minecraft:plain_message\","
            + "\"contents\":{\"text\":\"\",\"extra\":["
            + "{\"text\":\"\\ue004\",\"font\":\"minecraft:dialog_space\",\"insertion\":\"rp:slot:9\"},"
            + "{\"text\":\"\",\"font\":\"minecraft:dialog_items_1\",\"insertion\":\"rp:item:9\"},"
            + "{\"text\":\"\\ue004\",\"font\":\"minecraft:dialog_space\",\"insertion\":\"rp:slot:10\"},"
            + "{\"text\":\"\",\"font\":\"minecraft:dialog_items_1\",\"insertion\":\"rp:item:10\"}"
            + "]},\"width\":400}]}";

    private static JsonObject hover(String id, int count) {
        JsonObject hover = new JsonObject();
        hover.addProperty("action", "show_item");
        hover.addProperty("id", id);
        hover.addProperty("count", count);
        return hover;
    }

    private static IntFunction<DialogItems.Shown> holding(Map<Integer, DialogItems.Shown> slots) {
        return slots::get;
    }

    @Test
    void putsTheItemTheCountAndTheTooltipInAMarkedSlot() {
        DialogItems.Shown diamonds = new DialogItems.Shown("minecraft:diamond", 64, false, hover("minecraft:diamond", 64));
        String out = DialogItems.fill(BODY, holding(Map.of(9, diamonds)), id -> true);
        assertFalse(out.contains("rp:item:"), out);
        assertFalse(out.contains("rp:slot:"), out);
        assertTrue(out.contains("\"text\":\"" + DialogItemIcons.glyph("minecraft:diamond") + "\""), out);
        assertTrue(out.contains("\"hover_event\":{\"action\":\"show_item\",\"id\":\"minecraft:diamond\",\"count\":64}"), out);
        String count = "" + (char) (DialogItems.TWIN_BASE + 6) + (char) (DialogItems.TWIN_BASE + 4) + "64" + (char) DialogItems.BACK;
        assertTrue(out.contains("\"text\":\"" + count + "\""), out);
        // The empty slot beside it: no icon, no tooltip, and its run kept.
        assertEquals(1, out.split("show_item", -1).length - 1, out);
        JsonParser.parseString(out);
    }

    @Test
    void whatReplacesAMarkerAdvancesNothing() {
        // The icon font's advances: every icon 17, a digit and its twin 6 and -6, the step back -17.
        for (int count : new int[] {1, 2, 9, 10, 64, 99}) {
            DialogItems.Shown shown = new DialogItems.Shown("minecraft:stone", count, false, hover("minecraft:stone", count));
            int pen = 0;
            for (JsonObject part : DialogItems.icon(shown, "minecraft:dialog_items_0")) {
                for (char c : part.get("text").getAsString().toCharArray()) {
                    if (c == DialogItems.BACK) {
                        pen -= 17;
                    } else if (c >= DialogItems.TWIN_BASE && c < DialogItems.TWIN_BASE + 10) {
                        pen -= 6;
                    } else if (c >= '0' && c <= '9') {
                        pen += 6;
                    } else if (c >= DialogItemIcons.BASE && c < DialogItems.TWIN_BASE) {
                        pen += 17;
                    } else {
                        throw new AssertionError("an unexpected character " + (int) c);
                    }
                }
            }
            assertEquals(0, pen, "a count of " + count);
        }
    }

    @Test
    void anItemWithAModelOfItsOwnIsTheNoPictureIcon() {
        DialogItems.Shown sword = new DialogItems.Shown("minecraft:diamond_sword", 1, true, hover("minecraft:diamond_sword", 1));
        String out = DialogItems.fill(BODY, holding(Map.of(10, sword)), id -> true);
        assertTrue(out.contains("\"text\":\"" + (char) DialogItemIcons.BASE + "\""), out);
        assertFalse(out.contains("\"text\":\"" + DialogItemIcons.glyph("minecraft:diamond_sword") + "\""), out);
    }

    @Test
    void anItemTheServerDoesNotKnowIsTakenOutOfItsTooltip() {
        String json = "{\"body\":[{\"contents\":{\"extra\":["
                + "{\"text\":\"\\ue004\",\"hover_event\":{\"action\":\"show_item\",\"id\":\"minecraft:copper_helmet\",\"count\":1},"
                + "\"click_event\":{\"action\":\"run_command\",\"command\":\"buy helmet\"}},"
                + "{\"text\":\"\\ue004\",\"hover_event\":{\"action\":\"show_item\",\"id\":\"minecraft:diamond\",\"count\":1}},"
                + "{\"text\":\"\\ue004\",\"hover_event\":{\"action\":\"show_text\",\"value\":\"words\"}}"
                + "]}}]}";
        String out = DialogItems.fill(json, slot -> null, id -> !id.equals("minecraft:copper_helmet"));
        assertFalse(out.contains("copper_helmet"), out);
        assertTrue(out.contains("buy helmet"), out);
        assertTrue(out.contains("minecraft:diamond"), out);
        assertTrue(out.contains("show_text"), out);
    }

    @Test
    void aDialogWithNothingToDoIsTheSameString() {
        String json = "{\"type\":\"minecraft:notice\",\"title\":\"Hi\"}";
        assertSame(json, DialogItems.fill(json, slot -> null, id -> true));
        String known = "{\"hover_event\":{\"action\":\"show_item\",\"id\":\"minecraft:stone\"}}";
        assertSame(known, DialogItems.fill(known, slot -> null, id -> true));
    }

    @Test
    void onlyAPageWithSlotsIsPerPlayer() {
        assertTrue(DialogItems.perPlayer(BODY));
        assertFalse(DialogItems.perPlayer("{\"hover_event\":{\"action\":\"show_item\",\"id\":\"minecraft:stone\"}}"));
    }

    @Test
    void theIconListKnowsItsItemsWithOrWithoutTheNamespace() {
        assertTrue(DialogItemIcons.has("diamond_sword"));
        assertTrue(DialogItemIcons.has("minecraft:diamond_sword"));
        assertFalse(DialogItemIcons.has("minecraft:not_an_item"));
        assertEquals(DialogItemIcons.glyph("stone"), DialogItemIcons.glyph("minecraft:stone"));
        assertEquals((char) DialogItemIcons.BASE, DialogItemIcons.glyph("minecraft:not_an_item"));
        assertTrue(DialogItemIcons.glyph("minecraft:zombie_head") < DialogItems.TWIN_BASE, "the icons run into the twins");
    }
}
