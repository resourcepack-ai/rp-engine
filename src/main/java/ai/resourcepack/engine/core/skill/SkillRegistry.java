package ai.resourcepack.engine.core.skill;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every mechanic, targeter and condition by name, the engine's own and those
 * plugins register. Looked up when a line RUNS, not when it loads, so a
 * plugin enabling after the engine is in time.
 */
final class SkillRegistry implements SkillDefinitions.Names {

    /** A mechanic, handed every target of its line at once. */
    interface Mechanic {
        void run(SkillContext context, SkillLine line, List<Object> targets);

        /**
         * Whether a line with no {@code @targeter} aims at the caster rather
         * than at the trigger: what a model's animation or a stance is about.
         */
        default boolean selfByDefault() {
            return false;
        }
    }

    interface Condition {
        /** Whether {@code cast.target} (an entity or a place) passes. */
        boolean test(SkillCastImpl cast);
    }

    interface Targeter {
        /** Entities and/or Locations. */
        List<Object> find(SkillCastImpl cast);
    }

    private final Map<String, Mechanic> mechanics = new ConcurrentHashMap<>();
    private final Map<String, Condition> conditions = new ConcurrentHashMap<>();
    private final Map<String, Targeter> targeters = new ConcurrentHashMap<>();

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    void mechanic(Mechanic mechanic, String name, String... aliases) {
        mechanics.put(key(name), mechanic);
        for (String alias : aliases) {
            mechanics.put(key(alias), mechanic);
        }
    }

    void condition(Condition condition, String name, String... aliases) {
        conditions.put(key(name), condition);
        for (String alias : aliases) {
            conditions.put(key(alias), condition);
        }
    }

    void targeter(Targeter targeter, String name, String... aliases) {
        targeters.put(key(name), targeter);
        for (String alias : aliases) {
            targeters.put(key(alias), targeter);
        }
    }

    Mechanic findMechanic(String name) {
        return mechanics.get(key(name));
    }

    Condition findCondition(String name) {
        return conditions.get(key(name));
    }

    Targeter findTargeter(String name) {
        return targeters.get(key(name));
    }

    int mechanics() {
        return mechanics.size();
    }

    @Override
    public boolean mechanic(String name) {
        return mechanics.containsKey(key(name)) || name.startsWith("delay") || name.equals("wait");
    }

    @Override
    public boolean targeter(String name) {
        return targeters.containsKey(key(name));
    }

    @Override
    public boolean same(String name, String as, boolean targeter) {
        if (name == null || as == null) {
            return false;
        }
        Object mine = targeter ? targeters.get(key(name)) : mechanics.get(key(name));
        Object theirs = targeter ? targeters.get(key(as)) : mechanics.get(key(as));
        return mine != null && mine == theirs;
    }

    @Override
    public boolean condition(String name) {
        return conditions.containsKey(key(name));
    }
}
