package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;

import java.util.List;

/**
 * One skill tree as it runs: who cast it, what set it off, the targets a
 * metaskill's lines inherit, and its power. A metaskill call makes a child;
 * every child shares its parent's {@link Run}.
 */
final class SkillContext {

    /** What a whole tree shares, however deep its metaskill calls go. */
    static final class Run {
        /** The event that set it off, while it can still be changed: only during the first, synchronous stretch. */
        final Event event;
        boolean eventLive;
        /** Lines run since the tree last waited; capped by {@link SkillRuntime#MAX_LINES_PER_TICK}. */
        int executed;
        final int generation;
        /** A death skill: it runs on after its caster is gone, at {@link #deathAt}. */
        final boolean death;
        final Location deathAt;
        /** The damage of the hit that set it off, for {@code <skill.damage>}. */
        final double damage;

        Run(Event event, int generation, boolean death, Location deathAt, double damage) {
            this.event = event;
            this.eventLive = event != null;
            this.generation = generation;
            this.death = death;
            this.deathAt = deathAt;
            this.damage = damage;
        }
    }

    final LivingEntity caster;
    final ContentId mob;
    final SkillState state;
    final Entity trigger;
    final String triggerName;
    final Run run;
    double power = 1;
    int depth;
    /** The targets a line without a targeter aims at; null outside a metaskill call. */
    List<Object> inherited;
    /** One of the mob's own lines, which fires MobSkillEvent before it runs. */
    boolean top;

    SkillContext(LivingEntity caster, ContentId mob, SkillState state, Entity trigger, String triggerName, Run run) {
        this.caster = caster;
        this.mob = mob;
        this.state = state;
        this.trigger = trigger;
        this.triggerName = triggerName;
        this.run = run;
    }

    SkillContext child(List<Object> targets) {
        SkillContext child = new SkillContext(caster, mob, state, trigger, triggerName, run);
        child.power = power;
        child.depth = depth + 1;
        child.inherited = targets;
        return child;
    }

    /** Whether the caster can still act: alive, or this is its death skill. */
    boolean alive() {
        return run.death || (caster.isValid() && !caster.isDead());
    }

    /** Where the caster is, or where it died. */
    Location casterLocation() {
        if (caster.isValid() || run.deathAt == null) {
            return caster.getLocation();
        }
        return run.deathAt.clone();
    }
}
