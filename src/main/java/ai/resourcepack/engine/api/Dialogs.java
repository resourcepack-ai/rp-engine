package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/**
 * Opening the screens a pack declares — Minecraft 1.21.6's dialogs.
 *
 * <p>Obtained from {@code RPEngineAPI.dialogs()}. Everything here is
 * main-thread only: showing a dialog dispatches a command.
 *
 * <p><b>A dialog needs 1.21.6.</b> On anything older {@link #supported()} is
 * false and {@link #show} answers false without doing anything — the engine
 * still loads the definitions and still lists them, so an addon can say "this
 * server is too old for that" rather than finding out from a stack trace.
 *
 * <p><b>And it needs a server restart to have appeared.</b> Dialogs are
 * registry data: the server reads them when it loads its world, which is
 * before any plugin is enabled. So a dialog written by this load is openable
 * after the next RESTART — not after a {@code /minecraft:reload}, which
 * rebuilds recipes and advancements and leaves this registry exactly as the
 * world load left it — and {@link #pending()} says when that is outstanding.
 * Nothing here reloads on its own: a data reload rebuilds every recipe on the
 * server, which is not a thing a plugin should do to somebody's server because
 * one screen changed, and it would not make the dialog openable anyway.
 */
public interface Dialogs {

    /** Every dialog id, sorted. */
    Collection<ContentId> ids();

    /** What the pack said a dialog is. */
    Optional<DialogInfo> info(ContentId id);

    /** Whether this server's Minecraft version has dialogs at all. */
    boolean supported();

    /**
     * Whether a dialog on disk is not yet in the server's registry.
     *
     * <p>True from the moment a load writes something the running server has
     * not read, until the server is restarted. Worth telling an owner about;
     * not worth failing anything over.
     */
    boolean pending();

    /**
     * Whether {@link #show} would get as far as the client.
     *
     * <p>Answers the half of a failure this engine knows for certain: the
     * version, whether the dialog exists, whether the player is online, and
     * whether they are holding the pack its art is in. It deliberately does not
     * answer whether the SERVER has read the dialog yet — nothing can ask the
     * registry that without reaching past Bukkit, which is why {@link #pending}
     * exists and why it is a warning rather than an answer.
     *
     * <p>It is here because the alternative is guessing. {@code show} returning
     * false used to be all a caller had, so "the player is wearing the server's
     * own pack" and "the server has not restarted" were one answer, and the
     * message an owner got named whichever the caller happened to check first.
     */
    boolean canShow(Player viewer, ContentId id);

    /**
     * Opens a dialog on a player's screen.
     *
     * @return false if there is no such dialog, the server is too old, the
     *         player is holding no pack the dialog's art is in, or the server
     *         has not read the dialog yet (see {@link #pending})
     */
    boolean show(Player viewer, ContentId id);

    /** Closes whatever dialog a player has open. */
    void close(Player viewer);
}
