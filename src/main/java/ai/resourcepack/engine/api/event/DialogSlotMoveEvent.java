package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

/**
 * A player is about to move a stack in a dialog whose items move. Cancellable.
 *
 * <p>A dialog can show a player's inventory and ender chest with slots they can
 * click: the first click picks a stack up, the second puts it down in another
 * slot, merging with the same item or swapping with a different one. This is
 * asked before the second click moves anything — so a server whose rules say a
 * stack may not go somewhere (a soulbound item, an event world) can say no, and
 * the items stay where they are.
 *
 * <p>Slots are named {@code <container>/<index>}: {@code inv/0}-{@code inv/35}
 * the player's inventory in Bukkit's numbering (0-8 the hotbar), and
 * {@code ender/0}-{@code ender/26} their ender chest. The stacks are copies;
 * changing them changes nothing.
 */
public final class DialogSlotMoveEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId dialog;
    private final String from;
    private final String to;
    private final ItemStack moving;
    private final ItemStack onto;
    private boolean cancelled;

    public DialogSlotMoveEvent(Player player, ContentId dialog, String from, String to, ItemStack moving, ItemStack onto) {
        super(player);
        this.dialog = dialog;
        this.from = from;
        this.to = to;
        this.moving = moving;
        this.onto = onto;
    }

    /** The dialog it happens in. */
    public ContentId dialog() {
        return dialog;
    }

    /** The slot the stack was picked up from, as {@code <container>/<index>}. */
    public String from() {
        return from;
    }

    /** The slot it is being put down on. */
    public String to() {
        return to;
    }

    /** A copy of the stack being moved. */
    public ItemStack moving() {
        return moving;
    }

    /** A copy of what is in the slot it goes on, or null for an empty slot. */
    public ItemStack onto() {
        return onto;
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
