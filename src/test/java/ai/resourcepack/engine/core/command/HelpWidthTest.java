package ai.resourcepack.engine.core.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every line of {@code /rp}'s help has to fit on one line of chat.
 *
 * <p>This exists because the first version of that help did neither thing it
 * was trying to do. It padded a column to a fixed number of characters, on the
 * belief that Minecraft's chat font is monospace — it is proportional, so the
 * descriptions started at a different place on every row — and the padded
 * lines came to about seventy characters, so most of them wrapped. Both faults
 * are invisible from a console and obvious to anyone reading it in game, which
 * is exactly the kind of thing worth asserting.
 *
 * <p>The areas are built with nulls: {@code help()} is a literal list and
 * touches no field, which is the point of it being one.
 */
class HelpWidthTest {

    private static List<Help> everyLine() {
        List<Area> areas = List.of(
                new ContentCommands(null, null, null, null, null, null, null),
                new ModelCommands(null, null, null, null, null, null),
                new InterfaceCommands(null, null, null),
                new EmoteCommands(null, null),
                new SyncCommands(null, null, null, null, null, null),
                new LiquidCommands(null, null, null, null));

        List<Help> lines = new ArrayList<>();
        for (Area area : areas) {
            assertFalse(area.title().isEmpty(), "an area with no title has no heading");
            lines.addAll(area.help());
        }
        return lines;
    }

    @Test
    void noLineWraps() {
        for (Help line : everyLine()) {
            assertTrue(line.plain().length() <= Help.MAX_WIDTH,
                    () -> "too long for one line of chat (" + line.plain().length() + " > "
                            + Help.MAX_WIDTH + "): " + line.plain());
        }
    }

    @Test
    void everyLineSaysWhatItDoes() {
        for (Help line : everyLine()) {
            assertFalse(line.text().isEmpty(),
                    () -> line.signature() + " has no description");
            // A description that just repeats the command teaches nothing.
            assertFalse(line.text().equalsIgnoreCase(line.command()),
                    () -> line.signature() + " describes itself as its own name");
        }
    }

    @Test
    void theRenderedLineIsThePlainOneWithColourInIt() {
        for (Help line : everyLine()) {
            assertEquals(line.plain(), line.render().replaceAll("§.", ""),
                    "colour codes changed the text rather than only its colour");
        }
    }

    @Test
    void aSubcommandIsTheFirstWordOfItsLine() {
        // "sync add" and "liquid fill" are verbs inside a subcommand; the
        // router dispatches and gates on the subcommand alone.
        assertEquals("sync", Help.of("sync add", "<player>", "share your pushes").command());
        assertEquals("liquid", Help.of("liquid clear", "remove it").command());
        assertEquals("give", Help.of("give", "<id> [n]", "give yourself one").command());
    }
}
