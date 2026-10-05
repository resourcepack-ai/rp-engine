package ai.resourcepack.engine.core.dialog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player's items in a Studio dialog's slots.
 *
 * <p>The properties the open path leans on: a marked slot gets the item's icon,
 * count and tooltip and an empty one gets nothing; what replaces a marker comes
 * to no advance at all, whatever the count and whether or not the stack is the
 * one picked up; a container this engine does not fill is left alone; an item
 * tooltip this server would refuse is taken out and every other left alone; and
 * a dialog with nothing to do comes back as the very string it was.
 */
class DialogItemsTest {

    /**
     * Live slots as Studio sends them: the clickable run, the icon's marker after
     * it — one of the inventory by key, one by a bare number (the first build's
     * spelling), one of the ender chest, and a marker naming a container nothing
     * here fills.
     */
    private static final String BODY = "{\"type\":\"minecraft:notice\",\"title\":\"Backpack\",\"body\":[{\"type\":\"minecraft:plain_message\","
            + "\"contents\":{\"text\":\"\",\"extra\":["
            + run("rp:slot:inv/9") + "," + marker("rp:item:inv/9") + ","
            + run("rp:slot:10") + "," + marker("rp:item:10") + ","
            + run("rp:slot:ender/3") + "," + marker("rp:item:ender/3") + ","
            + marker("rp:item:somebodys/3")
            + "]},\"width\":400}]}";

    private static String run(String insertion) {
        return "{\"text\":\"\\ue004\",\"font\":\"minecraft:dialog_space\",\"insertion\":\"" + insertion + "\"}";
    }

    private static String marker(String insertion) {
        return "{\"text\":\"\",\"font\":\"minecraft:dialog_items_1\",\"insertion\":\"" + insertion + "\"}";
    }

    private static JsonObject hover(String id, int count) {
        JsonObject hover = new JsonObject();
        hover.addProperty("action", "show_item");
        hover.addProperty("id", id);
        hover.addProperty("count", count);
        return hover;
    }

    private static Function<DialogSlots.Key, DialogItems.Shown> holding(Map<String, DialogItems.Shown> slots) {
        return key -> slots.get(key.toString());
    }

    /** How far a run of an icon font's characters moves the pen: icons 17, a digit 6, its twin -6, the step back -17. */
    private static int advance(List<JsonObject> parts) {
        int pen = 0;
        for (JsonObject part : parts) {
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
        return pen;
    }

    @Test
    void putsTheItemTheCountAndTheTooltipInAMarkedSlot() {
        DialogItems.Shown diamonds = new DialogItems.Shown("minecraft:diamond", 64, false, hover("minecraft:diamond", 64));
        String out = DialogItems.fill(BODY, holding(Map.of("inv/9", diamonds)), id -> true);
        assertFalse(out.contains("rp:item:inv/9"), out);
        assertFalse(out.contains("rp:slot:"), out);
        assertTrue(out.contains("\"text\":\"" + DialogItemIcons.glyph("minecraft:diamond") + "\""), out);
        assertTrue(out.contains("\"hover_event\":{\"action\":\"show_item\",\"id\":\"minecraft:diamond\",\"count\":64}"), out);
        String count = "" + (char) (DialogItems.TWIN_BASE + 6) + (char) (DialogItems.TWIN_BASE + 4) + "64" + (char) DialogItems.BACK;
        assertTrue(out.contains("\"text\":\"" + count + "\""), out);
        // The empty slots beside it: no icon, no tooltip, and their runs kept.
        assertEquals(1, out.split("show_item", -1).length - 1, out);
        // A container this engine does not fill is left as Studio wrote it: an empty slot.
        assertTrue(out.contains("rp:item:somebodys/3"), out);
        JsonParser.parseString(out);
    }

    @Test
    void whatReplacesAMarkerAdvancesNothing() {
        for (int count : new int[] {1, 2, 9, 10, 64, 99}) {
            for (boolean held : new boolean[] {false, true}) {
                DialogItems.Shown shown = new DialogItems.Shown("minecraft:stone", count, false, hover("minecraft:stone", count), held);
                assertEquals(0, advance(DialogItems.icon(shown, "minecraft:dialog_items_0")), "a count of " + count + (held ? ", held" : ""));
            }
        }
    }

    @Test
    void aStackPickedUpIsLitBehindItsIcon() {
        DialogItems.Shown held = new DialogItems.Shown("minecraft:stone", 12, false, hover("minecraft:stone", 12), true);
        String first = DialogItems.icon(held, "minecraft:dialog_items_0").get(0).get("text").getAsString();
        assertEquals("" + (char) DialogItemIcons.HELD + (char) DialogItems.BACK + DialogItemIcons.glyph("stone"), first);
        assertTrue(DialogItemIcons.HELD < DialogItems.TWIN_BASE, "the highlight runs into the twins");
    }

    @Test
    void anEnderChestSlotIsFilledFromItsKey() {
        DialogItems.Shown pearls = new DialogItems.Shown("minecraft:ender_pearl", 16, false, hover("minecraft:ender_pearl", 16));
        String out = DialogItems.fill(BODY, holding(Map.of("ender/3", pearls)), id -> true);
        assertFalse(out.contains("rp:item:ender/3"), out);
        assertTrue(out.contains("\"text\":\"" + DialogItemIcons.glyph("ender_pearl") + "\""), out);
    }

    @Test
    void anItemWithAModelOfItsOwnIsTheNoPictureIcon() {
        // A bare number is a slot of the inventory, as the first build wrote one.
        DialogItems.Shown sword = new DialogItems.Shown("minecraft:diamond_sword", 1, true, hover("minecraft:diamond_sword", 1));
        String out = DialogItems.fill(BODY, holding(Map.of("inv/10", sword)), id -> true);
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
