package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.Map;

/**
 * A dialog is about to be put on a player's screen: by its command, an item,
 * {@code /rp dialog}, a link from another dialog, or a plugin's
 * {@code Dialogs.show}.
 *
 * <p>Cancelling leaves their screen as it is. This is the event for "not in
 * combat" and "the shop is shut on Sundays": rules about when a dialog may
 * open, which the engine cannot know.
 *
 * <p>It fires every time the dialog is DRAWN, and a dialog is drawn again
 * whenever something in it changes: a page turned, an item moved, a setting
 * switched. {@link #followed} tells those from a dialog opened afresh, and a
 * listener that greets somebody or charges them wants to ignore them.
 */
public final class DialogOpenEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId dialog;
    private final Map<String, String> values;
    private final boolean followed;
    private boolean cancelled;

    public DialogOpenEvent(Player player, ContentId dialog, Map<String, String> values, boolean followed) {
        super(player);
        this.dialog = dialog;
        this.values = values == null ? Map.of() : Map.copyOf(values);
        this.followed = followed;
    }

    /** The dialog. A page of a paged dialog is a dialog of its own: {@code shop.2}. */
    public ContentId dialog() {
        return dialog;
    }

    /** The values it is being opened with, over the player's own. Read only. */
    public Map<String, String> values() {
        return values;
    }

    /**
     * Whether the player reached this from the dialog they were already
     * looking at (a page, a move, a changed setting) rather than opening one.
     */
    public boolean followed() {
        return followed;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
