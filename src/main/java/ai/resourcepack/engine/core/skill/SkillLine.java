package ai.resourcepack.engine.core.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One skill line, parsed: a MythicMobs line as written
 * ({@code damage{a=8} @target ~onAttack ?stance{s=angry} <50% 0.3}) or the
 * YAML long form, which compiles to the same thing.
 *
 * <p>Names are kept as written but lowercased; aliases are resolved when the
 * line runs, so a mechanic a plugin registers after the content loaded still
 * works without a reload.
 */
final class SkillLine {

    /** What it says to do, lowercased: {@code damage}, {@code effect:particles}. */
    final String mechanic;
    final Args args;
    /** {@code @...}, or null for the default (inherited targets, else the trigger, else itself). */
    final Ref targeter;
    /** {@code ~...}, or null for MythicMobs' default, {@code onCombat}. */
    final Trigger trigger;
    final List<Cond> conditions;
    /** {@code <50%} and friends, or null. */
    final HealthMod health;
    /** 0 to 1; 1 when not written. */
    final double chance;
    /** The text it was parsed from, for diagnostics and events. */
    final String text;
    /** Where it came from, as {@code file: where}, for warnings at run time. */
    String source = "";

    SkillLine(String mechanic, Args args, Ref targeter, Trigger trigger, List<Cond> conditions,
              HealthMod health, double chance, String text) {
        this.mechanic = mechanic;
        this.args = args;
        this.targeter = targeter;
        this.trigger = trigger;
        this.conditions = List.copyOf(conditions);
        this.health = health;
        this.chance = chance;
        this.text = text;
    }

    /** The trigger's name with its {@code on} taken off, lowercased; {@code combat} when none. */
    String triggerName() {
        return trigger == null ? "combat" : trigger.name;
    }

    @Override
    public String toString() {
        return text;
    }

    /** {@code k=v;k2=v2}, keys lowercased, values as written (quotes taken off). */
    static final class Args {
        static final Args NONE = new Args(Map.of(), "");

        final Map<String, String> values;
        final String raw;

        Args(Map<String, String> values, String raw) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            this.raw = raw;
        }

        /** The first of {@code keys} that is set. */
        String get(String... keys) {
            for (String key : keys) {
                String value = values.get(key.toLowerCase(Locale.ROOT));
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        boolean has(String... keys) {
            return get(keys) != null;
        }

        @Override
        public String toString() {
            return raw;
        }
    }

    /** A targeter, or a condition's name and arguments. */
    static final class Ref {
        final String name;
        final Args args;

        Ref(String name, Args args) {
            this.name = name;
            this.args = args;
        }

        @Override
        public String toString() {
            return name + (args.raw.isEmpty() ? "" : "{" + args.raw + "}");
        }
    }

    /** {@code ~onTimer:100}: name {@code timer}, argument {@code 100}. */
    static final class Trigger {
        final String name;
        final String arg;

        Trigger(String name, String arg) {
            this.name = name;
            this.arg = arg;
        }
    }

    /**
     * A condition. Inline ({@code ?}, {@code ?!}, {@code ?~}) or one line of a
     * metaskill's {@code Conditions:}, which can carry an action:
     * {@code true} (the default), {@code false}, {@code cast X},
     * {@code castinstead X}, {@code orElseCast X}, {@code power N}.
     */
    static final class Cond {
        final Ref ref;
        final boolean negate;
        /** Tests the trigger entity rather than the caster ({@code ?~}). */
        final boolean onTrigger;
        final String action;
        final String actionArg;

        Cond(Ref ref, boolean negate, boolean onTrigger, String action, String actionArg) {
            this.ref = ref;
            this.negate = negate;
            this.onTrigger = onTrigger;
            this.action = action;
            this.actionArg = actionArg;
        }

        @Override
        public String toString() {
            return ref.toString();
        }
    }

    /**
     * {@code <50%}, {@code >200}, {@code =30%-50%}, {@code =90%}. The {@code =}
     * form fires once per mob, the first time it is in range (MythicMobs:
     * "will only ever trigger once"); {@code <} and {@code >} fire every time.
     */
    static final class HealthMod {
        final Ranged range;
        final boolean once;

        HealthMod(Ranged range, boolean once) {
            this.range = range;
            this.once = once;
        }

        /** Whether a mob at {@code health} of {@code max} is in range. */
        boolean test(double health, double max) {
            double value = range.percent ? (max <= 0 ? 0 : health / max * 100) : health;
            if (once && range.low == range.high) {
                // =90% is "below 90%" in MythicMobs, not "exactly 90%".
                return value <= range.high;
            }
            return range.test(value);
        }
    }
}
