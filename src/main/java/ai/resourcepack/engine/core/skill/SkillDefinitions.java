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

        /**
         * Whether {@code name} runs the same mechanic (or, with
         * {@code targeter}, the same targeter) as {@code as}. By name here; the
         * registry compares what each one runs, so an alias is what it names.
         */
        default boolean same(String name, String as, boolean targeter) {
            return name != null && name.trim().equalsIgnoreCase(as);
        }
    }

    /**
     * The mechanics a stranger's push may use (one read as untrusted: see
     * {@code StudioRelay.trusts}): Studio's ability catalogue, which is what
     * its panel and its planner offer, and calling a metaskill. Compared by
     * what each name RUNS ({@link Names#same}), so an alias is the mechanic it
     * names, and an alias somebody invents for {@code command} is not.
     *
     * <p>An allowlist, not a list of the dangerous ones: a stranger's line
     * that ran {@code command{}} or {@code command[]} (the parser takes either
     * bracket) ran as the console, and every mechanic added to the engine
     * after this would otherwise be one more thing to remember to refuse.
     */
    static final List<String> STRANGER_MECHANICS = List.of("skill", "animate", "damage", "heal", "throw", "pull",
            "leap", "lunge", "potion", "ignite", "summon", "setstance", "vfx", "sound", "particles", "message",
            "sendtitle", "shoot", "explosion", "lightning", "tint", "speed", "shield", "teleport");

    /** The targeters a stranger's push may use: Studio's catalogue. None of them reaches past the caster's world. */
    static final List<String> STRANGER_TARGETERS = List.of("self", "target", "trigger", "playersinradius",
            "livinginradius", "nearestplayer", "livingincone", "selflocation", "targetlocation", "forward", "ring",
            "randomlocationsnearcaster");

    /** The condition actions that run a metaskill, whose argument is a skill reference. */
    private static final Set<String> CASTING_ACTIONS = Set.of("cast", "castinstead", "orelsecast");

    /**
     * Why a line (or, with {@code condition}, one line of a metaskill's
     * {@code Conditions:}) from a stranger's push may not run, or null when
     * it may: every mechanic and targeter it names is in
     * {@link #STRANGER_MECHANICS} and {@link #STRANGER_TARGETERS}, inline
     * skills to any depth included, and every metaskill it calls is one a
     * push registered ({@code studio:}). A server's own metaskills are the
     * server's: a stranger's mob calling one that rewards a kill with a
     * command would be that stranger running it on a timer.
     *
     * <p>Parsed with the loader's own parser, so it sees exactly what will
     * run; a line it cannot parse is refused rather than guessed at.
     */
    public static String strangerRefusal(String raw, boolean condition, Names names) {
        return strangerRefusal(raw, condition, names, 0);
    }

    private static String strangerRefusal(String raw, boolean condition, Names names, int depth) {
        if (depth > 8) {
            return "inline skills nested too deep";
        }
        SkillParser.Parsed parsed = condition ? SkillParser.condition(raw, false) : SkillParser.parse(raw);
        if (parsed.line == null) {
            return "it does not parse";
        }
        SkillLine line = parsed.line;
        if (!condition) {
            String mechanic = line.mechanic;
            boolean waits = mechanic.equals("delay") || mechanic.equals("wait");
            if (!waits && STRANGER_MECHANICS.stream().noneMatch(allowed -> names.same(mechanic, allowed, false))) {
                return mechanic + " is not a mechanic a synced pack may use here";
            }
            if (names.same(mechanic, "summon", false)) {
                String why = strangerSummon(line.args.get("type", "t", "mob", "m"));
                if (why != null) {
                    return why;
                }
            }
            if (names.same(mechanic, "skill", false)) {
                String why = strangerSkillRef(line.args.get("skill", "s", "meta", "m", "metaskill", ""), names, depth);
                if (why != null) {
                    return why;
                }
            }
            for (String value : line.args.values.values()) {
                String why = strangerInline(value, names, depth);
                if (why != null) {
                    return why;
                }
            }
            if (line.targeter != null) {
                String targeter = line.targeter.name;
                if (STRANGER_TARGETERS.stream().noneMatch(allowed -> names.same(targeter, allowed, true))) {
                    return "@" + targeter + " is not a targeter a synced pack may use here";
                }
                for (String value : line.targeter.args.values.values()) {
                    String why = strangerInline(value, names, depth);
                    if (why != null) {
                        return why;
                    }
                }
            }
        }
        for (SkillLine.Cond cond : line.conditions) {
            if (CASTING_ACTIONS.contains(cond.action)) {
                String why = strangerSkillRef(cond.actionArg, names, depth);
                if (why != null) {
                    return why;
                }
            }
            for (String value : cond.ref.args.values.values()) {
                String why = strangerInline(value, names, depth);
                if (why != null) {
                    return why;
                }
            }
        }
        return null;
    }

    /**
     * What a stranger's {@code summon} may bring: a custom mob, or a vanilla
     * one that is a creature and not a boss. Not TNT, a crystal, a minecart
     * or a falling block, which are griefing and lag with nothing to fight.
     */
    private static String strangerSummon(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String text = type.trim().toLowerCase(Locale.ROOT);
        String bare = text.startsWith("minecraft:") ? text.substring("minecraft:".length()) : text;
        org.bukkit.entity.EntityType vanilla = null;
        for (org.bukkit.entity.EntityType candidate : org.bukkit.entity.EntityType.values()) {
            if (candidate.name().equalsIgnoreCase(bare)) {
                vanilla = candidate;
            }
        }
        if (vanilla == null) {
            // Not a vanilla name: a custom mob, or nothing, which summons nothing.
            return null;
        }
        if (!vanilla.isAlive() || vanilla == org.bukkit.entity.EntityType.PLAYER
                || vanilla.name().equals("WITHER") || vanilla.name().equals("ENDER_DRAGON")
                || vanilla.name().equals("ARMOR_STAND")) {
            return "summon{type=" + type.trim() + "} is not a creature a synced pack may summon here";
        }
        return null;
    }

    /** A metaskill a stranger's line calls: inline (checked in turn), or one a push registered. */
    private static String strangerSkillRef(String ref, Names names, int depth) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String text = ref.trim();
        if (text.startsWith("[")) {
            return strangerInline(text, names, depth);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        if (colon >= 0 && !lower.substring(0, colon).equals("studio")) {
            return "it calls " + text + ", which is the server's own";
        }
        return null;
    }

    /** An argument that lists skills inline ({@code [ - damage{a=1} ]}): each checked as a line. */
    private static String strangerInline(String value, Names names, int depth) {
        if (value == null || !value.trim().startsWith("[")) {
            return null;
        }
        for (String piece : SkillParser.inline(value.trim())) {
            String why = strangerRefusal(piece, false, names, depth + 1);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    private static Map<String, String> triggers() {
        Map<String, String> map = new HashMap<>();
        for (String name : List.of("spawn", "load", "spawnorload", "death", "attack", "damaged", "timer",
                "interact", "combat", "entercombat", "dropcombat", "changetarget", "playerkill", "kill",
                "shoot", "projectilehit", "signal")) {
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
        map.put("bowshoot", "shoot");
        map.put("shootbow", "shoot");
        map.put("projectileland", "projectilehit");
        map.put("hit", "projectilehit");
        map.put("projectile", "projectilehit");
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
            boolean threat = threatTable(body);
            Map<String, String> variables = variables(body, definition.origin(), definition.id().path(), diagnostics);
            if (raw == null && !threat && variables.isEmpty()) {
                continue;
            }
            String where = definition.id().path() + ".skills";
            List<SkillLine> lines = raw == null ? List.of()
                    : lines(raw, definition.origin(), where, names, true, diagnostics);
            String stance = body.string("stance").orElse(null);
            if (!lines.isEmpty() || stance != null || threat || !variables.isEmpty()) {
                mobs.put(definition.id(), new MobSkillSet(lines, stance, threat, variables));
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

    /**
     * {@code threat-table: true}, MythicMobs' {@code ThreatTable: true} or its
     * {@code Modules: {ThreatTable: true}}.
     */
    static boolean threatTable(DefinitionNode body) {
        for (String key : List.of("threat-table", "ThreatTable", "threat")) {
            if (body.bool(key).orElse(false)) {
                return true;
            }
        }
        Object modules = body.has("modules") ? body.raw("modules") : body.raw("Modules");
        if (modules instanceof Map) {
            DefinitionNode node = DefinitionNode.of((Map<?, ?>) modules);
            return node.bool("ThreatTable").orElse(node.bool("threat-table").orElse(false));
        }
        return false;
    }

    /** {@code variables: {phase: 1, mood: calm}}: a mob's variables as it spawns. */
    static Map<String, String> variables(DefinitionNode body, String origin, String where, List<Diagnostic> diagnostics) {
        Object raw = body.has("variables") ? body.raw("variables") : body.raw("Variables");
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map)) {
            diagnostics.add(Diagnostic.warning(origin, where, "variables: is not a map. Try variables: {phase: 1}."));
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        ((Map<?, ?>) raw).forEach((key, value) -> {
            if (key != null && value != null && !(value instanceof Map) && !(value instanceof List)) {
                out.put(SkillState.varName(key.toString()), value.toString());
            }
        });
        return out;
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

        /** {@code threat-table: true}: it fights whoever has hurt it most. */
        final boolean threatTable;
        /** Its variables as it spawns. */
        final Map<String, String> variables;

        MobSkillSet(List<SkillLine> lines, String stance) {
            this(lines, stance, false, Map.of());
        }

        MobSkillSet(List<SkillLine> lines, String stance, boolean threatTable, Map<String, String> variables) {
            this.lines = List.copyOf(lines);
            this.stance = stance;
            this.threatTable = threatTable;
            this.variables = Map.copyOf(variables);
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
