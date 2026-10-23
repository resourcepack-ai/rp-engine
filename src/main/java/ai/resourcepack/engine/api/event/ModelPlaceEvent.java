package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

/**
 * A player is about to place a model. Cancellable.
 *
 * <p>The engine decides whether a piece can <em>physically</em> go somewhere —
 * the space is air, nothing is already there. Whether it is <em>allowed</em> to
 * is a rule about somebody's server, and the engine cannot see those. Region
 * protection, plot ownership, build height, an event world where nothing may be
 * placed at all: every one of them is a decision a server makes, so this asks
 * rather than guesses.
 */
public final class ModelPlaceEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId model;
    private final Block block;
    private final ItemStack stack;
    private boolean cancelled;

    /** A placement that was not made from a stack, so {@link #stack()} is null. */
    public ModelPlaceEvent(Player player, ContentId model, Block block) {
        this(player, model, block, null);
    }

    public ModelPlaceEvent(Player player, ContentId model, Block block, ItemStack stack) {
        super(player);
        this.model = model;
        this.block = block;
        this.stack = stack;
    }

    /** Which piece. */
    public ContentId model() {
        return model;
    }

    /** The block space it would occupy. */
    public Block block() {
        return block;
    }

    /**
     * The stack it is being placed from, or null when it is not being placed
     * from one: a plugin calling {@code Models.place}, say.
     *
     * <p>The live one out of the player's hand, as it is before the model goes
     * down, so whatever the item carries (its persistent data, its name, its
     * lore) can be read here and moved onto the placement. One is taken off it
     * after the model is placed, unless the player is in creative.
     */
    public ItemStack stack() {
        return stack;
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
