package ai.resourcepack.engine.core.sound;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.api.Sounds;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Plays custom sounds. Internal.
 *
 * <p>Thin, like every Bukkit-facing class here. Bukkit already takes a sound
 * event by name, so this is a lookup, a category translation and a call.
 */
public final class SoundsImpl implements Sounds {

    private volatile Map<ContentId, SoundInfo> sounds = Map.of();

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, SoundInfo> loaded) {
        this.sounds = loaded == null ? Map.of() : Map.copyOf(loaded);
    }

    @Override
    public Collection<ContentId> ids() {
        List<ContentId> sorted = new ArrayList<>(sounds.keySet());
        sorted.sort(ContentId::compareTo);
        return List.copyOf(sorted);
    }

    @Override
    public Optional<SoundInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(sounds.get(id));
    }

    @Override
    public Optional<SoundInfo> info(String id) {
        return ContentId.parse(id).flatMap(this::info);
    }

    @Override
    public boolean play(Player player, ContentId id) {
        Optional<SoundInfo> sound = info(id);
        return sound.isPresent() && play(player, id, sound.get().volume(), sound.get().pitch());
    }

    @Override
    public boolean play(Player player, ContentId id, float volume, float pitch) {
        Optional<SoundInfo> sound = info(id);
        if (player == null || sound.isEmpty()) {
            return false;
        }
        // At the player's own position: this is the "only you hear it" call,
        // and giving it a place somebody else could stand near would make it a
        // different method wearing this one's name.
        player.playSound(player.getLocation(), sound.get().event(), categoryOf(sound.get()), volume, pitch);
        return true;
    }

    @Override
    public boolean playAt(Location location, ContentId id) {
        Optional<SoundInfo> sound = info(id);
        return sound.isPresent() && playAt(location, id, sound.get().volume(), sound.get().pitch());
    }

    @Override
    public boolean playAt(Location location, ContentId id, float volume, float pitch) {
        Optional<SoundInfo> sound = info(id);
        if (location == null || location.getWorld() == null || sound.isEmpty()) {
            return false;
        }
        location.getWorld().playSound(location, sound.get().event(), categoryOf(sound.get()), volume, pitch);
        return true;
    }

    /**
     * Minecraft's category names and Bukkit's enum are the same set spelled
     * differently, and {@code sounds.json} has to use Minecraft's. This is the
     * one place the two meet.
     */
    private static SoundCategory categoryOf(SoundInfo sound) {
        try {
            return SoundCategory.valueOf(sound.category().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SoundCategory.MASTER;
        }
    }
}
