package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemAction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading an item's {@code actions:} block.
 *
 * <p>Pure, and separated from running them for the usual reason: this is the
 * half that can be wrong in a way somebody has to be told about at load, and
 * the half that can be tested without a server.
 *
 * <pre>
 * wand:
 *   material: STICK
 *   actions:
 *     right_click:
 *       - cooldown: 5
 *       - message: "&amp;bWhoosh."
 *       - sound: mypack:chime
 *       - console: "effect give {player} levitation 3"
 * </pre>
 *
 * <p>A step is a one-key map, so the list keeps its order and reads as a
 * sequence of instructions. An unknown verb is a load error naming the verb
 * rather than a step that silently does nothing, because a typo in an action
 * is otherwise indistinguishable from the whole feature not working.
 */
public final class ItemActions {

    private ItemActions() {
    }

    /** What an item does, by trigger. Empty for the overwhelming majority. */
    public static Map<ItemAction.Trigger, List<ItemAction>> parse(
            DefinitionNode body, ContentId id, String origin, List<Diagnostic> diagnostics) {
        Map<ItemAction.Trigger, List<ItemAction>> out = new EnumMap<>(ItemAction.Trigger.class);
        DefinitionNode actions = body.node("actions").orElse(null);
        if (actions == null) {
            return Map.of();
        }
        String where = id.path();

        for (String written : actions.keys()) {
            ItemAction.Trigger trigger = ItemAction.Trigger.parse(written).orElse(null);
            if (trigger == null) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "actions: " + written + " is not something an item can do. One of: "
                                + triggerNames() + "."));
                continue;
            }
            List<ItemAction> steps = steps(actions, written, origin, where, diagnostics);
            if (!steps.isEmpty()) {
                out.put(trigger, List.copyOf(steps));
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static List<ItemAction> steps(DefinitionNode actions, String trigger, String origin,
                                          String where, List<Diagnostic> diagnostics) {
        List<ItemAction> steps = new ArrayList<>();
        List<DefinitionNode> written = actions.nodes(trigger);
        if (written.isEmpty() && actions.raw(trigger) != null) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "actions." + trigger + " is a list of steps, each one key: "
                            + "- message: \"hello\"."));
            return steps;
        }

        for (DefinitionNode step : written) {
            if (step.keys().size() != 1) {
                // Two keys in one entry is a missing dash, and YAML gives no
                // hint of that at all — the second key silently joins the
                // first step's map and is never run.
                diagnostics.add(Diagnostic.error(origin, where,
                        "actions." + trigger + " has a step with " + step.keys().size()
                                + " keys (" + String.join(", ", step.keys())
                                + "). Each step is its own list entry, with one key."));
                continue;
            }
            String verb = step.keys().iterator().next();
            ItemAction.Kind kind = ItemAction.Kind.parse(verb).orElse(null);
            if (kind == null) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "actions." + trigger + ": " + verb + " is not something this can do. One of: "
                                + kindNames() + "."));
                continue;
            }
            steps.add(ItemAction.of(kind, step.string(verb).orElse("")));
        }
        return steps;
    }

    /**
     * Whether a step is well-formed enough to be worth running.
     *
     * <p>Checked at load so a bad number is one line on a console at startup
     * rather than nothing happening in game with no explanation anywhere.
     */
    public static void validate(Map<ItemAction.Trigger, List<ItemAction>> actions, ContentId id,
                                String origin, List<Diagnostic> diagnostics) {
        String where = id.path();
        for (Map.Entry<ItemAction.Trigger, List<ItemAction>> entry : actions.entrySet()) {
            for (ItemAction step : entry.getValue()) {
                String problem = problemWith(step);
                if (problem != null) {
                    diagnostics.add(Diagnostic.warning(origin, where,
                            "actions." + entry.getKey().written() + ": " + problem));
                }
            }
        }
    }

    private static String problemWith(ItemAction step) {
        switch (step.kind()) {
            case COOLDOWN:
            case TAKE:
                return step.number().isPresent() ? null
                        : step.kind().name().toLowerCase(Locale.ROOT) + ": "
                                + step.argument() + " is not a number.";
            case GIVE:
                String[] words = step.words();
                if (words.length == 0 || ContentId.parse(words[0]).isEmpty()) {
                    return "give: " + step.argument() + " is not a namespace:id.";
                }
                return null;
            case EFFECT:
                return step.words().length >= 2 ? null
                        : "effect: " + step.argument()
                                + " needs a type and a duration, like SPEED 10 1.";
            case MESSAGE:
            case BROADCAST:
            case ACTIONBAR:
            case CONSOLE:
            case RUN:
            case PERMISSION:
                return step.argument().isEmpty()
                        ? step.kind().name().toLowerCase(Locale.ROOT) + " has nothing after it."
                        : null;
            default:
                return null;
        }
    }

    private static String triggerNames() {
        List<String> names = new ArrayList<>();
        for (ItemAction.Trigger trigger : ItemAction.Trigger.values()) {
            names.add(trigger.written());
        }
        return String.join(", ", names);
    }

    private static String kindNames() {
        List<String> names = new ArrayList<>();
        for (ItemAction.Kind kind : ItemAction.Kind.values()) {
            names.add(kind.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }
}
