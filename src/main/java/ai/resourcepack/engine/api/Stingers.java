package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Optional;

import org.bukkit.entity.Player;

/**
 * Stinger transitions: a full-screen animation that covers a player's screen,
 * holds while the server changes something underneath (a teleport, a match
 * starting), and uncovers it again.
 *
 * <p><strong>The pack draws it, the engine only times it.</strong> A stinger
 * is one character in a Studio pack's font, and the pack's text shader
 * stretches any title drawn in one of its trigger colours over the whole
 * screen, reading how far through it is from the title's fade. So playing one
 * is sending titles: the cover coming in as a title fading in, the cover
 * leaving as a fresh title fading out. A player whose pack does not hold the
 * stinger would see a stray character, so it plays only for players holding
 * the pack it came in (the same rule a Studio VFX is shown by).
 *
 * <p>Ids are {@code studio:<id>}: stingers come from Studio packs (a push, a
 * Studio zip in the content folder, or the pack this server publishes).
 *
 * <p>A new play on a player replaces the one already playing on them. Any
 * thread; {@code whenCovered} runs on the player's own thread (the main thread,
 * or their region's on Folia).
 */
public interface Stingers {

    /** Every stinger id, sorted. */
    List<ContentId> ids();

    Optional<StingerInfo> info(ContentId id);

    /** A stinger by what somebody typed: {@code studio:arena}, or a bare {@code arena}. */
    Optional<StingerInfo> find(String name);

    /** Whether this player's pack holds this stinger, which is what {@link #play} needs. */
    boolean canPlay(Player player, ContentId id);

    /**
     * Plays a stinger whole: the cover comes in, {@code whenCovered} runs, it
     * holds for the stinger's own hold, and it leaves. Empty when there is no
     * such stinger or this player's pack does not hold it, and then
     * {@code whenCovered} is not run.
     */
    Optional<StingerPlay> play(Player player, ContentId id, Runnable whenCovered);

    /** As {@link #play(Player, ContentId, Runnable)}, holding for {@code holdTicks} instead of the stinger's own. */
    Optional<StingerPlay> play(Player player, ContentId id, int holdTicks, Runnable whenCovered);

    /**
     * Covers a player's screen and holds it until {@link StingerPlay#release()}
     * is called, for a wait whose length is not known up front (a world
     * loading, a match filling). {@code whenCovered} runs once it is covered.
     */
    Optional<StingerPlay> cover(Player player, ContentId id, Runnable whenCovered);

    /** What is playing on this player now, if anything. */
    Optional<StingerPlay> playing(Player player);

    /** Cancels what is playing on this player. Returns whether anything was. */
    boolean stop(Player player);
}
