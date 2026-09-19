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
 * <p><b>And it needs a datapack reload to have appeared.</b> Dialogs are
 * registry data: the server reads them when it loads its datapacks, which is
 * before any plugin is enabled. So a dialog written by this load is openable
 * after the next {@code /minecraft:reload} or restart, and {@link #pending()}
 * says when that is outstanding. Nothing here reloads on its own — a data
 * reload rebuilds every recipe on the server, which is not a thing a plugin
 * should do to somebody's server because one screen changed.
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
     * not read, until the server is reloaded or restarted. Worth telling an
     * owner about; not worth failing anything over.
     */
    boolean pending();

    /**
     * Opens a dialog on a player's screen.
     *
     * @return false if there is no such dialog, the server is too old, or the
     *         player is holding no pack the dialog's art is in
     */
    boolean show(Player viewer, ContentId id);

    /** Closes whatever dialog a player has open. */
    void close(Player viewer);
}
