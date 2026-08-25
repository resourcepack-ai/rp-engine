package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/**
 * The custom sounds this server holds.
 *
 * <p>Reads are safe from any thread. Playing one is main thread only, like
 * everything that touches a player.
 *
 * <p><strong>A sound only exists for a player holding the pack.</strong>
 * Nothing is streamed at play time — the audio is in the resource pack — so
 * playing one at somebody who declined it is silence rather than an error, and
 * there is nothing the server can do about that except know it.
 */
public interface Sounds {

    /** Every custom sound id, sorted. */
    Collection<ContentId> ids();

    /** What the pack said a sound is, or empty if there is no such sound. */
    Optional<SoundInfo> info(ContentId id);

    /** As {@link #info(ContentId)}, from the text form of an id. */
    Optional<SoundInfo> info(String id);

    /**
     * Plays one to a single player, at the pack's own volume and pitch.
     *
     * @return false if there is no such sound
     */
    boolean play(Player player, ContentId id);

    /** Plays one to a single player, overriding volume and pitch. */
    boolean play(Player player, ContentId id, float volume, float pitch);

    /**
     * Plays one at a place, for everybody near enough to hear it.
     *
     * @return false if there is no such sound
     */
    boolean playAt(Location location, ContentId id);

    /** Plays one at a place, overriding volume and pitch. */
    boolean playAt(Location location, ContentId id, float volume, float pitch);
}
