package ai.resourcepack.engine.core.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading {@code --showYourself} off the words of an {@code /emote}.
 *
 * <p><b>The flag has to leave the list before anything resolves a name, and
 * that is the whole reason this is tested rather than eyeballed.</b> Emote
 * names are free text with spaces in them, so the director resolves them by
 * taking the longest leading run of words that names something and calling the
 * rest a cast. A flag left in that list is not ignored — it is a candidate.
 * {@code /emote Sprint --showYourself} would look for a group called "Sprint
 * --showYourself", fail, look for a group called "Sprint", find it, and then
 * refuse the whole command because a group takes no cast and one word was left
 * over. The failure is a sentence about needing other players, in front of a
 * flag whose entire job is that it changes nothing about who is in the emote.
 *
 * <p>Nothing here touches a server: both functions were written as statics over
 * a {@code String[]} precisely so the parsing could be reached without one.
 */
class ShowYourselfFlagTest {

    @Test
    void findsTheFlagWhateverCaseItIsTyped() {
        // Nobody types a camel-cased flag the same way twice, and the one
        // spelling that must work is the one the docs and the studio card
        // print.
        assertTrue(EmoteCommands.hasShowYourself(new String[] {"Sprint", "--showYourself"}));
        assertTrue(EmoteCommands.hasShowYourself(new String[] {"Sprint", "--showyourself"}));
        assertTrue(EmoteCommands.hasShowYourself(new String[] {"Sprint", "--SHOWYOURSELF"}));
    }

    @Test
    void findsItBeforeTheNameToo() {
        // Not required by anything, but a flag that only works in one position
        // is a flag people report as broken.
        assertTrue(EmoteCommands.hasShowYourself(new String[] {"--showYourself", "Sprint"}));
    }

    @Test
    void absentIsAbsent() {
        assertFalse(EmoteCommands.hasShowYourself(new String[] {"Sprint"}));
        assertFalse(EmoteCommands.hasShowYourself(new String[] {}));
        // The bare word is an ordinary word. An emote may be called anything,
        // and the two dashes are what make the flag unmistakable.
        assertFalse(EmoteCommands.hasShowYourself(new String[] {"showYourself"}));
    }

    @Test
    void stripsTheFlagAndLeavesTheNameWhole() {
        // The case the director sees: a multi-word group name, intact, with
        // nothing after it that could be read as a cast member.
        assertArrayEquals(
                new String[] {"Slow", "walk"},
                EmoteCommands.withoutFlags(new String[] {"Slow", "walk", "--showYourself"}));
    }

    @Test
    void stripsFlagsFromAnywhereInTheLine() {
        assertArrayEquals(
                new String[] {"Sprint"},
                EmoteCommands.withoutFlags(new String[] {"--showYourself", "Sprint"}));
    }

    @Test
    void stripsFlagsItDoesNotKnow() {
        // An unknown flag left in the list would be hunted for as a player and
        // refused with CAST_NOT_ONLINE — a sentence about somebody being
        // offline, in front of a typo.
        assertArrayEquals(
                new String[] {"Sprint"},
                EmoteCommands.withoutFlags(new String[] {"Sprint", "--showYourselves", "--verbose"}));
    }

    @Test
    void leavesAnOrdinaryLineAlone() {
        // The overwhelmingly common call: no flag, and every word still a word.
        // A cast member's name must survive this untouched.
        assertArrayEquals(
                new String[] {"Hug", "Steve"},
                EmoteCommands.withoutFlags(new String[] {"Hug", "Steve"}));
    }
}
