package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.Map;

/**
 * A player clicked something in a dialog that runs a command: a button, or an
 * area of its picture.
 *
 * <p>A click in a dialog reaches the server as the command it runs, so this is
 * that command, recognised as a click because the dialog the player was shown
 * holds exactly it. Cancelling stops the command running, whoever it belongs
 * to. This is the event for a shop that checks a balance before
 * {@code /buy sword} goes through, and for counting which buttons get pressed.
 *
 * <p>What it cannot see: a click the client answers by itself. A link that
 * opens a URL, copies text or turns to a page the client already holds never
 * reaches the server, and neither does a plain Close.
 */
public final class DialogClickEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId dialog;
    private final Map<String, String> values;
    private final String command;
    private boolean cancelled;

    public DialogClickEvent(Player player, ContentId dialog, Map<String, String> values, String command) {
        super(player);
        this.dialog = dialog;
        this.values = values == null ? Map.of() : Map.copyOf(values);
        this.command = command;
    }

    /** The dialog that was clicked in. */
    public ContentId dialog() {
        return dialog;
    }

    /** The values that dialog was opened with. Read only. */
    public Map<String, String> values() {
        return values;
    }

    /** The command the click runs, without its leading slash: {@code buy sword}. */
    public String command() {
        return command;
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
