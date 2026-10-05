package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An ItemsAdder item's {@code events:}, as RP Engine actions.
 *
 * <p>Their shape is an event holding a map of actions, each action a map of
 * its own settings:
 *
 * <pre>
 * events:
 *   interact:
 *     right:
 *       play_sound: { name: block.note_block.bell }
 *       execute_commands:
 *         greet: { command: "say hi {player}", as_console: true }
 *   placed_furniture:
 *     interact:
 *       execute_commands: { help: { command: help } }
 * </pre>
 *
 * <p>Ours is a trigger holding a list of one-key steps, so the translation is
 * a rename on both levels. {@code {player}} is the same placeholder in both,
 * which is a happy accident worth keeping intact.
 *
 * <p>What has no trigger here - holding, wearing, fishing, a sneaking click,
 * their guns and books - or no step - particles, damage, replacing blocks,
 * dropping experience - is named in one warning per item, and the rest of the
 * event still comes across. An action with a {@code delay} is skipped, since
 * a step here runs when the trigger does; one with its own {@code permission}
 * becomes a {@code permission} step when every action in the event asks for
 * the same one, and is skipped otherwise, because a permission step stops
 * everything after it rather than one action.
 */
final class ItemsAdderEvents {

    private ItemsAdderEvents() {
    }

    /** Their event names that are one of our triggers, as written. */
    private static final Map<String, String> ITEM_EVENTS = Map.of(
            "attack", "attack",
            "eat", "consume",
            "drink", "consume",
            "drop", "drop",
            "pickup", "pickup",
            "item_break", "break",
            "block_break", "block_break",
            "bow_shot", "shoot");

    /** Their sub-events under {@code placed_block} and {@code placed_furniture}. */
    private static final Map<String, String> PLACED_EVENTS = Map.of(
            "interact", "interact",
            "break", "remove",
            "place", "place");

    /**
     * Every event of one item.
     *
     * @param stands whether the item is a block or furniture, which is what
     *               {@code placed_block} and {@code placed_furniture} need
     */
    static ImportedActions translate(DefinitionNode events, String id, String namespace, boolean stands,
                                     String origin, List<Diagnostic> diagnostics) {
        ImportedActions out = new ImportedActions();
        Set<String> noTrigger = new LinkedHashSet<>();
        Set<String> noStep = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        for (String event : events.keys()) {
            DefinitionNode body = events.node(event).orElse(DefinitionNode.empty());
            switch (event) {
                case "interact":
                case "interact_mainhand":
                    clicks(body, event, out, id, namespace, noTrigger, noStep, notes);
                    break;
                case "placed_block":
                case "placed_furniture":
                    if (!stands) {
                        notes.add(event + " is on an item that is neither a block nor furniture, so it never fires");
                        break;
                    }
                    for (String sub : body.keys()) {
                        DefinitionNode actions = body.node(sub).orElse(DefinitionNode.empty());
                        String trigger = PLACED_EVENTS.get(sub);
                        if (trigger == null) {
                            noTrigger.add(event + "." + sub);
                        } else if (sub.equals("interact") && isClickMap(actions)) {
                            // Written the way the item's own interact is.
                            for (String side : actions.keys()) {
                                if (side.equals("right")) {
                                    out.addAll(trigger, steps(actions.node(side).orElse(DefinitionNode.empty()),
                                            event + ".interact.right", namespace, noStep, notes));
                                } else {
                                    noTrigger.add(event + ".interact." + side);
                                }
                            }
                        } else {
                            out.addAll(trigger, steps(actions, event + "." + sub, namespace, noStep, notes));
                        }
                    }
                    break;
                default:
                    String trigger = ITEM_EVENTS.get(event);
                    if (trigger == null) {
                        noTrigger.add(event);
                    } else {
                        out.addAll(trigger, steps(body, event, namespace, noStep, notes));
                    }
            }
        }

        if (!noTrigger.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the events " + String.join(", ", noTrigger) + " have no RP Engine trigger and were skipped."));
        }
        if (!noStep.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the actions " + String.join(", ", noStep) + " have no RP Engine step and were skipped; "
                            + "the rest of each event still runs."));
        }
        for (String note : notes) {
            diagnostics.add(Diagnostic.warning(origin, id, note + "."));
        }
        return out;
    }

    /** {@code interact.right}, {@code interact.left}, or the actions straight under {@code interact}. */
    private static void clicks(DefinitionNode body, String event, ImportedActions out, String id, String namespace,
                               Set<String> noTrigger, Set<String> noStep, List<String> notes) {
        if (!isClickMap(body)) {
            // A bare interact fires on either click, so it is both of ours.
            List<java.util.Map<String, Object>> steps = steps(body, event, namespace, noStep, notes);
            out.addAll("right_click", steps);
            out.addAll("left_click", steps);
            return;
        }
        for (String side : body.keys()) {
            DefinitionNode actions = body.node(side).orElse(DefinitionNode.empty());
            switch (side) {
                case "right":
                    out.addAll("right_click", steps(actions, event + ".right", namespace, noStep, notes));
                    break;
                case "left":
                    out.addAll("left_click", steps(actions, event + ".left", namespace, noStep, notes));
                    break;
                default:
                    // right_shift, left_shift, entity...: a click with a
                    // condition on it, and actions have no conditions.
                    noTrigger.add(event + "." + side);
            }
        }
    }

    /** Whether an interact block is split by click rather than holding actions itself. */
    private static boolean isClickMap(DefinitionNode body) {
        for (String key : body.keys()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.equals("right") || lower.equals("left") || lower.endsWith("_shift") || lower.startsWith("entity")) {
                return true;
            }
        }
        return false;
    }

    /** One event's actions, as our steps in the order they were written. */
    private static List<java.util.Map<String, Object>> steps(DefinitionNode actions, String where, String namespace,
                                                             Set<String> noStep, List<String> notes) {
        List<java.util.Map<String, Object>> out = new ArrayList<>();

        // A permission on every action alike is a gate on the whole event,
        // which is exactly what a permission step at the front is.
        String shared = null;
        boolean sharedByAll = !actions.keys().isEmpty();
        for (String action : actions.keys()) {
            String permission = actions.node(action).flatMap(a -> a.string("permission")).orElse(null);
            if (permission == null || permission.startsWith("!") || (shared != null && !shared.equals(permission))) {
                sharedByAll = false;
                break;
            }
            shared = permission;
        }
        if (sharedByAll && shared != null) {
            out.add(ImportedActions.step("permission", shared));
        }

        for (String action : actions.keys()) {
            DefinitionNode settings = actions.node(action).orElse(DefinitionNode.empty());
            if (settings.decimal("delay").orElse(0d) > 0) {
                notes.add(where + "." + action + " has a delay, and an RP Engine step runs when its trigger "
                        + "does, so it was skipped");
                continue;
            }
            if (settings.string("permission").isPresent() && !sharedByAll) {
                notes.add(where + "." + action + " asks for its own permission, and a permission step here stops "
                        + "everything after it, so it was skipped");
                continue;
            }
            if (!action(action, actions, settings, namespace, out)) {
                noStep.add(action);
            }
        }
        return out;
    }

    /** One action. False when it has no step here. */
    private static boolean action(String action, DefinitionNode actions, DefinitionNode settings, String namespace,
                                  List<java.util.Map<String, Object>> out) {
        switch (action) {
            case "play_sound": {
                String name = settings.string("name").orElse(null);
                if (name == null) return false;
                out.add(ImportedActions.step("sound", ImportedActions.sound(name,
                        settings.decimal("volume").orElse(null), settings.decimal("pitch").orElse(null))));
                return true;
            }
            case "execute_commands": {
                boolean any = false;
                for (String name : settings.keys()) {
                    DefinitionNode command = settings.node(name).orElse(DefinitionNode.empty());
                    String line = command.string("command").orElse(null);
                    if (line == null) continue;
                    line = line.startsWith("/") ? line.substring(1) : line;
                    out.add(ImportedActions.step(command.bool("as_console").orElse(Boolean.FALSE) ? "console" : "run",
                            line));
                    any = true;
                }
                return any;
            }
            case "potion_effect": {
                String type = settings.string("type").orElse(null);
                if (type == null) return false;
                out.add(ImportedActions.step("effect", ImportedActions.effect(type,
                        settings.decimal("duration").orElse(20d), settings.integer("amplifier").orElse(0))));
                return true;
            }
            case "give_item": {
                String item = settings.string("item").orElse(null);
                if (item == null) return false;
                int amount = Math.max(1, settings.integer("amount").orElse(1));
                if (item.startsWith("minecraft:") || !item.contains(":") && item.equals(item.toUpperCase(Locale.ROOT))) {
                    // A vanilla item is not a content id, so the game's own
                    // give hands it over.
                    String vanilla = item.toLowerCase(Locale.ROOT);
                    out.add(ImportedActions.step("console", "give {player} "
                            + (vanilla.contains(":") ? vanilla : "minecraft:" + vanilla) + " " + amount));
                } else {
                    out.add(ImportedActions.step("give",
                            (item.contains(":") ? item : namespace + ":" + item) + (amount > 1 ? " " + amount : "")));
                }
                return true;
            }
            case "replace_block":
                // A placed block's click-into:, written by the block translation.
                return true;
            case "decrement_amount":
                out.add(ImportedActions.step("take", settings.integer("amount").orElse(1)));
                return true;
            case "cancel":
                if (Boolean.FALSE.equals(actions.bool(action).orElse(Boolean.TRUE))) return true;
                out.add(ImportedActions.step("cancel", ""));
                return true;
            case "message":
            case "actionbar": {
                // ItemsAdderAdditions' two, written as text or as {text: ...}.
                String text = actions.string(action).or(() -> settings.string("text"))
                        .or(() -> settings.string("message")).orElse(null);
                if (text == null) return false;
                out.add(ImportedActions.step(action, ImportedActions.text(text)));
                return true;
            }
            default:
                return false;
        }
    }
}
