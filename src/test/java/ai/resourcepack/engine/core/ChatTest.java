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
        // What EngineCommand hands it at startup. Every command, not every
        // command with a line of help — the distinction this fixture used to
        // get wrong, which is why these tests passed while the real thing
        // truncated "/rp sync accept" to "/rp sync".
        Chat.commands(Set.of("sync <code>", "sync add <player>", "sync remove <player>",
                "sync accept", "sync deny", "sync who", "sync leave", "sync stop",
                "liquid corner", "liquid fill <id>", "liquid clear",
                "give <id> [amount]", "emote <name|stop>", "reload", "push",
                "accept", "deny",
                "distribute <code|off>", "say <text>", "sound <id> [player]",
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
    void theInvitationLinksTheCommandItNames() {
        // The one that was wrong in production. "accept" has no line of help,
        // so a word-list matcher stopped at "sync", and the truncated command
        // had no placeholder left in it — which made it RUN rather than
        // SUGGEST. A player clicking the invitation ran "/rp sync" and got a
        // usage error.
        ClickEvent click = only("Alice wants to share a pack with you. /rp sync accept, or deny.")
                .orElseThrow();
        assertEquals("/rp sync accept", click.getValue());
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.getAction());
    }

    @Test
    void bothHalvesOfAnInvitationAreClickable() {
        // "or deny" is a word in a sentence, not a command, so only the first
        // half was ever clickable. The message names both in full now.
        List<ClickEvent> both =
                clicks("Alice wants to share a pack with you. /rp sync accept or /rp sync deny.");
        assertEquals(2, both.size());
        assertEquals("/rp sync accept", both.get(0).getValue());
        assertEquals("/rp sync deny", both.get(1).getValue());
    }

    @Test
    void aVerbWithNoLineOfHelpIsStillACommand() {
        assertEquals("/rp sync remove <player>",
                only("/rp sync remove <player>").orElseThrow().getValue());
        assertEquals("/rp sync who", only("Nobody else. /rp sync who to check.")
                .orElseThrow().getValue());
    }

    @Test
    void aCommandWordDoesNotEatTheSameWordInTheSentence() {
        // "stop" is a real command word and "stop serving it" is a sentence.
        // A word list cannot tell those apart; a prefix match can, because no
        // command continues "distribute off" with "stop".
        ClickEvent click = only("/rp distribute off     stop serving it").orElseThrow();
        assertEquals("/rp distribute off", click.getValue());
    }

    @Test
    void aPlaceholderWithSpacesInItIsOneArgument() {
        // Cut at the first space, this suggested "/rp say <text".
        assertEquals("/rp say <text with :namespace:id: in it>",
                only("/rp say <text with :namespace:id: in it>").orElseThrow().getValue());
    }

    @Test
    void aTruncatedCommandIsNeverWiredToRun() {
        // The compounding failure: truncation usually removes the placeholder,
        // and a command with no placeholder is RUN rather than SUGGEST. So a
        // wrong span did not just look wrong, it executed something.
        for (String line : List.of(
                "Alice wants to share a pack with you. /rp sync accept, or deny.",
                "/rp sync remove <player>",
                "/rp distribute off     stop serving it")) {
            for (ClickEvent click : clicks(line)) {
                boolean fillIn = click.getValue().indexOf('<') >= 0
                        || click.getValue().indexOf('[') >= 0
                        || click.getValue().indexOf('|') >= 0;
                assertEquals(fillIn ? ClickEvent.Action.SUGGEST_COMMAND : ClickEvent.Action.RUN_COMMAND,
                        click.getAction(), click.getValue());
            }
        }
    }

    @Test
    void aWordTheCommandLayerKnowsIsNotACommandInTheWrongPlace() {
        // "push" is a command. "Nothing to push." is a sentence about one, and
        // the word is not preceded by a slash, so nothing should link at all.
        assertTrue(clicks("Nothing to push.").isEmpty());
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
