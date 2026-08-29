package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A model is about to be put on an entity, or taken off one.
 *
 * <p>The entity here belongs to somebody else — a MythicMobs boss, a Citizens
 * NPC, a vanilla mob a command block spawned — which is exactly why this is
 * worth telling other plugins about: the thing being dressed is theirs, and
 * they may have an opinion about it being dressed.
 *
 * <p>One event with an {@link Action} rather than two classes, because a
 * listener that cares about binding almost always cares about unbinding, and
 * two registrations for one question is a worse API than one branch.
 */
public final class ModelBindEvent extends Event implements Cancellable {

    /** Which way round this is. */
    public enum Action {

        /** A model is going on. */
        BIND,

        /** One is coming off. */
        UNBIND
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entity host;
    private final ContentId model;
    private final Action action;
    private boolean cancelled;

    public ModelBindEvent(Entity host, ContentId model, Action action) {
        this.host = host;
        this.model = model;
        this.action = action;
    }

    /** The entity being dressed or undressed. */
    public Entity host() {
        return host;
    }

    /**
     * The model going on, or the one coming off.
     *
     * <p>Null on an unbind of an entity whose model cannot be read back — an
     * entity dressed by a version that did not record it, which is worth
     * handling rather than pretending cannot happen.
     */
    public ContentId model() {
        return model;
    }

    /** Whether this is a bind or an unbind. */
    public Action action() {
        return action;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
