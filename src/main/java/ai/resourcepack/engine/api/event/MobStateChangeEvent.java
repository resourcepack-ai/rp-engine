package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.MobState;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A custom mob's animation state changed: it started walking, stopped, was
 * hurt, bit something, died.
 *
 * <p>Fires on a change of what is PLAYING, so a state the mob has no animation
 * for never appears (a mob with no {@code run} goes from idle to walk however
 * fast it goes). {@code previous} and {@code state} may be null for "its rest
 * pose".
 *
 * <p>Not cancellable: it reports what the engine has already decided to show.
 * A plugin that wants another animation calls {@code Mobs.play} on top.
 */
public final class MobStateChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entity entity;
    private final ContentId id;
    private final MobState previous;
    private final MobState state;

    public MobStateChangeEvent(Entity entity, ContentId id, MobState previous, MobState state) {
        this.entity = entity;
        this.id = id;
        this.previous = previous;
        this.state = state;
    }

    public Entity entity() {
        return entity;
    }

    /** Which custom mob it is. */
    public ContentId id() {
        return id;
    }

    /** What it was showing, or null for its rest pose. */
    public MobState previous() {
        return previous;
    }

    /** What it is showing now, or null for its rest pose. */
    public MobState state() {
        return state;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
