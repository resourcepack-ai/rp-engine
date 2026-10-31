package ai.resourcepack.engine.core.skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One mob's skill bookkeeping: cooldowns, stance, which health thresholds
 * have fired, when each timer is next due, and how many skill trees it has
 * waiting. Touched only on the mob's own thread.
 *
 * <p>Free of Bukkit (times are handed in), so the rules are tested as
 * arithmetic: {@code SkillStateTest}.
 */
final class SkillState {

    /** Ready time, in milliseconds, by key ({@code skill:<id>}, {@code line:<n>}). */
    private final Map<String, Long> cooldowns = new HashMap<>();
    /** False once an {@code =} health line has fired: it fires once per mob. */
    private final Map<SkillLine, Boolean> armed = new IdentityHashMap<>();
    /** The game tick each timer line is next due. */
    final Map<SkillLine, Long> timers = new IdentityHashMap<>();
    /** What each line's own {@code cooldown=} keys under. */
    private final Map<SkillLine, String> lineKeys = new IdentityHashMap<>();

    String stance;
    /** Skill trees waiting on a delay, or auras ticking. Capped by {@link SkillRuntime#MAX_RUNNING}. */
    int running;
    /** Whose turn it last was, for combat triggers: the mob's target as of its last look. */
    UUID lastTarget;
    /** Its name as this last wrote it, so a name tag's rename is left alone. Null before the first. */
    String nameSet;
    /** Set once the name has been renamed by somebody else. */
    boolean nameTaken;
    /** What it summoned, for {@code @children}. */
    final List<UUID> children = new ArrayList<>();
    /** Where it was spawned, as world name and x, y, z; null when not seen spawning. */
    Object[] spawnedAt;
    /** Warned already, so a broken line says so once per mob rather than every tick. */
    boolean warnedBudget;
    /** Mob-scoped variables, by lowercase name: {@code setvariable}, {@code variableadd}. */
    final Map<String, String> vars = new HashMap<>();
    /** Who it fights, when it has {@code threat-table: true}; null otherwise. */
    ThreatTable threat;
    /** Boss bars its skills made, by name ({@code barcreate}). Removed with the mob. */
    final Map<String, org.bukkit.boss.BossBar> bars = new HashMap<>();

    /** A variable's name as stored: lowercase, and without MythicMobs' {@code caster.} scope. */
    static String varName(String written) {
        String name = written == null ? "" : written.trim().toLowerCase(java.util.Locale.ROOT);
        for (String scope : new String[]{"caster.", "self.", "mob."}) {
            if (name.startsWith(scope)) {
                return name.substring(scope.length());
            }
        }
        return name;
    }

    SkillState(String stance) {
        this.stance = stance;
    }

    /** Whether {@code key} is still cooling down at {@code nowMillis}. */
    boolean cooling(String key, long nowMillis) {
        Long ready = cooldowns.get(key);
        return ready != null && nowMillis < ready;
    }

    /** Seconds left on {@code key}, 0 when ready. */
    double remaining(String key, long nowMillis) {
        Long ready = cooldowns.get(key);
        return ready == null ? 0 : Math.max(0, (ready - nowMillis) / 1000.0);
    }

    void cool(String key, long nowMillis, double seconds) {
        if (seconds > 0) {
            cooldowns.put(key, nowMillis + Math.round(seconds * 1000));
        }
    }

    /** The cooldown key of a line's own {@code cooldown=}. */
    String lineKey(SkillLine line) {
        return lineKeys.computeIfAbsent(line, l -> "line:" + lineKeys.size());
    }

    /**
     * An {@code =} health line seen with the mob {@code inRange}: whether it
     * fires. Once per mob, the first time it is seen in range, as MythicMobs'
     * "will only ever trigger once". Not re-armed by healing back above it:
     * the usual phase skill heals the boss, and a re-armed threshold then
     * fires again on the next hit, every hit.
     */
    boolean crossing(SkillLine line, boolean inRange) {
        if (!inRange || !armed.getOrDefault(line, Boolean.TRUE)) {
            return false;
        }
        armed.put(line, Boolean.FALSE);
        return true;
    }

    /**
     * A timer line looked at on tick {@code now}: whether it is due. The first
     * look only schedules it, one interval on, as MythicMobs does: a mob does
     * not cast its every-ten-seconds skill the tick it appears.
     */
    boolean due(SkillLine line, long now, long interval) {
        Long next = timers.get(line);
        if (next == null) {
            timers.put(line, now + interval);
            return false;
        }
        if (now < next) {
            return false;
        }
        // Behind by more than one interval (an unloaded chunk) is caught up once, not many times.
        long following = next + interval;
        timers.put(line, following <= now ? now + interval : following);
        return true;
    }
}
