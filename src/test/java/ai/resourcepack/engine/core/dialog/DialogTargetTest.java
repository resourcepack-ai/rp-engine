package ai.resourcepack.engine.core.dialog;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /shop <name>} fills {@code {target}} into the dialog's console
 * buttons, so what a player may type there must never reach more than one
 * player: no selector, and no wildcard EssentialsX or CMI would read as
 * "everybody".
 */
class DialogTargetTest {

    private static final Predicate<String> NOBODY = name -> false;

    @Test
    void aPlainNameIsATarget() {
        assertTrue(DialogCommands.isTarget("Steve", NOBODY));
        assertTrue(DialogCommands.isTarget("a_b_9", NOBODY));
        assertTrue(DialogCommands.isTarget(".BedrockGuy", NOBODY));
    }

    @Test
    void aWildcardOrSelectorNeverIs() {
        for (String bad : new String[] {"*", "**", "@a", "@p", "-s", "", "a b", "{x}", "Steve*"}) {
            assertFalse(DialogCommands.isTarget(bad, NOBODY), bad);
        }
    }

    @Test
    void anOddPrefixIsATargetOnlyWhenThatPlayerIsOnline() {
        Predicate<String> online = Set.of("*Bedrock")::contains;
        assertTrue(DialogCommands.isTarget("*Bedrock", online));
        assertFalse(DialogCommands.isTarget("*Bedrock", NOBODY));
        // Even a player somehow called "*" is not let through: the console reads it as everybody.
        assertFalse(DialogCommands.isTarget("*", name -> true));
        assertFalse(DialogCommands.isTarget("**", name -> true));
    }

    @Test
    void tooLongIsNot() {
        assertFalse(DialogCommands.isTarget("a".repeat(33), NOBODY));
    }
}
