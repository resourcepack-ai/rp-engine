package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.Sounds;
import ai.resourcepack.engine.core.version.Compatibility;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Whether a stack is a music disc, and what it plays.
 *
 * <p>A version fork. Below 1.21 a disc is a {@code MUSIC_DISC_} material and
 * nothing else can be; from 1.21 any item can be made one by the
 * {@code jukebox_playable} component, and asking an item about that component
 * names API that does not exist on the older servers. So the newer question is
 * {@link ComponentDiscs}, a class of its own that is never loaded below 1.21,
 * and this interface is how the jukebox asks without knowing which it got.
 *
 * @see Feature#JUKEBOX_SONGS
 */
public interface Discs {

    /**
     * What {@code stack} plays in a jukebox.
     *
     * @return empty if it is not a disc at all; a present but EMPTY string for
     *         a disc whose song cannot be named — it still goes in and comes
     *         back out, it just plays nothing; otherwise the sound key
     */
    Optional<String> songOf(ItemStack stack);

    /** The arm for this server. */
    static Discs forServer(Compatibility compatibility, Sounds sounds, Logger logger) {
        return compatibility != null && compatibility.has(Feature.JUKEBOX_SONGS)
                ? new ComponentDiscs(sounds, logger)
                : new Materials();
    }

    /** Below 1.21: a disc is its material, and every one is vanilla's. */
    final class Materials implements Discs {

        @Override
        public Optional<String> songOf(ItemStack stack) {
            if (stack == null) {
                return Optional.empty();
            }
            return DiscSongs.ofMaterial(stack.getType().name());
        }
    }
}
