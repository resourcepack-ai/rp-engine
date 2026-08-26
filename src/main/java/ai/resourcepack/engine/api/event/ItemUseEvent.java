package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

/**
 * A player used a custom item.
 *
 * <p>The engine does nothing with this on its own. A wand that casts, a key
 * that opens a door, a compass that points at the nearest player: all of them
 * are a server's own behaviour, and an engine that guessed at one would be
 * wrong for every server that wanted a different one. This says what happened
 * and gets out of the way — the same shape as
 * {@link ModelInteractEvent}, for the same reason.
 *
 * <p>Cancelling it stops the vanilla use of the stack as well, so an item that
 * is a bucket underneath does not also fill with water when it is meant to be
 * a wand.
 */
public final class ItemUseEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** How the item was used. */
    public enum Action {

        /** Right-click, in the air. */
        RIGHT_CLICK,

        /** Right-click, on a block. {@link #block()} is that block. */
        RIGHT_CLICK_BLOCK,

        /** Left-click, in the air. */
        LEFT_CLICK,

        /** Left-click, on a block. {@link #block()} is that block. */
        LEFT_CLICK_BLOCK
    }

    private final ContentId item;
    private final ItemStack stack;
    private final Action action;
    private final Block block;
    private boolean cancelled;

    public ItemUseEvent(Player player, ContentId item, ItemStack stack, Action action, Block block) {
        super(player);
        this.item = item;
        this.stack = stack;
        this.action = action;
        this.block = block;
    }

    /** Which custom item. */
    public ContentId item() {
        return item;
    }

    /**
     * The stack itself.
     *
     * <p>The live one out of the player's hand, so a listener that wants to
     * consume a use can change its amount or its durability directly.
     */
    public ItemStack stack() {
        return stack;
    }

    /** How it was used. */
    public Action action() {
        return action;
    }

    /** The block it was used on, or null if it was used in the air. */
    public Block block() {
        return block;
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
