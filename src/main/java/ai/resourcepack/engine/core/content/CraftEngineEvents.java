package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.resourcepack.engine.core.content.CraftEngine.get;
import static ai.resourcepack.engine.core.content.CraftEngine.list;
import static ai.resourcepack.engine.core.content.CraftEngine.map;
import static ai.resourcepack.engine.core.content.CraftEngine.number;
import static ai.resourcepack.engine.core.content.CraftEngine.string;
import static ai.resourcepack.engine.core.content.CraftEngine.strings;
import static ai.resourcepack.engine.core.content.CraftEngine.truthy;

/**
 * CraftEngine's {@code events}, as RP Engine actions.
 *
 * <p>Read as CraftEngine's {@code CommonFunctions.parseEvents} reads them, in
 * both of its shapes: a map of trigger to a list of functions, or a list of
 * {@code {on: trigger, conditions, functions}} entries (where {@code on} may
 * itself be a list, and a {@code type} beside it makes the entry one
 * function). The trigger names and aliases are its {@code EventTrigger}'s,
 * with {@code break} meaning the item's, the block's or the furniture's own
 * breaking depending on whose events they are, as its three managers say.
 *
 * <p>Functions with a step here: {@code command} ({@code as_player} is
 * {@code run}, otherwise {@code console}), {@code message} ({@code overlay} is
 * the action bar), {@code actionbar}, {@code play_sound},
 * {@code potion_effect}, {@code cancel_event}, {@code set_count} taking from
 * the stack, and {@code run} with no delay, whose functions are inlined. A
 * {@code permission} condition becomes a {@code permission} step; any other
 * condition is a branch, which actions deliberately do not have, so what sits
 * behind it is skipped and named.
 */
final class CraftEngineEvents {

    private CraftEngineEvents() {
    }

    /** Whose events these are, which decides what {@code break} and a click mean. */
    enum Owner {
        ITEM, BLOCK, FURNITURE
    }

    /**
     * Every event in {@code declared}, into {@code out}'s {@code actions:}.
     *
     * @param stands for an item, whether it places a block or furniture, which
     *               is what its {@code place} event needs
     */
    static void translate(Object declared, Owner owner, boolean stands, String id, String origin,
                          List<Diagnostic> diagnostics, Map<String, Object> out) {
        if (declared == null) {
            return;
        }
        ImportedActions actions = new ImportedActions();
        Set<String> noTrigger = new LinkedHashSet<>();
        Set<String> noStep = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        Map<String, Object> byTrigger = map(declared);
        if (byTrigger != null) {
            for (Map.Entry<String, Object> event : byTrigger.entrySet()) {
                event(List.of(event.getKey()), List.of(), list(event.getValue()), owner, stands, actions,
                        noTrigger, noStep, notes);
            }
        } else {
            for (Object raw : list(declared)) {
                Map<String, Object> entry = map(raw);
                if (entry == null) continue;
                // A YAML 1.1 reader takes an unquoted on: for the boolean true,
                // which is what ours is; CraftEngine's reader does not.
                List<String> on = strings(entry.containsKey("on") ? entry.get("on") : entry.get("true"));
                List<Object> functions = entry.containsKey("type") ? List.of(entry) : list(entry.get("functions"));
                List<Object> conditions = entry.containsKey("type") ? List.of() : list(get(entry, "condition", "conditions"));
                event(on, conditions, functions, owner, stands, actions, noTrigger, noStep, notes);
            }
        }

        actions.into(out);
        String whose = owner == Owner.ITEM ? "" : owner.name().toLowerCase(Locale.ROOT) + " ";
        if (!noTrigger.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the " + whose + "events " + String.join(", ", noTrigger) + " have no RP Engine trigger and "
                            + "were skipped."));
        }
        if (!noStep.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the functions " + String.join(", ", noStep) + " have no RP Engine step and were skipped; the "
                            + "rest of each event still runs."));
        }
        for (String note : notes) {
            diagnostics.add(Diagnostic.warning(origin, id, note + "."));
        }
    }

    /** One event: its triggers, the conditions on all of it, and its functions. */
    private static void event(List<String> on, List<Object> conditions, List<Object> functions, Owner owner,
                              boolean stands, ImportedActions actions, Set<String> noTrigger, Set<String> noStep,
                              List<String> notes) {
        String gate = permission(conditions, "an event", notes);
        if (gate == BLOCKED) return;
        for (String written : on) {
            String trigger = trigger(written, owner, stands);
            if (trigger == null) {
                noTrigger.add(written);
                continue;
            }
            List<Map<String, Object>> plain = new ArrayList<>();
            List<Map<String, Object>> gated = new ArrayList<>();
            String inner = steps(functions, plain, gated, noStep, notes);
            if (gate != null) {
                plain.addAll(gated);
                if (inner != null && !inner.equals(gate)) {
                    notes.add("functions behind the permission " + inner + " inside an event already behind "
                            + gate + " were skipped");
                    plain.removeAll(gated);
                }
                if (!actions.addGated(trigger, gate, plain)) {
                    notes.add("a " + written + " event behind the permission " + gate + " was skipped: another "
                            + "already asks for a different one, and a permission step stops everything after it");
                }
            } else {
                actions.addAll(trigger, plain);
                if (!actions.addGated(trigger, inner, gated)) {
                    notes.add("functions behind the permission " + inner + " were skipped: another on " + written
                            + " already asks for a different one");
                }
            }
        }
    }

    /** Sentinel for "a condition that is not a permission". */
    private static final String BLOCKED = "\u0000blocked";

    /** The single permission a list of conditions checks, null for none, or {@link #BLOCKED}. */
    private static String permission(List<Object> conditions, String what, List<String> notes) {
        String found = null;
        for (Object raw : conditions) {
            Map<String, Object> condition = map(raw);
            String type = condition == null ? String.valueOf(raw) : CraftEngineItems.type(condition.get("type"));
            String permission = condition == null ? null : string(condition.get("permission"));
            if (!type.equals("permission") || permission == null || (found != null && !found.equals(permission))) {
                notes.add(what + " behind the condition " + type + " was skipped: actions here have no branches, "
                        + "and a permission is the only check they can make");
                return BLOCKED;
            }
            found = permission;
        }
        return found;
    }

    /**
     * Functions as steps, the ungated into {@code plain} and those behind one
     * permission into {@code gated}.
     *
     * @return the permission {@code gated} sits behind, or null
     */
    private static String steps(List<Object> functions, List<Map<String, Object>> plain,
                                List<Map<String, Object>> gated, Set<String> noStep, List<String> notes) {
        String gate = null;
        for (Object raw : functions) {
            Map<String, Object> function = map(raw);
            if (function == null) continue;
            String type = CraftEngineItems.type(function.get("type"));
            String permission = permission(list(get(function, "condition", "conditions")), "the " + type
                    + " function", notes);
            if (permission == BLOCKED) continue;
            if (function.get("target") != null && !"self".equals(string(function.get("target")))) {
                notes.add("the " + type + " function aims at other players, and an RP Engine step acts on whoever "
                        + "set it off, so it was skipped");
                continue;
            }
            List<Map<String, Object>> into = plain;
            if (permission != null) {
                if (gate != null && !gate.equals(permission)) {
                    notes.add("the " + type + " function behind the permission " + permission + " was skipped: "
                            + "another function in the event asks for " + gate);
                    continue;
                }
                gate = permission;
                into = gated;
            }
            if (type.equals("run")) {
                if (number(function.get("delay")) != null && number(function.get("delay")) > 0) {
                    notes.add("a run function with a delay was skipped: an RP Engine step runs when its trigger "
                            + "does");
                    continue;
                }
                List<Map<String, Object>> innerPlain = new ArrayList<>();
                List<Map<String, Object>> innerGated = new ArrayList<>();
                String inner = steps(list(function.get("functions")), innerPlain, innerGated, noStep, notes);
                into.addAll(innerPlain);
                if (inner != null) {
                    String outer = into == gated ? permission : gate;
                    if (outer == null || outer.equals(inner)) {
                        gate = inner;
                        gated.addAll(innerGated);
                    } else {
                        notes.add("functions behind the permission " + inner + " inside a run behind " + outer
                                + " were skipped");
                    }
                }
                continue;
            }
            if (!function(type, function, into, notes)) {
                noStep.add(type);
            }
        }
        return gate;
    }

    /** One function. False when it has no step here. */
    private static boolean function(String type, Map<String, Object> function, List<Map<String, Object>> out,
                                    List<String> notes) {
        switch (type) {
            case "command": {
                if (truthy(function.get("as_op"))) {
                    notes.add("an as_op command was skipped: running a command as an opped player is not a step "
                            + "here, so write it as a console command");
                    return true;
                }
                String verb = truthy(function.get("as_player")) || truthy(function.get("as_event")) ? "run" : "console";
                for (String command : strings(get(function, "command", "commands"))) {
                    String line = ImportedActions.placeholders(command).trim();
                    while (line.startsWith("/")) line = line.substring(1);
                    if (line.contains("<arg:")) {
                        notes.add("the command " + command + " uses a CraftEngine argument RP Engine does not fill, "
                                + "so it was skipped");
                        continue;
                    }
                    out.add(ImportedActions.step(verb, line));
                }
                return true;
            }
            case "message": {
                String verb = truthy(function.get("overlay")) ? "actionbar" : "message";
                for (String message : strings(get(function, "message", "messages"))) {
                    out.add(ImportedActions.step(verb, ImportedActions.text(message)));
                }
                return true;
            }
            case "actionbar": {
                String text = string(get(function, "actionbar", "message"));
                if (text == null) return false;
                out.add(ImportedActions.step("actionbar", ImportedActions.text(text)));
                return true;
            }
            case "play_sound": {
                String sound = string(function.get("sound"));
                if (sound == null) return false;
                out.add(ImportedActions.step("sound", ImportedActions.sound(sound,
                        number(function.get("volume")), number(function.get("pitch")))));
                return true;
            }
            case "potion_effect": {
                String effect = string(function.get("potion_effect"));
                if (effect == null) return false;
                Double duration = number(function.get("duration"));
                Double amplifier = number(function.get("amplifier"));
                out.add(ImportedActions.step("effect", ImportedActions.effect(effect,
                        duration == null ? 20 : duration, amplifier == null ? 0 : amplifier.intValue())));
                return true;
            }
            case "cancel_event":
                out.add(ImportedActions.step("cancel", ""));
                return true;
            case "set_count": {
                Double count = number(get(function, "count", "amount"));
                if (truthy(function.get("add")) && count != null && count < 0) {
                    out.add(ImportedActions.step("take", (int) -count.doubleValue()));
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    /** Their trigger name, or one of its aliases, as ours for whoever owns the event. */
    private static String trigger(String written, Owner owner, boolean stands) {
        String name = written.trim().toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        boolean rightClick = name.equals("right_click") || name.equals("use_on") || name.equals("use")
                || name.equals("use_item_on");
        boolean place = name.equals("place") || name.equals("build");
        switch (owner) {
            case BLOCK:
                if (rightClick) return "interact";
                if (place) return "place";
                if (name.equals("break") || name.equals("block_break") || name.equals("dig")) return "remove";
                return null;
            case FURNITURE:
                if (rightClick) return "interact";
                if (place) return "place";
                if (name.equals("break") || name.equals("furniture_break")) return "remove";
                return null;
            default:
                if (rightClick) return "right_click";
                if (place) return stands ? "place" : null;
                switch (name) {
                    case "left_click": return "left_click";
                    case "attack":
                    case "hit": return "attack";
                    case "consume":
                    case "eat":
                    case "drink": return "consume";
                    case "block_break":
                    case "dig": return "block_break";
                    case "item_break":
                    case "break": return "break";
                    case "pick_up":
                    case "pick": return "pickup";
                    case "shoot": return "shoot";
                    default: return null;
                }
        }
    }
}
