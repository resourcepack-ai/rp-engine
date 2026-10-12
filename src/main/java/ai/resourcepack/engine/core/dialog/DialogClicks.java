package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.DialogInfo;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.function.Supplier;

/**
 * Keeps up with a player whose client turned a dialog's page by itself, and
 * gives a click on a dialog's picture its sound. Internal.
 *
 * <p>A page link that is a {@code show_dialog} opens the next page out of the
 * client's own registry, and the server hears nothing about it — so the engine
 * would go on believing the player was on the page it sent, and a plugin's
 * {@code reopen} after a click on the new one would put the old one back. A
 * click that runs a command is the first thing the server hears from the new
 * page, and {@link DialogsImpl#noteCommand} works out from it which page that
 * was.
 *
 * <p>It also answers a click on a dialog the server has to take away
 * ({@link DialogsImpl#answerClick}): a seamless one, still on the screen, or
 * one the client is waiting on. Watched at LOWEST, before the command runs and
 * before any other plugin has acted on it, so a close that has to come first
 * does — and whether something cancels the command later changes nothing: the
 * player clicked, and the click is answered either way.
 */
public final class DialogClicks implements Listener {

    private final DialogsImpl dialogs;
    /** The server's click: {@code dialogs.click-sound} in config.yml, read on every click so a reload takes. */
    private final Supplier<DialogInfo.ClickSound> serverClick;

    public DialogClicks(DialogsImpl dialogs) {
        this(dialogs, () -> DialogInfo.ClickSound.GAME);
    }

    public DialogClicks(DialogsImpl dialogs, Supplier<DialogInfo.ClickSound> serverClick) {
        this.dialogs = dialogs;
        this.serverClick = serverClick;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        dialogs.noteCommand(event.getPlayer(), event.getMessage());
        // The game's own click, which a click on a dialog's picture does not
        // make by itself: its buttons click, a run of its body's text does not.
        // The dialog may have asked for another sound, or for none.
        dialogs.pictureClickSound(event.getPlayer(), event.getMessage(), serverClick.get())
                .filter(sound -> !sound.silent())
                .ifPresent(sound -> play(event.getPlayer(), sound));
        // Last: an answer may close the dialog, after which nothing above
        // would find it.
        dialogs.answerClick(event.getPlayer(), event.getMessage());
    }

    private static void play(Player player, DialogInfo.ClickSound sound) {
        try {
            player.playSound(player.getLocation(), sound.sound(), SoundCategory.MASTER, sound.volume(), sound.pitch());
        } catch (RuntimeException e) {
            // A key the server will not take: no sound, rather than no click.
        }
    }
}
