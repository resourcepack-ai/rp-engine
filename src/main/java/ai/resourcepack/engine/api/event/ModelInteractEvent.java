package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player right-clicked a placed model.
 *
 * <p>The engine does nothing with this on its own, and that is deliberate. A
 * chair somebody sits in, a shop that opens a menu, a lever that fires
 * redstone: all of them are a server's own behaviour, and the engine that
 * guessed at one would be wrong for every server that wanted a different one.
 * It says what happened and gets out of the way.
 *
 * <p>Cancelling stops nothing here — there is nothing to stop — but a listener
 * that has handled the click marks it so another listener can leave it alone.
 */
public final class ModelInteractEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId model;
    private final Interaction hitbox;
    private boolean cancelled;

    public ModelInteractEvent(Player player, ContentId model, Interaction hitbox) {
        super(player);
        this.model = model;
        this.hitbox = hitbox;
    }

    /** Which model was clicked. */
    public ContentId model() {
        return model;
    }

    /**
     * The hitbox entity that was clicked.
     *
     * <p>Its location is where the model stands, and its persistent data is
     * where anything a listener wants to remember about this particular
     * placement belongs.
     */
    public Interaction hitbox() {
        return hitbox;
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
