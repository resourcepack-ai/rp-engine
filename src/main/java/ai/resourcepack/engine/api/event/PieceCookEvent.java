package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.Location;
import org.bukkit.entity.Interaction;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * A placed model or custom block that works as a furnace has finished cooking
 * one item, and the result is about to go into its output slot.
 *
 * <p>Cancelling leaves the input where it is and starts its cooking again.
 * {@link #setResult} changes what comes out.
 */
public final class PieceCookEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId piece;
    private final Location location;
    private final Interaction hitbox;
    private final ItemStack input;
    private ItemStack result;
    private boolean cancelled;

    public PieceCookEvent(ContentId piece, Location location, Interaction hitbox, ItemStack input, ItemStack result) {
        this.piece = piece;
        this.location = location;
        this.hitbox = hitbox;
        this.input = input;
        this.result = result;
    }

    /** What is cooking: a model's or block's content id. A Studio model is {@code studio:<id>}. */
    public ContentId piece() {
        return piece;
    }

    public Location location() {
        return location.clone();
    }

    /** The placed model's hitbox, or null when this is a custom block. */
    public Interaction hitbox() {
        return hitbox;
    }

    /** One of what is being cooked. */
    public ItemStack input() {
        return input.clone();
    }

    public ItemStack result() {
        return result.clone();
    }

    public void setResult(ItemStack result) {
        if (result == null || result.getType().isAir()) {
            throw new IllegalArgumentException("A cook has to make something. Cancel the event instead.");
        }
        this.result = result.clone();
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
