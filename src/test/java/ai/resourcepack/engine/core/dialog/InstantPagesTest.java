package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Page turns the client makes by itself.
 *
 * <p>A link may become a {@code show_dialog} by id only when the registry's copy
 * of every page the client could then reach is exactly the player's own — one
 * stale page is a screen from somebody else's push. And the datapack must be
 * written so that the copy and the comparison agree, byte for byte.
 */
class InstantPagesTest {

    private static ContentId id(String text) {
        return ContentId.parse(text).orElseThrow();
    }

    /** A page whose body and footer button both turn to {@code target}. */
    private static String page(String title, String text, String... targets) {
        StringBuilder extra = new StringBuilder();
        for (String target : targets) {
            if (extra.length() > 0) {
                extra.append(',');
            }
            extra.append("{\"text\":\"x\",\"click_event\":{\"action\":\"run_command\",\"command\":\"rp page ")
                    .append(target).append("\"},\"hover_event\":{\"action\":\"show_text\",\"value\":\"rp page ")
                    .append(target).append("\"}}");
        }
        return "{\"type\":\"minecraft:notice\",\"title\":\"" + title + "\",\"body\":[{\"type\":\"minecraft:plain_message\","
                + "\"contents\":{\"text\":\"" + text + "\",\"extra\":[" + extra + "]}}],"
                + "\"action\":{\"label\":\"Next\",\"action\":{\"type\":\"minecraft:run_command\",\"command\":\"rp page "
                + targets[0] + "\"}}}";
    }

    private static final String MENU = page("Menu", "Pick one", "studio:menu.b");
    private static final String B = page("B", "Plain words", "studio:menu", "studio:menu.c");
    /** Says the player's name, so only the engine can open it. */
    private static final String C = page("C", "Hello {player}", "studio:menu");

    private static Map<ContentId, String> all() {
        Map<ContentId, String> all = new LinkedHashMap<>();
        all.put(id("studio:menu"), MENU);
        all.put(id("studio:menu.b"), B);
        all.put(id("studio:menu.c"), C);
        return all;
    }

    /** The datapack as it would be on disk after a restart: written from {@link #all}. */
    private static Map<ContentId, String> written() {
        Map<ContentId, String> out = new LinkedHashMap<>();
        all().forEach((k, v) -> out.put(k, DialogDatapack.fileContent(v, all())));
        return out;
    }

    private static DialogsImpl engine(Map<ContentId, String> onDisk, boolean registryHasThem) {
        DialogsImpl dialogs = new DialogsImpl(new DialogDatapack(Logger.getAnonymousLogger(), onDisk, x -> registryHasThem), false);
        Map<ContentId, DialogInfo> catalogue = new LinkedHashMap<>();
        all().forEach((k, v) -> catalogue.put(k, DialogInfo.authored(k, v, k.path())));
        dialogs.replace(catalogue);
        return dialogs;
    }

    @Test
    void aBodyClickAndAFooterButtonBothBecomeShowDialog() {
        JsonObject swapped = JsonParser.parseString(DialogLinks.swap(MENU, x -> true)).getAsJsonObject();
        JsonObject click = swapped.getAsJsonArray("body").get(0).getAsJsonObject().getAsJsonObject("contents")
                .getAsJsonArray("extra").get(0).getAsJsonObject().getAsJsonObject("click_event");
        assertEquals("show_dialog", click.get("action").getAsString());
        assertEquals("studio:menu.b", click.get("dialog").getAsString());
        assertFalse(click.has("command"));
        JsonObject button = swapped.getAsJsonObject("action").getAsJsonObject("action");
        assertEquals("minecraft:show_dialog", button.get("type").getAsString());
        assertEquals("studio:menu.b", button.get("dialog").getAsString());
        assertFalse(button.has("command"));
    }

    @Test
    void aMentionOrAnUnallowedPageIsLeftAlone() {
        // Nothing allowed: the very same string comes back, not a re-serialised copy.
        assertSame(MENU, DialogLinks.swap(MENU, x -> false));
        String swapped = DialogLinks.swap(MENU, x -> true);
        // The tooltip that SAYS the command is words, not a click.
        assertTrue(swapped.contains("\"value\":\"rp page studio:menu.b\""));
        String say = "{\"click_event\":{\"action\":\"run_command\",\"command\":\"say rp page studio:menu.b\"}}";
        assertSame(say, DialogLinks.swap(say, x -> true));
    }

    @Test
    void theFileLinksOnlyToPagesWithNothingPerPlayer() {
        String file = DialogDatapack.fileContent(B, all());
        // menu is plain, so the client may open it from its registry...
        assertFalse(DialogLinks.opens(file, id("studio:menu")));
        assertTrue(file.contains("\"dialog\":\"studio:menu\""));
        // ...and C says the player's name, so turning to it still asks the engine.
        assertTrue(DialogLinks.opens(file, id("studio:menu.c")));
        // A page with no page links is written exactly as it came.
        String plain = "{\"type\":\"minecraft:notice\",\"title\":\"x\"}";
        assertSame(plain, DialogDatapack.fileContent(plain, all()));
    }

    @Test
    void everyReachablePlainPageInTheRegistryTurnsOnTheClient() {
        DialogsImpl dialogs = engine(written(), true);
        assertEquals(Set.of(id("studio:menu"), id("studio:menu.b")), dialogs.instantPages(null, id("studio:menu"), MENU));
        // From the page with the name on it as well: it reaches the same plain pages.
        assertEquals(Set.of(id("studio:menu"), id("studio:menu.b")), dialogs.instantPages(null, id("studio:menu.c"), C));
    }

    @Test
    void oneStalePageAndNothingTurnsOnTheClient() {
        Map<ContentId, String> disk = written();
        // B was pushed again after the last restart: the registry holds the old one.
        disk.put(id("studio:menu.b"), DialogDatapack.fileContent(page("B", "Older words", "studio:menu", "studio:menu.c"), all()));
        assertEquals(Set.of(), engine(disk, true).instantPages(null, id("studio:menu"), MENU));
    }

    @Test
    void nothingTurnsOnTheClientWhenTheRegistryDoesNotHaveThem() {
        // On disk, but the world has the pack disabled: the codec cannot find the ids.
        assertEquals(Set.of(), engine(written(), false).instantPages(null, id("studio:menu"), MENU));
        // And before any restart has read a datapack at all.
        assertEquals(Set.of(), engine(Map.of(), true).instantPages(null, id("studio:menu"), MENU));
    }

    @Test
    void aCommandIsHeldWithOrWithoutItsSlash() {
        String json = "{\"click_event\":{\"action\":\"run_command\",\"command\":\"buy sword\"}}";
        assertTrue(DialogLinks.holdsCommand(json, "/buy sword"));
        assertTrue(DialogLinks.holdsCommand(json, "buy sword"));
        assertFalse(DialogLinks.holdsCommand(json, "/buy swords"));
        assertFalse(DialogLinks.holdsCommand(json, "/buy"));
    }
}
