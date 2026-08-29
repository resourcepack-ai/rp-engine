package ai.resourcepack.engine.core;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a command mentioned in a sentence starts and stops.
 *
 * <p>Every case here is a real line this plugin sends. The one that matters is
 * {@link #aSentenceCarriesOnAfterTheCommand()}: a rule that took following
 * words would put "first" inside the command and suggest something that does
 * not exist, and it would do it in the message a player sees most.
 */
class ChatTest {

    @BeforeEach
    void setUp() {
        // What EngineCommand hands it at startup, derived from the help.
        Chat.vocabulary(Set.of("sync <code>", "sync add <player>", "liquid corner",
                "liquid fill <id>", "give <id> [amount]", "emote <name|stop>", "distribute <code>",
                "hud <id|clear> [player]"));
    }

    private static List<ClickEvent> clicks(String line) {
        return Arrays.stream(Chat.linkify(line))
                .map(BaseComponent::getClickEvent)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static Optional<ClickEvent> only(String line) {
        List<ClickEvent> found = clicks(line);
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    private static String plain(String line) {
        StringBuilder all = new StringBuilder();
        for (BaseComponent part : Chat.linkify(line)) {
            all.append(part.toPlainText());
        }
        return all.toString();
    }

    @Test
    void aSentenceCarriesOnAfterTheCommand() {
        ClickEvent click = only("You are not synced. /rp sync <code> first.").orElseThrow();

        assertEquals("/rp sync <code>", click.getValue());
    }

    @Test
    void aCommandWithNothingToFillInIsRun() {
        ClickEvent click = only("Mark a corner first with /rp liquid corner.").orElseThrow();

        assertEquals("/rp liquid corner", click.getValue());
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.getAction());
    }

    @Test
    void aCommandWithAPlaceholderIsSuggested() {
        ClickEvent click = only("/rpengine liquid fill <id>").orElseThrow();

        assertEquals("/rpengine liquid fill <id>", click.getValue());
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.getAction());
    }

    @Test
    void aChoiceIsPartOfTheCommand() {
        assertEquals("/rpengine hud <id|clear> [player]",
                only("/rpengine hud <id|clear> [player]").orElseThrow().getValue());
    }

    @Test
    void anIdIsAnArgument() {
        assertEquals("/rp liquid fill mypack:acid",
                only("Run /rp liquid fill mypack:acid to make it acid.").orElseThrow().getValue());
    }

    @Test
    void punctuationBelongsToTheSentence() {
        assertEquals("/rp liquid corner",
                only("Try /rp liquid corner, then stand opposite.").orElseThrow().getValue());
    }

    @Test
    void twoCommandsInOneLineAreTwoClicks() {
        assertEquals(2, clicks("/rpengine distribute <code> serves it, /rp liquid corner does not").size());
    }

    @Test
    void somebodyElsesCommandIsLeftAlone() {
        assertTrue(clicks("Ask an admin to run /lp user you parent add builder.").isEmpty());
    }

    @Test
    void aWordThatIsNotACommandIsNotOne() {
        assertTrue(clicks("Nothing here mentions anything at all.").isEmpty());
    }

    /** The text a player reads has to be exactly what was written. */
    @Test
    void nothingIsLostOrDuplicated() {
        String line = "You are not synced. /rp sync <code> first.";

        assertEquals(line, plain(line));
    }

    @Test
    void colourSurvivesACommandInTheMiddle() {
        // §7 grey, then a command, then more grey text: the tail must still be
        // grey rather than inheriting whatever the command component carried.
        String line = "§7Try §b/rp liquid corner§7 and then stand opposite.";

        assertEquals("Try /rp liquid corner and then stand opposite.", plain(line));
        assertFalse(clicks(line).isEmpty());
    }
}
