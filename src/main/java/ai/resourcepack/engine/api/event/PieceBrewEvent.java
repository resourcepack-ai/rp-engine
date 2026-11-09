package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.Location;
import org.bukkit.entity.Interaction;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A placed model or custom block that works as a brewing stand has finished a
 * brew, and the bottles are about to change.
 *
 * <p>Cancelling leaves the bottles and the reagent as they are.
 * {@link #results} is the three bottle slots in order, null where a slot does
 * not change, and changing the list changes what comes out.
 */
public final class PieceBrewEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId piece;
    private final Location location;
    private final Interaction hitbox;
    private final ItemStack reagent;
    private final List<ItemStack> results;
    private boolean cancelled;

    public PieceBrewEvent(ContentId piece, Location location, Interaction hitbox, ItemStack reagent,
                          List<ItemStack> results) {
        this.piece = piece;
        this.location = location;
        this.hitbox = hitbox;
        this.reagent = reagent;
        this.results = results;
    }

    /** What is brewing: a model's or block's content id. A Studio model is {@code studio:<id>}. */
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

    public ItemStack reagent() {
        return reagent.clone();
    }

    /** The three bottle slots in order, null where nothing changes. Live: set an entry to change it. */
    public List<ItemStack> results() {
        return results;
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
