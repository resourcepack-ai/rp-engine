package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Nexo and Oraxen mechanics that are behaviour, as RP Engine actions and
 * the few item properties they amount to.
 *
 * <ul>
 *   <li>{@code commands}: console and player commands on a click, with its
 *       cooldown, permission and {@code one_usage}. Both plugins fire it on
 *       either click, so it is both of ours, or on eating for food.</li>
 *   <li>{@code custom}: their own event-and-action mechanic, for the events
 *       that are a trigger here.</li>
 *   <li>{@code clickActions} on furniture and custom blocks (Nexo, and Oraxen
 *       before it moved them), and Oraxen's {@code events} that replaced them:
 *       a click on the piece where it stands, which is {@code interact}.</li>
 *   <li>Oraxen's legacy {@code food}, {@code consumable} and
 *       {@code consumable_potion_effects}, {@code soulbound} with no chance of
 *       loss, and {@code hat}.</li>
 * </ul>
 *
 * <p>A condition is an expression over the player in both plugins; the one
 * with a step here is a permission check, and anything else would be a branch,
 * so an action behind one is skipped with a warning naming the condition.
 */
final class NexoOraxenActions {

    private NexoOraxenActions() {
    }

    /** The item mechanics translated here, which the importer must not also warn about. */
    static final Set<String> ITEM_MECHANICS = Set.of("commands", "custom", "consumable", "food",
            "consumable_potion_effects", "soulbound", "hat");

    /**
     * Every item mechanic this class reads, applied to {@code out}.
     *
     * @param edible whether the item is eaten, which moves {@code commands} from
     *               the click to the meal, as both plugins do
     */
    static void item(DefinitionNode mechanics, String id, String namespace, boolean edible, String origin,
                     List<Diagnostic> diagnostics, Map<String, Object> out) {
        ImportedActions actions = new ImportedActions();
        List<String> notes = new ArrayList<>();

        mechanics.node("commands").ifPresent(commands -> commands(commands, edible, actions, notes));
        mechanics.node("custom").ifPresent(custom -> custom(custom, actions, notes));
        if (mechanics.node("consumable").isPresent() || mechanics.bool("consumable").orElse(Boolean.FALSE)) {
            // The legacy mechanic used one up on a right-click.
            actions.add("right_click", "take", 1);
        }
        mechanics.node("food").ifPresent(food -> food(food, namespace, actions, notes, out));
        mechanics.node("consumable_potion_effects").ifPresent(effects -> {
            for (String type : effects.keys()) {
                DefinitionNode effect = effects.node(type).orElse(DefinitionNode.empty());
                actions.add("consume", "effect", ImportedActions.effect(type,
                        effect.decimal("duration").orElse(600d), effect.integer("amplifier").orElse(0)));
            }
        });
        mechanics.node("soulbound").ifPresent(soulbound -> {
            if (soulbound.decimal("lose_chance").orElse(0d) <= 0) {
                out.put("keep-on-death", true);
            } else {
                notes.add("soulbound with a lose_chance: RP Engine's keep-on-death always keeps it, so it is "
                        + "kept every time");
                out.put("keep-on-death", true);
            }
        });
        if (mechanics.node("hat").isPresent() || mechanics.bool("hat").orElse(Boolean.FALSE)) {
            out.put("hat", true);
        }

        actions.into(out);
        for (String note : notes) {
            diagnostics.add(Diagnostic.warning(origin, id, note + "."));
        }
    }

    /**
     * {@code clickActions} and Oraxen's {@code events} on a furniture or block
     * mechanic, as the piece's own {@code interact} in {@code out}.
     */
    static void standing(DefinitionNode mechanic, String id, String origin, List<Diagnostic> diagnostics,
                         Map<String, Object> out) {
        ImportedActions actions = new ImportedActions();
        List<String> notes = new ArrayList<>();
        for (DefinitionNode entry : mechanic.nodes("clickActions")) {
            group(entry.strings("conditions"), legacy(entry.strings("actions"), notes), "clickActions", actions,
                    notes);
        }
        for (DefinitionNode event : mechanic.nodes("events")) {
            String click = event.string("click").orElse("BOTH").trim().toUpperCase(Locale.ROOT);
            if (click.equals("LEFT")) {
                notes.add("a left-click event: a left click on a placed piece breaks it here, so it was skipped");
                continue;
            }
            if (click.equals("BOTH")) {
                notes.add("a click: BOTH event runs on the right click only; a left click on a placed piece "
                        + "breaks it here");
            }
            for (DefinitionNode action : event.nodes("actions")) {
                List<Map<String, Object>> steps = new ArrayList<>();
                if (action.string("command").isPresent()) {
                    String executor = action.string("executor").orElse("player").trim().toUpperCase(Locale.ROOT)
                            .replace('-', '_');
                    String command = stripSlash(ImportedActions.placeholders(action.string("command").get()));
                    if (executor.equals("OP_PLAYER")) {
                        notes.add("an op_player command (" + command + ") was skipped: running a command as an "
                                + "opped player is not a step here, so write it as console or run");
                    } else {
                        steps.add(ImportedActions.step(executor.equals("CONSOLE") ? "console" : "run", command));
                    }
                } else if (action.string("message").isPresent()) {
                    steps.add(ImportedActions.step("message", ImportedActions.text(action.string("message").get())));
                } else if (action.raw("legacy") != null) {
                    steps.addAll(legacy(action.strings("legacy"), notes));
                }
                List<String> conditions = action.strings("conditions").isEmpty()
                        ? action.strings("condition") : action.strings("conditions");
                group(conditions, steps, "events", actions, notes);
            }
        }
        actions.into(out);
        for (String note : notes) {
            diagnostics.add(Diagnostic.warning(origin, id, note + "."));
        }
    }

    /** One group of steps behind its conditions, on the piece's {@code interact}. */
    private static void group(List<String> conditions, List<Map<String, Object>> steps, String where,
                              ImportedActions actions, List<String> notes) {
        String permission = null;
        for (String condition : conditions) {
            String checked = ImportedActions.permissionOf(condition);
            if (checked == null) {
                notes.add(where + " behind the condition " + condition + " was skipped: actions here have no "
                        + "branches, and a permission is the only check they can make");
                return;
            }
            if (permission != null && !permission.equals(checked)) {
                notes.add(where + " checking two permissions was skipped");
                return;
            }
            permission = checked;
        }
        if (!actions.addGated("interact", permission, steps)) {
            notes.add(where + " behind the permission " + permission + " was skipped: another of its click "
                    + "actions already asks for a different one, and a permission step stops everything after it");
        }
    }

    private static final Pattern META = Pattern.compile("^\\{([^}]*)}\\s*");
    private static final Pattern TAGGED = Pattern.compile("^\\[([a-zA-Z_]+)]\\s*(.*)$", Pattern.DOTALL);

    /**
     * Their action strings: {@code [console] say hi}, {@code [player] spawn},
     * {@code [message] <red>hi}, {@code [actionbar] ...} and
     * {@code {volume=0.5 pitch=1} [sound] minecraft:block.bell.use}.
     */
    static List<Map<String, Object>> legacy(List<String> written, List<String> notes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : written) {
            String rest = line.trim();
            Map<String, String> meta = new LinkedHashMap<>();
            Matcher prefix = META.matcher(rest);
            if (prefix.find()) {
                for (String pair : prefix.group(1).trim().split("\\s+")) {
                    int equals = pair.indexOf('=');
                    if (equals > 0) meta.put(pair.substring(0, equals).toLowerCase(Locale.ROOT),
                            pair.substring(equals + 1));
                }
                rest = rest.substring(prefix.end());
            }
            Matcher tagged = TAGGED.matcher(rest);
            if (!tagged.matches()) {
                notes.add("the action " + line + " is not one RP Engine reads, so it was skipped");
                continue;
            }
            String verb = tagged.group(1).toLowerCase(Locale.ROOT);
            String argument = tagged.group(2).trim();
            switch (verb) {
                case "console":
                    out.add(ImportedActions.step("console", stripSlash(ImportedActions.placeholders(argument))));
                    break;
                case "player":
                    out.add(ImportedActions.step("run", stripSlash(ImportedActions.placeholders(argument))));
                    break;
                case "message":
                case "actionbar":
                    out.add(ImportedActions.step(verb, ImportedActions.text(argument)));
                    break;
                case "sound":
                    out.add(ImportedActions.step("sound", ImportedActions.sound(argument,
                            number(meta.get("volume")), number(meta.get("pitch")))));
                    break;
                default:
                    notes.add("the [" + verb + "] action has no RP Engine step and was skipped");
            }
        }
        return out;
    }

    /** {@code commands}: console and player lists, a cooldown, a permission and one_usage. */
    private static void commands(DefinitionNode commands, boolean edible, ImportedActions actions,
                                 List<String> notes) {
        List<Map<String, Object>> steps = new ArrayList<>();
        cooldown(commands.string("cooldown").orElse(null)).ifPresent(seconds ->
                steps.add(ImportedActions.step("cooldown", seconds)));
        for (String command : commands.strings("console")) {
            steps.add(ImportedActions.step("console", stripSlash(ImportedActions.placeholders(command))));
        }
        for (String command : commands.strings("player")) {
            steps.add(ImportedActions.step("run", stripSlash(ImportedActions.placeholders(command))));
        }
        if (!commands.strings("opped_player").isEmpty()) {
            notes.add("commands.opped_player was skipped: running a command as an opped player is not a step "
                    + "here, so write those as console commands");
        }
        if (commands.bool("one_usage").orElse(Boolean.FALSE)) {
            steps.add(ImportedActions.step("take", 1));
        }
        String permission = commands.string("permission").orElse(null);
        if (edible) {
            actions.addGated("consume", permission, steps);
        } else {
            // Either click runs them in both plugins. Ours keeps one cooldown
            // per trigger, so the two clicks are timed apart.
            actions.addGated("right_click", permission, steps);
            actions.addGated("left_click", permission, steps);
        }
    }

    /**
     * {@code custom}: named subsections, each an event, its conditions and
     * actions, a cooldown in milliseconds and {@code one_usage}.
     */
    private static void custom(DefinitionNode custom, ImportedActions actions, List<String> notes) {
        for (String name : custom.keys()) {
            DefinitionNode one = custom.node(name).orElse(DefinitionNode.empty());
            String event = one.string("event").orElse("").trim().toUpperCase(Locale.ROOT);
            String[] parts = event.split(":");
            List<String> triggers = new ArrayList<>();
            switch (parts[0]) {
                case "CLICK": {
                    String button = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "all";
                    String target = parts.length > 2 ? parts[2].toLowerCase(Locale.ROOT) : "all";
                    if (!target.equals("all")) {
                        notes.add("custom." + name + " fires only on a click at " + target + "; RP Engine clicks "
                                + "are on anything, so it does too");
                    }
                    if (!button.equals("left")) triggers.add("right_click");
                    if (!button.equals("right")) triggers.add("left_click");
                    break;
                }
                case "DROP":
                    triggers.add("drop");
                    break;
                case "PICKUP":
                    triggers.add("pickup");
                    break;
                case "BREAK":
                    triggers.add("break");
                    break;
                default:
                    notes.add("custom." + name + "'s event " + (event.isEmpty() ? "(none)" : parts[0])
                            + " has no RP Engine trigger, so it was skipped");
                    continue;
            }
            List<Map<String, Object>> steps = new ArrayList<>();
            double cooldown = one.decimal("cooldown").orElse(0d) / 1000d;
            if (cooldown >= 0.05) {
                steps.add(ImportedActions.step("cooldown", round(cooldown)));
            }
            steps.addAll(legacy(one.strings("actions"), notes));
            if (one.bool("one_usage").orElse(Boolean.FALSE)) {
                steps.add(ImportedActions.step("take", 1));
            }
            String permission = null;
            boolean skip = false;
            for (String condition : one.strings("conditions")) {
                String checked = ImportedActions.permissionOf(condition);
                if (checked == null || (permission != null && !permission.equals(checked))) {
                    notes.add("custom." + name + " behind the condition " + condition + " was skipped: actions "
                            + "here have no branches, and a permission is the only check they can make");
                    skip = true;
                    break;
                }
                permission = checked;
            }
            if (skip) continue;
            for (String trigger : triggers) {
                if (!actions.addGated(trigger, permission, steps)) {
                    notes.add("custom." + name + " was skipped: another mechanic on " + trigger + " already asks "
                            + "for a different permission");
                }
            }
        }
    }

    /**
     * Oraxen's legacy {@code food}: hunger and saturation are the food
     * component, its effects run on eating (their duration is in seconds), and
     * a replacement of one of the pack's items is given back.
     */
    private static void food(DefinitionNode food, String namespace, ImportedActions actions, List<String> notes,
                             Map<String, Object> out) {
        if (!out.containsKey("food")) {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("nutrition", food.integer("hunger").orElse(1));
            component.put("saturation", food.decimal("saturation").orElse(1d));
            out.put("food", component);
        }
        DefinitionNode effects = food.node("effects").orElse(DefinitionNode.empty());
        if (!effects.keys().isEmpty() && food.decimal("effect_probability").orElse(1d) < 1d) {
            notes.add("food effects with an effect_probability below 1 were skipped: an action always runs");
        } else {
            for (String type : effects.keys()) {
                DefinitionNode effect = effects.node(type).orElse(DefinitionNode.empty());
                actions.add("consume", "effect", ImportedActions.effect(type,
                        effect.decimal("duration").orElse(1d) * 20, effect.integer("amplifier").orElse(0)));
            }
        }
        food.node("replacement").ifPresent(replacement -> {
            String item = replacement.string("oraxen_item").or(() -> replacement.string("nexo_item")).orElse(null);
            String vanilla = replacement.string("minecraft_type").orElse(null);
            if (item != null) {
                actions.add("consume", "give", item.contains(":") ? item : namespace + ":" + item);
            } else if (vanilla != null) {
                actions.add("consume", "console", "give {player} minecraft:" + vanilla.toLowerCase(Locale.ROOT));
            } else {
                notes.add("food.replacement names another plugin's item, which RP Engine cannot give");
            }
        });
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|t|s|m|h|d)");

    /**
     * A cooldown in seconds: Nexo's {@code 5s}/{@code 10t}/{@code 1m30s}, or a
     * bare number, which Oraxen counts in milliseconds.
     */
    static java.util.Optional<Double> cooldown(String written) {
        if (written == null || written.isBlank()) {
            return java.util.Optional.empty();
        }
        String text = written.replace(" ", "").toLowerCase(Locale.ROOT);
        double seconds = 0;
        Matcher matcher = DURATION.matcher(text);
        boolean any = false;
        while (matcher.find()) {
            any = true;
            double value = Double.parseDouble(matcher.group(1));
            switch (matcher.group(2)) {
                case "ms": seconds += value / 1000d; break;
                case "t": seconds += value / 20d; break;
                case "s": seconds += value; break;
                case "m": seconds += value * 60; break;
                case "h": seconds += value * 3600; break;
                default: seconds += value * 86400;
            }
        }
        if (!any) {
            Double bare = number(text);
            if (bare == null) return java.util.Optional.empty();
            seconds = bare / 1000d;
        }
        return seconds >= 0.05 ? java.util.Optional.of(round(seconds)) : java.util.Optional.empty();
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static Double number(String written) {
        if (written == null) return null;
        try {
            return Double.parseDouble(written.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripSlash(String command) {
        String out = command.trim();
        while (out.startsWith("/")) out = out.substring(1);
        return out;
    }
}
