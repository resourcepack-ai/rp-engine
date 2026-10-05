package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Sounds;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.JukeboxPlayableComponent;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 1.21 and up: any item carrying a {@code jukebox_playable} component is a
 * disc, and the song it names is what it plays.
 *
 * <p>Listed in {@code version-arms.txt}, and constructed only by
 * {@link Discs#forServer} after the server said it has the component.
 *
 * <p><strong>A datapack's song cannot be played by name</strong>, and that is
 * Bukkit's limit rather than a choice: the song registry holds the sound event
 * a song plays, and the API hands out the song's key and nothing more. Vanilla's
 * songs follow a naming rule and are derived ({@link DiscSongs#ofSong}); a song
 * that shares its id with one of this server's own sounds plays that sound;
 * anything else goes into the jukebox and plays nothing, and the console is told
 * once per song so the silence has an explanation.
 */
final class ComponentDiscs implements Discs {

    private final Sounds sounds;
    private final Logger logger;
    private final Set<String> warned = new HashSet<>();

    ComponentDiscs(Sounds sounds, Logger logger) {
        this.sounds = sounds;
        this.logger = logger;
    }

    @Override
    public Optional<String> songOf(ItemStack stack) {
        if (stack == null) {
            return Optional.empty();
        }
        NamespacedKey song = null;
        try {
            ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
            if (meta != null && meta.hasJukeboxPlayable()) {
                JukeboxPlayableComponent component = meta.getJukeboxPlayable();
                song = component == null ? null : component.getSongKey();
            }
        } catch (RuntimeException | LinkageError e) {
            // A server between 1.21 and the API this was compiled against may
            // spell the component differently. Falling back to the material
            // still plays every vanilla disc, which is the part that matters.
            song = null;
        }
        if (song == null) {
            return DiscSongs.ofMaterial(stack.getType().name());
        }
        Optional<String> vanilla = DiscSongs.ofSong(song.getNamespace(), song.getKey());
        if (vanilla.isPresent()) {
            return vanilla;
        }
        Optional<ContentId> ours = ContentId.parse(song.toString());
        if (ours.isPresent() && sounds != null && sounds.info(ours.get()).isPresent()) {
            return Optional.of(ours.get().toString());
        }
        if (warned.add(song.toString()) && logger != null) {
            logger.warning("The music disc song " + song + " goes into placed jukeboxes but plays nothing: "
                    + "Bukkit does not say which sound a datapack song plays. Give this server a sound "
                    + "with the id " + song + " and it will play that.");
        }
        return Optional.of("");
    }
}
