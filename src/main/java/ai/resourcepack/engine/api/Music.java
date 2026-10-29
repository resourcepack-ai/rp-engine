package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/**
 * Background music: playlists of the pack's sounds, one per player.
 *
 * <p>Each player hears at most one piece at a time. What they hear is, in
 * order: what {@link #play} started for them; otherwise the music of the place
 * they are standing in, the highest {@code priority} first; otherwise nothing.
 * Walking from one place to another changes the music, and Minecraft's own
 * soundtrack is kept quiet while any plays unless the music says otherwise.
 *
 * <p>A track is followed by the player rather than left where it started, so
 * a mono track does not fade as they walk away from it. A stereo one sounds
 * the same either way.
 *
 * <p>Main thread only, except {@link #ids} and {@link #info}.
 */
public interface Music {

    /** Every piece of music, sorted. */
    Collection<ContentId> ids();

    /** What the pack said a piece of music is, or empty if there is none. */
    Optional<MusicInfo> info(ContentId id);

    /**
     * Plays one to a player from its first track, wherever they are, until
     * {@link #stop} or another {@code play}. Their place's music waits.
     *
     * @return false if there is no such music
     */
    boolean play(Player player, ContentId id);

    /**
     * Stops what is playing for a player. Music that {@link #play} started
     * ends, and the music of their place (if any) comes back. Called again, or
     * with nothing started, it stops the place's music, which stays quiet
     * until they leave that place.
     */
    void stop(Player player);

    /**
     * Moves a player on to the next track at once.
     *
     * @return false if nothing is playing for them
     */
    boolean skip(Player player);

    /** The music playing for a player, if any. */
    Optional<ContentId> playing(Player player);

    /** The track playing for a player, if any. */
    Optional<ContentId> track(Player player);
}
