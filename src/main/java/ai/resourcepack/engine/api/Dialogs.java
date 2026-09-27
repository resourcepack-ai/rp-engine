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
 * <p><b>It does not need a restart.</b> A dialog is registry data, and the
 * server builds that registry when it loads its world, before any plugin is
 * enabled — so for a while a dialog written by this load could not be opened
 * until the next start. It no longer works that way: the engine sends the
 * whole dialog inside {@code /dialog show}, which has accepted one written out
 * in full since the version this feature needs. A dialog loaded, pushed or
 * edited a moment ago opens now.
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
     * not read, until the server is restarted.
     *
     * <p><b>This no longer gates anything.</b> It once meant "these will not
     * open yet"; it now means only that the ids are not addressable from
     * OUTSIDE the engine — from somebody's own datapack, a command block, or a
     * hand-written {@code minecraft:show_dialog} — because that is the one
     * thing a registry entry is still needed for. {@link #show} works either
     * way, so do not check this before calling it.
     */
    boolean pending();

    /**
     * Whether {@link #show} would get as far as the client.
     *
     * <p>Answers the half of a failure this engine knows for certain: the
     * version, whether the dialog exists, whether the player is online, and
     * whether they are holding the pack its art is in. What it cannot promise
     * is that the game will accept the dialog — that is the game's own codec
     * reading somebody's JSON, and the only way to find out is to send it.
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
     *         player is holding no pack the dialog's art is in, or the game
     *         refused the dialog itself — which is a fault in its JSON, and is
     *         reported to the console with what the game made of it
     */
    boolean show(Player viewer, ContentId id);

    /** Closes whatever dialog a player has open. */
    void close(Player viewer);
}
