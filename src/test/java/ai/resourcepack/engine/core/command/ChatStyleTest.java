package ai.resourcepack.engine.core.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette, and the one piece of arithmetic in it.
 *
 * <p>A hex colour is seven codes rather than one — {@code §x} and then each
 * digit with its own section sign — and getting that wrong does not throw, it
 * prints the digits into somebody's chat.
 */
class ChatStyleTest {

    /** A string as a player reads it — the colour codes are between the words. */
    private static String plain(String coloured) {
        return coloured.replaceAll("§.", "");
    }

    @Test
    void aHexColourBecomesSevenCodes() {
        assertEquals("§x§3§6§7§0§f§8", ChatStyle.colour("#3670f8", "#000000"));
    }

    @Test
    void theHashIsOptionalAndSoIsTheCase() {
        String expected = ChatStyle.colour("#3670f8", "#000000");
        assertEquals(expected, ChatStyle.colour("3670f8", "#000000"));
        assertEquals(expected, ChatStyle.colour("#3670F8", "#000000"));
        assertEquals(expected, ChatStyle.colour("  #3670f8  ", "#000000"));
    }

    @Test
    void anythingThatIsNotAColourFallsBack() {
        String fallback = ChatStyle.colour("#aabbcc", "#000000");

        // A typo in a config is not a reason for a plugin to stop talking, and
        // a wrong shade is something somebody can see and fix.
        assertEquals(fallback, ChatStyle.colour("blue", "#aabbcc"));
        assertEquals(fallback, ChatStyle.colour("#12345", "#aabbcc"));
        assertEquals(fallback, ChatStyle.colour("#gggggg", "#aabbcc"));
        assertEquals(fallback, ChatStyle.colour(null, "#aabbcc"));
        assertEquals(fallback, ChatStyle.colour("", "#aabbcc"));
    }

    @Test
    void theDefaultPrefixIsTheBrandBlue() {
        String prefix = ChatStyle.defaults().prefix();

        assertTrue(prefix.contains(ChatStyle.colour(ChatStyle.BRAND, ChatStyle.BRAND)),
                "the default prefix is not the brand blue");
        assertEquals("[RPEngine] ", plain(prefix), "the default prefix does not name the plugin");
    }

    @Test
    void anEmptyNameKeepsTheDefaultRatherThanEmptyBrackets() {
        assertEquals("[RPEngine] ",
                plain(ChatStyle.of("  ", null, null, null, null, null, null).prefix()));
    }

    @Test
    void aConfiguredNameIsUsedVerbatim() {
        assertEquals("[Packs] ",
                plain(ChatStyle.of("Packs", null, null, null, null, null, null).prefix()));
    }

    @Test
    void everyColourIsItsOwnSetting() {
        ChatStyle style = ChatStyle.of("X", "#111111", "#222222", "#333333", "#444444",
                "#555555", "#666666");

        assertEquals(ChatStyle.colour("#333333", "#000000"), style.body());
        assertEquals(ChatStyle.colour("#444444", "#000000"), style.accent());
        assertEquals(ChatStyle.colour("#555555", "#000000"), style.error());
        assertEquals(ChatStyle.colour("#666666", "#000000"), style.success());
        assertTrue(style.prefix().contains(ChatStyle.colour("#111111", "#000000")));
        assertTrue(style.prefix().contains(ChatStyle.colour("#222222", "#000000")));
    }

    @Test
    void aHeadingIsTheAccentInBold() {
        ChatStyle style = ChatStyle.defaults();
        assertEquals(style.accent() + "§l", style.heading());
    }
}
