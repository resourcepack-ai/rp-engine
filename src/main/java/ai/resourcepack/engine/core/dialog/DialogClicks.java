package ai.resourcepack.engine.core.dialog;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Keeps up with a player whose client turned a dialog's page by itself. Internal.
 *
 * <p>A page link that is a {@code show_dialog} opens the next page out of the
 * client's own registry, and the server hears nothing about it — so the engine
 * would go on believing the player was on the page it sent, and a plugin's
 * {@code reopen} after a click on the new one would put the old one back. A
 * click that runs a command is the first thing the server hears from the new
 * page, and {@link DialogsImpl#noteCommand} works out from it which page that
 * was. Watched at MONITOR, after anything that might cancel the command, and
 * before the command itself runs.
 */
public final class DialogClicks implements Listener {

    private final DialogsImpl dialogs;

    public DialogClicks(DialogsImpl dialogs) {
        this.dialogs = dialogs;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        dialogs.noteCommand(event.getPlayer(), event.getMessage());
        // The game's own click, which a click on a dialog's picture does not
        // make by itself: its buttons click, a run of its body's text does not.
        if (dialogs.clickedPicture(event.getPlayer(), event.getMessage())) {
            event.getPlayer().playSound(event.getPlayer().getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK,
                    org.bukkit.SoundCategory.MASTER, 0.25f, 1f);
        }
    }
}
