package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which pages a dialog turns to.
 *
 * <p>{@code /rp page} is run by every player, so the whole of its safety is this
 * check: a dialog opens only when the one the player was shown holds a click
 * running exactly that command. What has to hold is that a link is found
 * wherever a click can be (a footer button's action, a click on a line of the
 * body), that one page's id never matches inside another's, and that nothing
 * but a whole command string counts — a tooltip that MENTIONS a page is not a
 * link to it.
 */
class DialogLinksTest {

    private static ContentId id(String text) {
        return ContentId.parse(text).orElseThrow();
    }

    /** A Studio page as it is pushed: the links in the body's click events, the footer's Close plain. */
    private static final String STUDIO_PAGE = "{\"type\":\"minecraft:notice\",\"title\":\"Settings\","
            + "\"body\":[{\"type\":\"minecraft:plain_message\",\"contents\":{\"text\":\"\",\"extra\":["
            + "{\"text\":\"\\ue001\",\"font\":\"minecraft:dialog_space\","
            + "\"click_event\":{\"action\":\"run_command\",\"command\":\"rp page studio:settings.audio\"}},"
            + "{\"text\":\"\\ue001\",\"font\":\"minecraft:dialog_space\","
            + "\"click_event\":{\"action\":\"run_command\",\"command\":\"rp page studio:settings\"},"
            + "\"hover_event\":{\"action\":\"show_text\",\"value\":\"rp page studio:secret\"}}]},\"width\":400}],"
            + "\"action\":{\"label\":\"Close\"}}";

    @Test
    void findsALinkInTheBody() {
        assertTrue(DialogLinks.opens(STUDIO_PAGE, id("studio:settings.audio")));
        assertTrue(DialogLinks.opens(STUDIO_PAGE, id("studio:settings")));
    }

    @Test
    void oneIdNeverMatchesInsideAnother() {
        String json = "{\"command\":\"rp page studio:settings.audio\"}";
        assertFalse(DialogLinks.opens(json, id("studio:settings")));
        assertFalse(DialogLinks.opens("{\"command\":\"rp page studio:settings\"}", id("studio:settings.audio")));
    }

    @Test
    void aMentionIsNotALink() {
        // The tooltip above says the words, inside a longer string: it is not a click.
        assertFalse(DialogLinks.opens(STUDIO_PAGE, id("studio:secret")));
        assertFalse(DialogLinks.opens("{\"text\":\"Type rp page studio:shop to go\"}", id("studio:shop")));
        assertFalse(DialogLinks.opens("{\"command\":\"say rp page studio:shop\"}", id("studio:shop")));
    }

    @Test
    void anAuthoredButtonMaySpellTheCommandItsOwnWay() {
        // A hand-written dialog's footer button: slash, the long root, upper case.
        String json = "{\"type\":\"minecraft:notice\",\"action\":{\"label\":\"Next\","
                + "\"action\":{\"type\":\"minecraft:run_command\",\"command\":\"/RPEngine page MyPack:Shop\"}}}";
        assertTrue(DialogLinks.opens(json, id("mypack:shop")));
        assertTrue(DialogLinks.opens("{\"command\":\"rpe page mypack:menus/shop\"}", id("mypack:menus/shop")));
        // What dialogs/*.yml's `command: rp page …` becomes: DialogDefinitions
        // writes a space after each colon.
        String authored = "{ \"label\": \"Audio\", \"width\": 150, \"action\": { \"type\": \"minecraft:run_command\", "
                + "\"command\": \"rp page mypack:settings_audio\" } }";
        assertTrue(DialogLinks.opens(authored, id("mypack:settings_audio")));
    }

    @Test
    void listsEachPageOnceInOrder() {
        List<ContentId> found = DialogLinks.targets(STUDIO_PAGE + STUDIO_PAGE);
        assertEquals(List.of(id("studio:settings.audio"), id("studio:settings")), found);
        assertEquals(List.of(), DialogLinks.targets("{\"title\":\"No links here\"}"));
        assertEquals(List.of(), DialogLinks.targets(null));
    }

    @Test
    void theCommandIsWhatTheCheckFinds() {
        // Studio writes the command this returns; the check has to find it again.
        ContentId page = id("studio:guide.chapter_2");
        assertEquals("rp page studio:guide.chapter_2", DialogLinks.command(page));
        assertTrue(DialogLinks.opens("{\"command\":\"" + DialogLinks.command(page) + "\"}", page));
    }
}
