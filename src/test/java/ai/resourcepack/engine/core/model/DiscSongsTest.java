package ai.resourcepack.engine.core.model;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A disc's sound is derived from its name rather than looked up, so the names
 * that look most like they would break the rule are the ones pinned here.
 */
class DiscSongsTest {

    @Test
    void aDiscPlaysTheSoundItIsNamedAfter() {
        assertEquals(Optional.of("minecraft:music_disc.cat"), DiscSongs.ofMaterial("MUSIC_DISC_CAT"));
        assertEquals(Optional.of("minecraft:music_disc.pigstep"), DiscSongs.ofMaterial("MUSIC_DISC_PIGSTEP"));
    }

    @Test
    void theNumberedDiscsFollowTheSameRule() {
        assertEquals(Optional.of("minecraft:music_disc.5"), DiscSongs.ofMaterial("MUSIC_DISC_5"));
        assertEquals(Optional.of("minecraft:music_disc.11"), DiscSongs.ofMaterial("MUSIC_DISC_11"));
        assertEquals(Optional.of("minecraft:music_disc.13"), DiscSongs.ofMaterial("MUSIC_DISC_13"));
    }

    @Test
    void aTwoWordDiscKeepsItsUnderscore() {
        assertEquals(Optional.of("minecraft:music_disc.creator_music_box"),
                DiscSongs.ofMaterial("MUSIC_DISC_CREATOR_MUSIC_BOX"));
    }

    @Test
    void everyDiscTheCompilingApiKnowsIsADisc() {
        // Whatever the API has, the rule covers — which is the argument for a
        // rule over a table.
        int discs = 0;
        for (Material material : Material.values()) {
            if (material.name().startsWith("MUSIC_DISC_")) {
                assertTrue(DiscSongs.isDisc(material.name()), material.name());
                discs++;
            }
        }
        assertTrue(discs >= 19, "found " + discs);
    }

    @Test
    void otherThingsAreNotDiscs() {
        assertTrue(DiscSongs.ofMaterial("DISC_FRAGMENT_5").isEmpty());
        assertTrue(DiscSongs.ofMaterial("JUKEBOX").isEmpty());
        assertTrue(DiscSongs.ofMaterial("MUSIC_DISC_").isEmpty());
        assertFalse(DiscSongs.isDisc(null));
    }

    @Test
    void aVanillaSongIsTheDiscOfTheSameName() {
        assertEquals(Optional.of("minecraft:music_disc.13"), DiscSongs.ofSong("minecraft", "13"));
        assertEquals(Optional.of("minecraft:music_disc.creator_music_box"),
                DiscSongs.ofSong("minecraft", "creator_music_box"));
    }

    @Test
    void aDatapackSongCannotBeNamedFromItsKey() {
        // Its sound is whatever the datapack said, which the API does not say.
        assertTrue(DiscSongs.ofSong("mypack", "anthem").isEmpty());
    }
}
