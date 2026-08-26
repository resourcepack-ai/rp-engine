package ai.resourcepack.engine.api;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Emotes: animations played on a PLAYER rather than on a placed model.
 *
 * <p>An emote renders somebody's skin, and a resource pack cannot reach a live
 * one - so a pack carries a baked rig per player it was built for, and a
 * player with no rig in the pack they are wearing cannot emote.
 * {@link #canPerform} is that question with an answer; it is a real state with
 * a real cause, not a mystery failure.
 *
 * <p>The performer is made invisible for the duration and put back exactly
 * where they were, keeping whatever invisibility they already had minus the
 * time the emote took. Being hit interrupts it. An emote with a cast ends for
 * everybody or for nobody.
 *
 * <p><b>Main thread only</b>, except the catalogue queries ({@code ids},
 * {@code info}) which read concurrent state and are safe anywhere.
 */
public interface Emotes {

    /**
     * Every emote this server holds, in manifest order.
     *
     * <p>An emote's id is the name the panel shows for it, which is also what
     * a player types - unlike a model id, which is not shown to anybody.
     *
     * <p><b>Movement GROUPS are in this list too.</b> A group is worn by name
     * with {@link #play}, exactly as an emote is, so a caller building a menu
     * or completing a command wants both — and studio allocates the two out of
     * one name space, so nothing here can collide. {@link EmoteInfo#isGroup()}
     * tells them apart for a caller that has to care.
     */
    List<String> ids();

    /**
     * What an emote is: how long, whether it loops, who else it needs, and
     * whether it is a group rather than a single emote.
     */
    Optional<EmoteInfo> info(String emoteId);

    /** Whether this player is in an emote right now, as lead or as cast. */
    boolean isEmoting(UUID playerId);

    /**
     * Whether this player has a rig in the pack they are wearing, and could
     * therefore emote at all.
     *
     * <p>False for somebody who joined after the pack was pushed. It says
     * nothing about whether they are on the ground, in combat, or already
     * emoting - {@link #play} answers all of that, with a reason.
     */
    boolean canPerform(Player player);

    /**
     * Plays a solo emote.
     *
     * @return {@link EmoteResult#started()} on success, otherwise a reason
     *         specific enough to tell the player what to do about it.
     */
    EmoteResult play(Player lead, String emoteId);

    /**
     * Plays an emote with a cast.
     *
     * <p>The cast is teleported into place, put in the emote's own facing, and
     * given back their position afterwards. The list must be exactly as long as
     * the emote's performer list - a duet run alone would put one person
     * through half a handshake with nobody opposite, so a mismatch is refused
     * rather than padded or trimmed.
     *
     * <p>Nothing is spawned and nobody is moved until every participant and
     * every destination has been checked.
     */
    EmoteResult play(Player lead, String emoteId, List<Player> cast);

    /**
     * The free-text form, for building your own command.
     *
     * <p>Emote names are free text with spaces in them, so
     * {@code "Slow clap Steve"} is either one emote or an emote and a player
     * and there is no separator to tell them apart. The longest leading run of
     * words that names an emote wins. That resolution lives here rather than in
     * a command because only this library knows which names exist.
     */
    EmoteResult perform(Player lead, List<String> words);

    /**
     * Who could still be named on a half-typed command, for tab completion.
     *
     * <p>Empty unless the words so far name an emote that takes a cast and
     * there are slots left, so completing a solo emote offers nobody and a duet
     * stops offering once its one partner is typed.
     */
    List<String> castCandidates(CommandSender sender, String[] words);

    /**
     * Stops this player's emote, and everybody else's in the same troupe.
     *
     * @return whether they were emoting.
     */
    boolean stop(Player player);
}
