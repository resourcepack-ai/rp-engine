package ai.resourcepack.engine.core.item;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What goes into a console step. The console has every permission, so nothing
 * a player can choose (their name, a placeholder about them) may add a word
 * to its command.
 */
class ActionRunnerTest {

    private static final Map<String, String> VALUES = Map.of(
            "player", "Steve", "uuid", "069a79f4-44e9-4726-a5be-fca90e38aaf5",
            "world", "world", "x", "1", "y", "64", "z", "-3");

    private static final UnaryOperator<String> NONE = token -> token;

    @Test
    void fillsTheValuesItKnows() {
        assertEquals("give Steve diamond 1", ActionRunner.fillCommand("give {player} diamond 1", VALUES, NONE));
        assertEquals("tp Steve 1 64 -3", ActionRunner.fillCommand("tp {player} {x} {y} {z}", VALUES, NONE));
        assertEquals("say {unknown}", ActionRunner.fillCommand("say {unknown}", VALUES, NONE));
    }

    @Test
    void asksPlaceholderApiOneValueAtATime() {
        UnaryOperator<String> papi = token -> token.equals("%player_level%") ? "30" : token;
        assertEquals("xp set Steve 30", ActionRunner.fillCommand("xp set {player} %player_level%", VALUES, papi));
        assertEquals("say 50%", ActionRunner.fillCommand("say 50%", VALUES, papi), "a lone percent is not a placeholder");
    }

    @Test
    void aValueWithASpaceSkipsTheStep() {
        UnaryOperator<String> nick = token -> token.equals("%essentials_nickname%") ? "x op Mallory" : token;
        assertNull(ActionRunner.fillCommand("give %essentials_nickname% diamond", VALUES, nick));
        assertNull(ActionRunner.fillCommand("give {player} diamond",
                Map.of("player", "Steve\nop Mallory"), NONE), "a control character");
        assertNull(ActionRunner.fillCommand("give {player} diamond",
                Map.of("player", "Steve x"), NONE), "a no-break space");
    }

    @Test
    void aValueIsPutInOnceAndNotReadAgain() {
        // A name that looks like a placeholder stays a name.
        UnaryOperator<String> papi = token -> "resolved";
        assertEquals("give %oops% diamond",
                ActionRunner.fillCommand("give {player} diamond", Map.of("player", "%oops%"), papi));
    }

    @Test
    void theAuthorsOwnSpacesAreTheirs() {
        assertEquals("say hello there", ActionRunner.fillCommand("say hello there", VALUES, NONE));
    }
}
