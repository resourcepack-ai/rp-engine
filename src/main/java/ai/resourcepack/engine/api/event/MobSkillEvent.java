package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A custom mob is about to cast a skill: one of its own {@code skills:} lines
 * set off by its trigger, or a metaskill called by name.
 *
 * <p>Cancel it and that skill does not run, and its cooldown is not spent.
 * Fires on the caster's thread, after the line's chance, health modifier and
 * conditions have passed, so it is a skill that WOULD run.
 */
public final class MobSkillEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity caster;
    private final ContentId mob;
    private final String skill;
    private final String trigger;
    private final Entity triggerEntity;
    private boolean cancelled;

    public MobSkillEvent(LivingEntity caster, ContentId mob, String skill, String trigger, Entity triggerEntity) {
        this.caster = caster;
        this.mob = mob;
        this.skill = skill;
        this.trigger = trigger;
        this.triggerEntity = triggerEntity;
    }

    /** The mob casting it. */
    public LivingEntity caster() {
        return caster;
    }

    /** Which custom mob it is. */
    public ContentId mob() {
        return mob;
    }

    /** A metaskill's id ({@code mypack:groundslam}), or the line as written for one of a mob's own lines. */
    public String skill() {
        return skill;
    }

    /** What set it off, without its {@code ~on}: {@code attack}, {@code timer}, {@code damaged}, {@code api}... */
    public String trigger() {
        return trigger;
    }

    /** Whoever hit it, what it hit, who clicked it; null for a timer or a spawn. */
    public Entity triggerEntity() {
        return triggerEntity;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
