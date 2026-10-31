package ai.resourcepack.engine.core.skill;

import ai.resourcepack.common.scheduler.Tasks;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code aura{auraName=venom;duration=100;interval=20;onStart=...;onTick=...;onEnd=...}}:
 * skills that run on a target for a while, every {@code interval} ticks.
 * MythicMobs' damage-over-time is this.
 *
 * <p>One aura of a name per target: casting it again restarts its clock
 * (MythicMobs' default of one stack, refreshed), rather than stacking a
 * second timer. It ticks on the target's own thread, and ends early when the
 * target dies, leaves, or the content reloads. Its lines run as the caster's,
 * aimed at the target.
 */
final class Auras {

    private Auras() {
    }

    /** Ticks left, by target and aura name. */
    private static final Map<String, long[]> RUNNING = new ConcurrentHashMap<>();

    static boolean start(SkillRuntime runtime, SkillCastImpl cast, LivingEntity target) {
        String name = cast.string("auraname", "aura", "aura", "name", "n", "buffname", "debuffname");
        long duration = Math.max(1, Math.round(cast.number("duration", 200, "d", "ticks", "t")));
        long interval = Math.max(1, Math.round(cast.number("interval", 20, "i")));
        int charges = cast.integer("charges", 0, "c");
        String key = target.getUniqueId() + "/" + name;
        String source = cast.context.mob + " aura " + name;
        List<SkillLine> onStart = runtime.inline(cast.raw("onstart", "os", "onstartskill"), source);
        List<SkillLine> onTick = runtime.inline(cast.raw("ontick", "ot", "ontickskill"), source);
        List<SkillLine> onEnd = runtime.inline(cast.raw("onend", "oe", "onendskill"), source);
        long[] existing = RUNNING.get(key);
        if (existing != null) {
            existing[0] = duration;
            existing[1] = charges > 0 ? charges : existing[1];
            return true;
        }
        long[] left = {duration, charges > 0 ? charges : Long.MAX_VALUE};
        RUNNING.put(key, left);
        SkillContext context = cast.context.child(List.of(target));
        runtime.execute(context, onStart, 0);
        int gen = runtime.generation;
        Tasks.Handle[] handle = new Tasks.Handle[1];
        handle[0] = Tasks.onTimer(runtime.plugin, target, () -> {
            boolean over = gen != runtime.generation || !target.isValid() || target.isDead()
                    || left[0] <= 0 || left[1] <= 0;
            if (over) {
                RUNNING.remove(key, left);
                if (gen == runtime.generation && target.isValid()) {
                    context.run.executed = 0;
                    runtime.execute(context, onEnd, 0);
                }
                if (handle[0] != null) {
                    handle[0].cancel();
                }
                return;
            }
            left[0] -= interval;
            left[1]--;
            context.run.executed = 0;
            runtime.execute(context, onTick, 0);
        }, interval, interval);
        return true;
    }
}
