package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Placement;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

/**
 * A player placed a model, and it is standing.
 *
 * <p>The other half of {@link ModelPlaceEvent}, which asks before anything
 * exists and so has no placement to give. This one has it: the handle, its
 * hitbox and its {@link Placement#data()}, which is where whatever the item
 * carried belongs if it should outlive the stack. Reading the stack in the
 * first event and writing the placement in a task a tick later was the only
 * way to do that before, and a tick is long enough for somebody to break it.
 *
 * <p>Fires for a content-folder piece and a Studio model alike, and for a
 * plugin's {@code Models.place} when it names a placer. Not cancellable: the
 * model is already down, and {@link Placement#remove} is how to take it back.
 */
public final class ModelPlacedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId model;
    private final Placement placement;
    private final ItemStack stack;

    public ModelPlacedEvent(Player player, ContentId model, Placement placement, ItemStack stack) {
        super(player);
        this.model = model;
        this.placement = placement;
        this.stack = stack;
    }

    /** Which piece. */
    public ContentId model() {
        return model;
    }

    /** The model now standing. */
    public Placement placement() {
        return placement;
    }

    /**
     * The stack it was placed from, or null when it was not placed from one
     * (a plugin calling {@code Models.place}). The live one in the player's
     * hand, before one is taken off it.
     */
    public ItemStack stack() {
        return stack;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
