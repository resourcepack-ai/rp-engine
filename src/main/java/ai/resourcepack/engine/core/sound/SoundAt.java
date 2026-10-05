package ai.resourcepack.engine.core.sound;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Sounds;
import org.bukkit.Location;
import org.bukkit.SoundCategory;

import java.util.Optional;

/**
 * Plays a sound named the way a content file names one, at a place.
 *
 * <p>The same rule as an action's {@code sound} step: one of this server's own
 * sounds if the id is one, otherwise a vanilla key played by its string. By the
 * string and not through {@code Registry.SOUNDS}, because a key from the wiki
 * is what an author has in front of them, the enum constants are renamed
 * between versions, and a sound event a resource pack added is not in the
 * registry at all.
 */
public final class SoundAt {

    private SoundAt() {
    }

    /**
     * @param key a sound id or vanilla key; null or blank plays nothing
     */
    public static void play(Sounds sounds, Location at, String key, SoundCategory category,
                            float volume, float pitch) {
        if (at == null || at.getWorld() == null || key == null || key.isBlank()) {
            return;
        }
        Optional<ContentId> id = ContentId.parse(key);
        if (sounds != null && id.isPresent() && sounds.info(id.get()).isPresent()) {
            sounds.playAt(at, id.get(), volume, pitch);
            return;
        }
        at.getWorld().playSound(at, key, category, volume, pitch);
    }
}
