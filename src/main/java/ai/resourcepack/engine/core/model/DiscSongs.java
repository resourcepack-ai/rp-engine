package ai.resourcepack.engine.core.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Which sound a music disc plays, worked out from its name.
 *
 * <p><strong>Derived, not tabled.</strong> Every vanilla disc follows one rule
 * — {@code MUSIC_DISC_X} plays {@code minecraft:music_disc.x}, and the song
 * {@code minecraft:x} is that same disc — and that includes the ones whose
 * names are numbers ({@code 5}, {@code 11}, {@code 13}) and the one with two
 * words ({@code creator_music_box}). A table would be a list to keep up with
 * every release; the rule has held since discs existed, and a disc added next
 * year works the day it ships.
 *
 * <p>Takes names rather than {@code Material}s so it is testable without a
 * server, and so a disc constant the compiling API does not have yet costs
 * nothing.
 */
public final class DiscSongs {

    private static final String PREFIX = "MUSIC_DISC_";

    private DiscSongs() {
    }

    /**
     * The sound a vanilla disc plays, from its material's name.
     *
     * @return empty for anything that is not a {@code MUSIC_DISC_}
     */
    public static Optional<String> ofMaterial(String material) {
        if (material == null || !material.startsWith(PREFIX) || material.length() == PREFIX.length()) {
            return Optional.empty();
        }
        return Optional.of("minecraft:music_disc." + material.substring(PREFIX.length()).toLowerCase(Locale.ROOT));
    }

    /**
     * The sound a jukebox song plays, from the song's key, for the songs whose
     * sound can be named without asking the game.
     *
     * <p>Only vanilla's: the song registry holds the sound event, and Bukkit's
     * API does not hand it out. A datapack song's sound is whatever the
     * datapack said, so it is answered empty here and the caller looks
     * elsewhere (this server's own sounds) or says it cannot play it.
     */
    public static Optional<String> ofSong(String namespace, String path) {
        if (!"minecraft".equals(namespace) || path == null || path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("minecraft:music_disc." + path);
    }

    /** Whether a material's name is a vanilla music disc. */
    public static boolean isDisc(String material) {
        return ofMaterial(material).isPresent();
    }
}
