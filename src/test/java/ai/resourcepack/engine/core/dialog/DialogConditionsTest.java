package ai.resourcepack.engine.core.dialog;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pieces of a dialog only some players see: the conditions, and what is kept
 * of a dialog for whom. Studio's verify:dialogs runs the same rules in
 * JavaScript over the bodies it builds.
 */
class DialogConditionsTest {

    private static Function<String, Optional<String>> values(Map<String, String> map) {
        return name -> Optional.ofNullable(map.get(name));
    }

    private static Predicate<String> perms(String... held) {
        Set<String> set = Set.of(held);
        return set::contains;
    }

    private static boolean test(String expression, Map<String, String> values, String... held) {
        return DialogConditions.test(expression, values(values), perms(held));
    }

    @Test
    void comparesValuesAsNumbersOrAsWords() {
        assertTrue(test("{vault_number} == \"2\"", Map.of("vault_number", "2")));
        assertFalse(test("{vault_number} == \"2\"", Map.of("vault_number", "1")));
        assertTrue(test("{vault_number} == 2.0", Map.of("vault_number", "2")));
        assertTrue(test("{rank} == \"Gold Rank\"", Map.of("rank", "gold rank")));
        assertTrue(test("{level} >= 10 && {level} < 20", Map.of("level", "12")));
        assertFalse(test("{level} > 9", Map.of("level", "9")));
        assertTrue(test("{rank} != \"admin\"", Map.of("rank", "member")));
    }

    @Test
    void aValueIsTrueUnlessItSaysOtherwise() {
        assertTrue(test("{muted}", Map.of("muted", "yes")));
        assertFalse(test("{muted}", Map.of("muted", "off")));
        assertFalse(test("{muted}", Map.of("muted", "0")));
        assertFalse(test("{muted}", Map.of()));
        assertTrue(test("!{muted}", Map.of()));
    }

    @Test
    void permissionsAndGrouping() {
        assertTrue(test("perm:vaults.vip", Map.of(), "vaults.vip"));
        assertFalse(test("perm:vaults.vip", Map.of()));
        assertTrue(test("!perm:vaults.vip", Map.of()));
        assertTrue(test("{vault_number} == \"2\" && perm:vaults.vip", Map.of("vault_number", "2"), "vaults.vip"));
        assertFalse(test("{vault_number} == \"2\" && perm:vaults.vip", Map.of("vault_number", "2")));
        assertTrue(test("({a} == \"1\" || {b} == \"1\") && !perm:x", Map.of("b", "1")));
        assertFalse(test("!({a} == \"1\" || {b} == \"1\")", Map.of("a", "1")));
    }

    @Test
    void aChoiceIsTheSettingOrItsDefault() {
        // How Studio writes a variable a control in the dialog sets: what the
        // player chose, or what the control shows when they have chosen nothing.
        String vault = "{vault_number?1:1|3:3|2:2} == \"2\"";
        assertTrue(test(vault, Map.of()), "the control's default when the player has none");
        assertFalse(test(vault, Map.of("vault_number", "1")));
        assertTrue(test(vault, Map.of("vault_number", "2")));
        // A list's row, shown when it has anything in it.
        assertTrue(test("{warps_3?*:1|-:0}", Map.of("warps_3", "Spawn")));
        assertFalse(test("{warps_3?*:1|-:0}", Map.of("warps_3", "")));
        assertFalse(test("{warps_3?*:1|-:0}", Map.of()));
    }

    @Test
    void whatCannotBeReadIsFalse() {
        assertFalse(test("{a} ==", Map.of("a", "1")));
        assertFalse(test("({a}", Map.of("a", "1")));
        assertFalse(test("{a} {b}", Map.of("a", "1", "b", "1")));
        assertTrue(test("", Map.of()));
    }

    @Test
    void aValueCannotWriteACondition() {
        // A value is compared, never read again as a condition or a placeholder.
        assertFalse(test("{name} == \"x\"", Map.of("name", "x\" || \"1\" == \"1")));
        assertFalse(test("{name}", Map.of("name", "{other}")));
    }

    @Test
    void stripKeepsWhatHoldsAndEmptiesTheRest() {
        String json = "{\"body\":{\"text\":\"\",\"extra\":["
                + "{\"text\":\"A\"},"
                + "{\"text\":\"\",\"insertion\":\"rp:if:perm:vaults.vip\",\"extra\":[{\"text\":\"V\",\"insertion\":\"rp:item:inv/1\"}]},"
                + "{\"text\":\"\",\"insertion\":\"rp:if:!perm:vaults.vip\",\"extra\":[{\"text\":\"N\"}]},"
                + "{\"text\":\" \",\"click_event\":{\"action\":\"run_command\",\"command\":\"rp slot player.vault2/1\"}}]}}";
        String vip = DialogConditions.strip(json, values(Map.of()), perms("vaults.vip"));
        assertTrue(vip.contains("\"V\"") && vip.contains("rp:item:inv/1"), vip);
        assertFalse(vip.contains("\"N\""), vip);
        assertFalse(vip.contains("rp:if:"), "a kept piece's condition is only an address: " + vip);
        String not = DialogConditions.strip(json, values(Map.of()), perms());
        assertFalse(not.contains("\"V\"") || not.contains("rp:item:"), not);
        assertTrue(not.contains("\"N\""), not);
        // Emptied, not taken out: the array keeps its length and nothing is left an empty `extra`.
        assertTrue(not.contains("{\"text\":\"\"}"), not);
        assertTrue(not.contains("\"A\""), not);
    }

    @Test
    void aClickInAHiddenPieceIsNotThere() {
        // A stretch of line two vaults could own: each candidate, stepped back
        // over, then the stretch. Only the vault the player sees keeps its click.
        String run = "{\"text\":\"\",\"insertion\":\"rp:if:{v?2:2|1:1} == \\\"%s\\\"\",\"extra\":["
                + "{\"text\":\"  \",\"click_event\":{\"action\":\"run_command\",\"command\":\"rp slot player.vault%s/1\"}},"
                + "{\"text\":\"<\"}]}";
        String json = "{\"body\":{\"text\":\"\",\"extra\":[" + String.format(run, 1, 1) + "," + String.format(run, 2, 2) + ",{\"text\":\"  \"}]}}";
        String one = DialogConditions.strip(json, values(Map.of()), perms());
        assertTrue(DialogLinks.holdsCommand(one, "rp slot player.vault1/1"), one);
        assertFalse(DialogLinks.holdsCommand(one, "rp slot player.vault2/1"), one);
        String two = DialogConditions.strip(json, values(Map.of("v", "2")), perms());
        assertFalse(DialogLinks.holdsCommand(two, "rp slot player.vault1/1"), two);
        assertTrue(DialogLinks.holdsCommand(two, "rp slot player.vault2/1"), two);
    }

    @Test
    void aDialogWithNoneIsLeftAsItIs() {
        String json = "{\"body\":{\"text\":\"hi {player}\"}}";
        assertEquals(json, DialogConditions.strip(json, values(Map.of()), perms()));
        assertFalse(DialogConditions.any(json));
    }
}
