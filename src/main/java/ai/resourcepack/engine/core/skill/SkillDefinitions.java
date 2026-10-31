package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads skills: each mob's {@code skills:} (or MythicMobs' {@code Skills:})
 * list in {@code entities/}, and the metaskills in {@code skills/}, which use
 * MythicMobs' own file shape so a file copies across:
 *
 * <pre>
 * GroundSlam:
 *   Cooldown: 12
 *   Conditions: [ - stance{s=phase2} ]
 *   TargetConditions: [ - distance{d=&lt;6} ]
 *   Skills:
 *   - damage{a=10} @PIR{r=6}
 * </pre>
 *
 * <p><b>A bad line costs that line</b>: it is reported with its file and its
 * place ({@code golem.skills[3]}) and skipped, and the mob and the rest of its
 * skills load. That is the rule items' actions already follow.
 *
 * <p>Free of Bukkit; the names it checks against are handed in, so a test
 * gives it a fixed list and the server gives it the live registry.
 */
public final class SkillDefinitions {

    /** The most lines one mob, or one metaskill, keeps. Past it the rest are refused at load. */
    public static final int MAX_LINES = 64;

    /** The triggers, by the name after {@code ~on}, lowercased, with MythicMobs' aliases. */
    static final Map<String, String> TRIGGERS = triggers();

    private SkillDefinitions() {
    }

    /** What a line may name, so the load can warn about what will not run. */
    public interface Names {
        boolean mechanic(String name);

        boolean targeter(String name);

        boolean condition(String name);
    }

    private static Map<String, String> triggers() {
        Map<String, String> map = new HashMap<>();
        for (String name : List.of("spawn", "load", "spawnorload", "death", "attack", "damaged", "timer",
                "interact", "combat", "entercombat", "dropcombat", "changetarget", "playerkill", "kill")) {
            map.put(name, name);
        }
        map.put("hurt", "damaged");
        map.put("damage", "damaged");
        map.put("killplayer", "playerkill");
        map.put("target", "changetarget");
        map.put("ontarget", "changetarget");
        map.put("rightclick", "interact");
        map.put("use", "interact");
        map.put("killed", "death");
        map.put("tick", "timer");
        return Map.copyOf(map);
    }

    /** What loaded: each mob's lines and every metaskill, and the warnings. */
    public static final class Result {
        final Map<ContentId, MobSkillSet> mobs;
        final Map<ContentId, Metaskill> metaskills;
        final List<Diagnostic> diagnostics;

        Result(Map<ContentId, MobSkillSet> mobs, Map<ContentId, Metaskill> metaskills, List<Diagnostic> diagnostics) {
            this.mobs = Map.copyOf(mobs);
            this.metaskills = Map.copyOf(metaskills);
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        /** How many mobs have skills and how many metaskills there are, for the load report. */
        public String summary() {
            int lines = 0;
            for (MobSkillSet set : mobs.values()) {
                lines += set.lines.size();
            }
            return mobs.size() + " mob" + (mobs.size() == 1 ? "" : "s") + " with " + lines + " skill line"
                    + (lines == 1 ? "" : "s") + ", " + metaskills.size() + " metaskill"
                    + (metaskills.size() == 1 ? "" : "s");
        }
    }

    public static Result parse(LoadReport loaded, Names names) {
        Map<ContentId, MobSkillSet> mobs = new LinkedHashMap<>();
        Map<ContentId, Metaskill> metaskills = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(mobs, metaskills, diagnostics);
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.ENTITY)) {
            DefinitionNode body = definition.body();
            Object raw = body.has("skills") ? body.raw("skills") : body.raw("Skills");
            if (raw == null) {
                continue;
            }
            String where = definition.id().path() + ".skills";
            List<SkillLine> lines = lines(raw, definition.origin(), where, names, true, diagnostics);
            String stance = body.string("stance").orElse(null);
            if (!lines.isEmpty() || stance != null) {
                mobs.put(definition.id(), new MobSkillSet(lines, stance));
            }
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.SKILL)) {
            metaskill(definition.id(), definition.body().values(), definition.origin(), names, diagnostics)
                    .ifPresent(skill -> metaskills.put(skill.id, skill));
        }
        return new Result(mobs, metaskills, diagnostics);
    }

    /**
     * {@code authored} with the skills Studio pushed added: each pushed mob's
     * lines and starting stance, and its metaskills under {@code namespace}.
     * The same reader as the content folder's, so a pushed line that cannot
     * run is a diagnostic naming {@code origin} and skipped, exactly as an
     * authored one. A pushed mob replaces an authored one of the same id, as
     * the mobs themselves are merged.
     *
     * @param lines      mob id to its own lines
     * @param stances    mob id to the stance it starts in (absent for none)
     * @param metaskills bare name to a MythicMobs-shaped body
     *                   ({@code Cooldown}, {@code Conditions}, {@code Skills})
     */
    public static Result withPushed(Result authored, String namespace, Map<ContentId, List<String>> lines,
                                    Map<ContentId, String> stances, Map<String, Map<String, Object>> metaskills,
                                    String origin, Names names) {
        Map<ContentId, MobSkillSet> mobs = new LinkedHashMap<>(authored.mobs);
        Map<ContentId, Metaskill> skills = new LinkedHashMap<>(authored.metaskills);
        List<Diagnostic> diagnostics = new ArrayList<>(authored.diagnostics);
        java.util.Set<ContentId> ids = new java.util.LinkedHashSet<>(lines.keySet());
        ids.addAll(stances.keySet());
        for (ContentId id : ids) {
            List<String> written = lines.getOrDefault(id, List.of());
            List<SkillLine> parsed = SkillDefinitions.lines(new ArrayList<Object>(written), origin,
                    id.path() + ".skills", names, true, diagnostics);
            String stance = stances.get(id);
            mobs.remove(id);
            if (!parsed.isEmpty() || stance != null) {
                mobs.put(id, new MobSkillSet(parsed, stance));
            }
        }
        metaskills.forEach((name, body) -> ContentId.of(namespace, name.toLowerCase(Locale.ROOT))
                .flatMap(id -> metaskill(id, body, origin, names, diagnostics))
                .ifPresent(skill -> skills.put(skill.id, skill)));
        return new Result(mobs, skills, diagnostics);
    }

    /** One metaskill from its MythicMobs-shaped body; keys are case-insensitive. */
    static java.util.Optional<Metaskill> metaskill(ContentId id, Map<String, Object> body, String origin, Names names,
                                                   List<Diagnostic> diagnostics) {
        Map<String, Object> keys = new HashMap<>();
        body.forEach((key, value) -> keys.put(key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", ""), value));
        String where = id.path();
        Object skills = keys.get("skills");
        if (skills == null) {
            diagnostics.add(Diagnostic.warning(origin, where, "A metaskill with no Skills: list does nothing."));
            return java.util.Optional.empty();
        }
        List<SkillLine> lines = lines(skills, origin, where + ".Skills", names, false, diagnostics);
        double cooldown = 0;
        Object written = keys.get("cooldown");
        if (written != null) {
            try {
                cooldown = Math.max(0, Double.parseDouble(written.toString().trim()));
            } catch (NumberFormatException e) {
                diagnostics.add(Diagnostic.warning(origin, where, "Cooldown: " + written + " is not a number of seconds."));
            }
        }
        List<SkillLine.Cond> conditions = conditions(keys.get("conditions"), false, origin, where + ".Conditions",
                names, diagnostics);
        List<SkillLine.Cond> targetConditions = conditions(keys.get("targetconditions"), false, origin,
                where + ".TargetConditions", names, diagnostics);
        List<SkillLine.Cond> triggerConditions = conditions(keys.get("triggerconditions"), true, origin,
                where + ".TriggerConditions", names, diagnostics);
        Object onCooldown = keys.get("oncooldownskill");
        boolean cancelIfNone = Boolean.parseBoolean(String.valueOf(keys.getOrDefault("cancelifnotargets", "false")));
        return java.util.Optional.of(new Metaskill(id, cooldown, conditions, targetConditions, triggerConditions, lines,
                onCooldown == null ? null : onCooldown.toString().trim(), cancelIfNone, origin));
    }

    private static List<SkillLine.Cond> conditions(Object raw, boolean onTrigger, String origin, String where,
                                                   Names names, List<Diagnostic> diagnostics) {
        List<SkillLine.Cond> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        List<?> list = raw instanceof List ? (List<?>) raw : List.of(raw);
        for (int i = 0; i < list.size(); i++) {
            String text = String.valueOf(list.get(i));
            SkillParser.Parsed parsed = SkillParser.condition(text, onTrigger);
            if (!parsed.ok()) {
                diagnostics.add(Diagnostic.warning(origin, where + "[" + (i + 1) + "]", parsed.error + " Skipped: " + text));
                continue;
            }
            SkillLine.Cond cond = parsed.line.conditions.get(0);
            if (!names.condition(cond.ref.name)) {
                diagnostics.add(Diagnostic.warning(origin, where + "[" + (i + 1) + "]",
                        "There is no condition " + cond.ref.name + ", so this one never passes. Skipped: " + text));
                continue;
            }
            out.add(cond);
        }
        return out;
    }

    /**
     * Lines from a list whose entries are MythicMobs lines or long-form maps.
     *
     * @param mob a mob's own list, where a line's trigger matters; a metaskill's lines have none
     */
    static List<SkillLine> lines(Object raw, String origin, String where, Names names, boolean mob,
                                 List<Diagnostic> diagnostics) {
        List<SkillLine> out = new ArrayList<>();
        if (!(raw instanceof List)) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "Not a list. Write one skill per line: - damage{a=8} @target ~onAttack"));
            return out;
        }
        List<?> list = (List<?>) raw;
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            String at = where + "[" + (i + 1) + "]";
            String text;
            if (entry instanceof Map) {
                text = SkillParser.longForm((Map<?, ?>) entry);
                if (text == null && ((Map<?, ?>) entry).size() == 1) {
                    // A line with ": " in it (message{m="Boss: hi"}) is a map to
                    // YAML. MythicMobs files are full of them; put it back together.
                    Map.Entry<?, ?> only = ((Map<?, ?>) entry).entrySet().iterator().next();
                    text = only.getKey() + ": " + only.getValue();
                }
                if (text == null) {
                    diagnostics.add(Diagnostic.warning(origin, at, "A long-form skill needs mechanic:. Skipped."));
                    continue;
                }
            } else if (entry == null) {
                continue;
            } else {
                text = entry.toString();
            }
            if (out.size() >= MAX_LINES) {
                diagnostics.add(Diagnostic.warning(origin, at, "More than " + MAX_LINES
                        + " skill lines; the rest are skipped. Move some into a metaskill."));
                break;
            }
            SkillParser.Parsed parsed = SkillParser.parse(text);
            if (!parsed.ok()) {
                diagnostics.add(Diagnostic.warning(origin, at, parsed.error + " Skipped: " + text));
                continue;
            }
            SkillLine line = parsed.line;
            String problem = problem(line, names, mob);
            if (problem != null) {
                diagnostics.add(Diagnostic.warning(origin, at, problem + " Skipped: " + text));
                continue;
            }
            String later = unregistered(line, names);
            if (later != null) {
                diagnostics.add(Diagnostic.warning(origin, at, later + " is not registered yet. The line is kept, "
                        + "and runs once the plugin that adds it has registered it: " + text));
            }
            if (!mob && line.trigger != null) {
                diagnostics.add(Diagnostic.warning(origin, at, "A metaskill's line runs when the skill is cast, so its "
                        + "~" + line.trigger.name + " is ignored: " + text));
            }
            line.source = origin + ": " + at;
            out.add(line);
        }
        return out;
    }

    /**
     * A plugin's name: {@code myplugin:smite}. One nobody has registered yet
     * is kept, because names are looked up as a line runs and a plugin that
     * enables after the engine registers after this load.
     */
    private static boolean plugins(String name) {
        int colon = name.indexOf(':');
        return colon > 0 && !name.startsWith("effect:") && !name.startsWith("e:");
    }

    /** The plugin names in a line nobody has registered yet, or null. */
    static String unregistered(SkillLine line, Names names) {
        if (!names.mechanic(line.mechanic)) {
            return "Mechanic " + line.mechanic;
        }
        if (line.targeter != null && !names.targeter(line.targeter.name)) {
            return "Targeter @" + line.targeter.name;
        }
        for (SkillLine.Cond cond : line.conditions) {
            if (!names.condition(cond.ref.name)) {
                return "Condition " + cond.ref.name;
            }
        }
        return null;
    }

    /** Why a parsed line cannot run, or null when it can. */
    static String problem(SkillLine line, Names names, boolean mob) {
        if (!names.mechanic(line.mechanic) && !plugins(line.mechanic)) {
            return "There is no mechanic " + line.mechanic + ".";
        }
        if (line.targeter != null && !names.targeter(line.targeter.name) && !plugins(line.targeter.name)) {
            return "There is no targeter @" + line.targeter.name + ".";
        }
        for (SkillLine.Cond cond : line.conditions) {
            if (!names.condition(cond.ref.name) && !plugins(cond.ref.name)) {
                return "There is no condition " + cond.ref.name + ".";
            }
        }
        if (mob && line.trigger != null) {
            String trigger = TRIGGERS.get(line.trigger.name);
            if (trigger == null) {
                return "There is no trigger ~on" + line.trigger.name + ". The triggers are "
                        + String.join(", ", new java.util.TreeSet<>(Set.copyOf(TRIGGERS.values()))) + ".";
            }
            if (trigger.equals("timer")) {
                try {
                    if (line.trigger.arg == null || Integer.parseInt(line.trigger.arg.trim()) <= 0) {
                        return "~onTimer needs its interval in ticks: ~onTimer:100.";
                    }
                } catch (NumberFormatException e) {
                    return "~onTimer:" + line.trigger.arg + " is not a number of ticks.";
                }
            }
        }
        return null;
    }

    /** A mob's skill lines, and the stance it starts in. */
    static final class MobSkillSet {
        final List<SkillLine> lines;
        final String stance;

        MobSkillSet(List<SkillLine> lines, String stance) {
            this.lines = List.copyOf(lines);
            this.stance = stance;
        }

        /** The canonical trigger of a line: {@code combat} when none was written. */
        static String trigger(SkillLine line) {
            return line.trigger == null ? "combat" : TRIGGERS.getOrDefault(line.trigger.name, line.trigger.name);
        }
    }

    /** A named skill: MythicMobs' metaskill. */
    static final class Metaskill {
        final ContentId id;
        final double cooldown;
        final List<SkillLine.Cond> conditions;
        final List<SkillLine.Cond> targetConditions;
        final List<SkillLine.Cond> triggerConditions;
        final List<SkillLine> lines;
        final String onCooldownSkill;
        final boolean cancelIfNoTargets;
        final String origin;

        Metaskill(ContentId id, double cooldown, List<SkillLine.Cond> conditions, List<SkillLine.Cond> targetConditions,
                  List<SkillLine.Cond> triggerConditions, List<SkillLine> lines, String onCooldownSkill,
                  boolean cancelIfNoTargets, String origin) {
            this.id = id;
            this.cooldown = cooldown;
            this.conditions = List.copyOf(conditions);
            this.targetConditions = List.copyOf(targetConditions);
            this.triggerConditions = List.copyOf(triggerConditions);
            this.lines = List.copyOf(lines);
            this.onCooldownSkill = onCooldownSkill;
            this.cancelIfNoTargets = cancelIfNoTargets;
            this.origin = origin;
        }
    }
}
