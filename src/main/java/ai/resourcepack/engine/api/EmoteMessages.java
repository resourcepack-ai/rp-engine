package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

/**
 * The few things an emote has to tell a player while it is running.
 *
 * <p>A refusal comes back to whoever called {@link Emotes#play} and they write
 * the words. These three don't: they happen to somebody who did not make the
 * call, at a moment nobody is waiting on a return value - being pulled into
 * somebody else's emote, an emote ending, and finding out on join that the one
 * you were in did not survive a crash. Silence there reads as a bug.
 *
 * <p>So the library asks, and a host answers in its own words and its own
 * palette. Supply one with
 * {@code RpaiLibrary.builder(plugin).emoteMessages(...)}; with none, nothing is
 * said at all, which is the right default for a library that must not put words
 * in anybody's chat box.
 *
 * <p>Every method is called on the main thread.
 */
public interface EmoteMessages {

    /**
     * Somebody has been pulled into another player's emote. They were
     * teleported and will be put back.
     */
    void pulledIn(Player member, Player lead, String emote);

    /**
     * An emote has ended and this player is back where they were.
     *
     * @param alone false when other people were in it - worth saying, because
     *              they were moved and are now somewhere they didn't walk to.
     */
    void stopped(Player player, boolean alone);

    /**
     * This player joined still carrying the marks of an emote that never
     * finished - the server went down mid-emote - and has been put back.
     */
    void restoredAfterInterruption(Player player);
}
