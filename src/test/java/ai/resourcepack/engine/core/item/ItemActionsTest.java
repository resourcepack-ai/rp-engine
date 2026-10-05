package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemAction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading what an item does.
 *
 * <p>Every wrong shape here is one an author cannot see the effect of: a
 * mistyped verb, a missing dash, a number that is not one. In game all three
 * look identical — nothing happens — so they are load errors that name the
 * line, which is the whole reason the parsing is separate from the running.
 */
class ItemActionsTest {

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final ContentId id = ContentId.of("mypack", "wand").orElseThrow();

    /** {@code actions: { <trigger>: [ {verb: argument}, ... ] }} */
    private DefinitionNode item(String trigger, List<Map<String, Object>> steps) {
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put(trigger, steps);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("material", "STICK");
        body.put("actions", actions);
        return DefinitionNode.of(body);
    }

    private static Map<String, Object> step(String verb, Object argument) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put(verb, argument);
        return one;
    }

    private Map<ItemAction.Trigger, List<ItemAction>> parse(DefinitionNode body) {
        return ItemActions.parse(body, id, "mypack/items", diagnostics);
    }

    private boolean complained(String about) {
        return diagnostics.stream().anyMatch(d -> d.message().contains(about));
    }

    // ---- the ordinary case ---------------------------------------------

    @Test
    void anItemUsuallyDoesNothing() {
        assertTrue(parse(DefinitionNode.of(Map.of("material", "STICK"))).isEmpty());
        assertTrue(diagnostics.isEmpty(), "and that is not worth a word about");
    }

    @Test
    void stepsKeepTheOrderTheyWereWrittenIn() {
        // Load-bearing: a cooldown before a message and a cooldown after it
        // are different items, and a map would decide the order by hash.
        Map<ItemAction.Trigger, List<ItemAction>> actions = parse(item("right_click", List.of(
                step("message", "&bWhoosh."),
                step("cooldown", 5),
                step("console", "effect give {player} levitation 3"))));

        List<ItemAction> steps = actions.get(ItemAction.Trigger.RIGHT_CLICK);
        assertEquals(3, steps.size());
        assertEquals(ItemAction.Kind.MESSAGE, steps.get(0).kind());
        assertEquals("&bWhoosh.", steps.get(0).argument());
        assertEquals(ItemAction.Kind.COOLDOWN, steps.get(1).kind());
        assertEquals(5d, steps.get(1).number().orElseThrow(), 0.0001);
        assertEquals(ItemAction.Kind.CONSOLE, steps.get(2).kind());
    }

    @Test
    void everyTriggerIsWritableWithAnUnderscoreOrADash() {
        assertEquals(ItemAction.Trigger.RIGHT_CLICK, ItemAction.Trigger.parse("right-click").orElseThrow());
        assertEquals(ItemAction.Trigger.RIGHT_CLICK, ItemAction.Trigger.parse("RIGHT_CLICK").orElseThrow());
        assertEquals(ItemAction.Trigger.CONSUME, ItemAction.Trigger.parse(" consume ").orElseThrow());
        assertTrue(ItemAction.Trigger.parse("sneeze").isEmpty());
        assertTrue(ItemAction.Trigger.parse(null).isEmpty());
    }

    // ---- what an author gets wrong --------------------------------------

    @Test
    void aMistypedTriggerIsNamed() {
        assertTrue(parse(item("rightclick", List.of(step("message", "hi")))).isEmpty());
        assertTrue(complained("rightclick"), "the error names what they wrote");
        assertTrue(complained("right_click"), "and what they could have written");
    }

    @Test
    void aMistypedVerbIsNamed() {
        parse(item("right_click", List.of(step("mesage", "hi"))));
        assertTrue(complained("mesage"));
        assertTrue(complained("message"));
    }

    @Test
    void aMissingDashIsCaughtRatherThanSilentlyLosingTheStep() {
        // The nastiest one. Without the dash the second verb joins the first
        // step's map, YAML is perfectly happy, and one of the two actions
        // simply never happens with nothing anywhere to say why.
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("message", "hi");
        both.put("console", "say hello");

        parse(item("right_click", List.of(both)));
        assertTrue(complained("2 keys"));
        assertTrue(complained("message"));
        assertTrue(complained("console"));
    }

    @Test
    void aTriggerThatIsNotAListSaysSo() {
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("right_click", "message: hi");
        parse(DefinitionNode.of(Map.of("material", "STICK", "actions", actions)));

        assertTrue(complained("list of steps"));
    }

    // ---- the shapes a step's argument has to have ------------------------

    @Test
    void aNumberThatIsNotOneIsAWarningRatherThanASilentZero() {
        Map<ItemAction.Trigger, List<ItemAction>> actions =
                parse(item("right_click", List.of(step("cooldown", "soon"))));
        ItemActions.validate(actions, id, "mypack/items", diagnostics);

        assertTrue(complained("is not a number"));
    }

    @Test
    void giveHasToNameAnId() {
        Map<ItemAction.Trigger, List<ItemAction>> actions =
                parse(item("right_click", List.of(step("give", "ruby"))));
        ItemActions.validate(actions, id, "mypack/items", diagnostics);

        assertTrue(complained("namespace:id"));
    }

    @Test
    void anEffectNeedsADuration() {
        Map<ItemAction.Trigger, List<ItemAction>> actions =
                parse(item("right_click", List.of(step("effect", "SPEED"))));
        ItemActions.validate(actions, id, "mypack/items", diagnostics);

        assertTrue(complained("needs a type and a duration"));
    }

    @Test
    void aVerbWithNothingAfterItIsAWarning() {
        Map<ItemAction.Trigger, List<ItemAction>> actions =
                parse(item("right_click", List.of(step("message", ""))));
        ItemActions.validate(actions, id, "mypack/items", diagnostics);

        assertTrue(complained("has nothing after it"));
    }

    @Test
    void aWellFormedItemProducesNoDiagnosticsAtAll() {
        Map<ItemAction.Trigger, List<ItemAction>> actions = parse(item("attack", List.of(
                step("permission", "mypack.wand"),
                step("cooldown", 2),
                step("effect", "SPEED 10 2"),
                step("give", "mypack:ruby 3"),
                step("sound", "mypack:chime 1 1.2"),
                step("take", 1),
                step("cancel", true))));
        ItemActions.validate(actions, id, "mypack/items", diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> "unexpected: " + diagnostics);
        assertEquals(7, actions.get(ItemAction.Trigger.ATTACK).size());
    }

    @Test
    void anArgumentIsSplitTheWayTheStepsThatTakeSeveralNeed() {
        ItemAction effect = ItemAction.of(ItemAction.Kind.EFFECT, "  SPEED 10 2  ");

        assertEquals("SPEED 10 2", effect.argument(), "trimmed, and otherwise untouched");
        assertEquals(3, effect.words().length);
        assertEquals("SPEED", effect.words()[0]);
        assertEquals(0, ItemAction.of(ItemAction.Kind.CANCEL, null).words().length);
        assertFalse(ItemAction.of(ItemAction.Kind.COOLDOWN, "").number().isPresent());
    }
}
