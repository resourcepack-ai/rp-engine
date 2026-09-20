package ai.resourcepack.engine.core.dialog;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON a dialog is written in, as the SNBT a command carries.
 *
 * <p>What is worth holding still here is the pair of properties the open path
 * depends on: the output is ONE line whatever the input looked like, and it
 * spells values the way the game's own parser reads them. The cases that
 * answer empty are the other half, and each is a case the game would have
 * refused with a stack trace instead.
 */
class DialogSnbtTest {

    private static String snbt(String json) {
        Optional<String> out = DialogSnbt.of(json);
        assertTrue(out.isPresent(), "should have transcribed: " + json);
        return out.get();
    }

    @Test
    void writesAnObjectOnOneLine() {
        // Pretty-printed, which is what the authored format produces - and a
        // command is one line, so this is the reason this class exists at all.
        String json = "{\n  \"type\": \"minecraft:notice\",\n  \"title\": \"Hello\"\n}\n";
        String out = snbt(json);
        assertEquals("{\"type\":\"minecraft:notice\",\"title\":\"Hello\"}", out);
        assertFalse(out.contains("\n"));
    }

    @Test
    void keepsTheNumericFormBecauseItPicksTheTag() {
        // 5 is an int and 5.0 a double, and a codec wanting an int takes only
        // the first. Reformatting either one silently changes what the game
        // reads.
        assertEquals("{\"width\":150}", snbt("{\"width\": 150}"));
        assertEquals("{\"step\":0.5}", snbt("{\"step\": 0.5}"));
    }

    @Test
    void spellsOutAnExponent() {
        // Valid JSON, and SNBT reads a bare e as the start of a type suffix.
        assertEquals("{\"n\":1000}", snbt("{\"n\": 1e3}"));
    }

    @Test
    void writesBooleansAsThemselves() {
        assertEquals("{\"pause\":false}", snbt("{\"pause\": false}"));
    }

    @Test
    void escapesOnlyQuoteAndBackslash() {
        assertEquals("{\"t\":\"a\\\"b\\\\c\"}", snbt("{\"t\": \"a\\\"b\\\\c\"}"));
    }

    @Test
    void passesAPrivateUseGlyphThrough() {
        // How a Studio pack draws a dialog's picture. An ordinary character to
        // both spellings, and it must not be escaped into something else.
        String out = snbt("{\"t\": \"\\ue100\"}");
        assertEquals("{\"t\":\"\uE100\"}", out);
    }

    @Test
    void keepsNestedShapes() {
        String out = snbt("{\"body\": [{\"type\": \"minecraft:plain_message\", \"contents\": {\"text\": \"x\"}}]}");
        assertEquals("{\"body\":[{\"type\":\"minecraft:plain_message\",\"contents\":{\"text\":\"x\"}}]}", out);
    }

    @Test
    void refusesAControlCharacterInAString() {
        // The realistic one: a line break written into a body line. SNBT has no
        // spelling for it - not raw, not as an escape - so the caller falls
        // back to the registry rather than sending something the game refuses.
        assertTrue(DialogSnbt.of("{\"t\": \"a\\nb\"}").isEmpty());
    }

    @Test
    void refusesANull() {
        // Dropping the field would change what the dialog says and dropping a
        // list entry would move every entry after it.
        assertTrue(DialogSnbt.of("{\"t\": null}").isEmpty());
        assertTrue(DialogSnbt.of("{\"body\": [null]}").isEmpty());
    }

    @Test
    void refusesWhatIsNotAnObject() {
        // A string would be read as a resource location and looked UP, which is
        // the registry route wearing this one's clothes.
        assertTrue(DialogSnbt.of("\"mypack:shop\"").isEmpty());
        assertTrue(DialogSnbt.of("[]").isEmpty());
        assertTrue(DialogSnbt.of("not json at all").isEmpty());
        assertTrue(DialogSnbt.of("").isEmpty());
        assertTrue(DialogSnbt.of(null).isEmpty());
    }

    @Test
    void carriesAWholeStudioDialog() {
        // The shape Studio emits, which is the one that matters most: it has to
        // survive verbatim, glyphs and all. Checked as a round trip rather than
        // against a literal, because the point is that nothing is lost.
        String json = "{\"type\":\"minecraft:multi_action\",\"title\":\"Shop\","
                + "\"can_close_with_escape\":true,\"pause\":false,\"after_action\":\"close\","
                + "\"body\":[{\"type\":\"minecraft:plain_message\",\"contents\":"
                + "{\"text\":\"\uE100\",\"color\":\"white\",\"shadow_color\":0},\"width\":256}],"
                + "\"columns\":2,\"actions\":[{\"label\":{\"text\":\"\uE101\",\"color\":\"white\","
                + "\"shadow_color\":0},\"width\":150,\"action\":{\"type\":\"minecraft:run_command\","
                + "\"command\":\"/shop buy\"}}]}";
        assertEquals(json, snbt(json));
    }
}
