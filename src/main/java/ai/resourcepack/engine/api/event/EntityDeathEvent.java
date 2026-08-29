package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A custom entity died.
 *
 * <p>Bukkit's own {@code EntityDeathEvent} fires too, and carries the drops —
 * this one exists because that event cannot tell you the thing that died was
 * {@code mypack:sentry} rather than an ordinary zombie. A listener wanting to
 * change the loot wants both: this one to know what it was, Bukkit's to change
 * what it leaves.
 *
 * <p>Not cancellable. Death is not a decision anybody gets to take back here,
 * and a plugin that wants to prevent one has
 * {@code EntityDamageEvent} for it.
 */
public final class EntityDeathEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entity entity;
    private final ContentId id;

    public EntityDeathEvent(Entity entity, ContentId id) {
        this.entity = entity;
        this.id = id;
    }

    /** The mob that died. Still in the world, as Bukkit's own event has it. */
    public Entity entity() {
        return entity;
    }

    /** What it was. */
    public ContentId id() {
        return id;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
