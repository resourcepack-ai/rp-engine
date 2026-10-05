package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.core.storage.ItemBytes;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * A placed piece that plays music discs.
 *
 * <p>A display entity cannot be a jukebox block, so this is the same trick as
 * the barrier and the light: the disc is kept on the piece's hitbox, in
 * persistent data, and the record is played AT the piece as a sound in the
 * records category — which is what a vanilla jukebox does under the hood, and
 * why the music slider still governs it.
 *
 * <p><strong>Volume 1 is a vanilla jukebox</strong>, which the game plays at
 * four times a normal sound's volume so that it carries about sixty-four
 * blocks. So {@code volume:} multiplies that, rather than starting from a
 * sound that would die out at sixteen.
 *
 * <p>The sound is sent once, when the disc goes in, like vanilla's. A player
 * who arrives afterwards does not hear it start mid-song, and a restart does
 * not resume it — the disc is still in the piece, and taking it out and putting
 * it back starts it again.
 *
 * <p>Main thread only.
 */
final class PieceJukebox {

    /** How far a vanilla jukebox is heard, at volume 1. */
    private static final double RANGE = 64.0;

    /** The game's own jukebox volume; see the class note. */
    private static final float VANILLA_VOLUME = 4f;

    private final Discs discs;
    private final NamespacedKey discKey;
    private final Logger logger;

    PieceJukebox(Plugin plugin, Discs discs) {
        this.discs = discs;
        this.discKey = new NamespacedKey(plugin, "model-disc");
        this.logger = plugin.getLogger();
    }

    /** Whether a disc is in this piece. */
    boolean holding(Interaction hitbox) {
        return hitbox.getPersistentDataContainer().has(discKey, PersistentDataType.BYTE_ARRAY);
    }

    /**
     * A right-click on the piece.
     *
     * @return whether the jukebox used the click. A click with no disc in it
     *         and none in hand is not a jukebox click, and falls through to
     *         whatever the piece does next — so a gramophone that is also a
     *         seat can still be sat on
     */
    boolean click(Player player, Interaction hitbox, ModelInfo.Jukebox jukebox) {
        if (jukebox.permission().isPresent() && !player.hasPermission(jukebox.permission().get())) {
            return false;
        }
        if (holding(hitbox)) {
            eject(hitbox, jukebox.volume());
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<String> song = discs.songOf(held);
        if (song.isEmpty()) {
            return false;
        }
        ItemStack disc = held.clone();
        disc.setAmount(1);
        try {
            hitbox.getPersistentDataContainer().set(discKey, PersistentDataType.BYTE_ARRAY,
                    ItemBytes.write(new ItemStack[]{disc}));
        } catch (IOException | RuntimeException e) {
            // Not taken from the hand if it was not stored: a disc that is in
            // neither place is a disc deleted.
            logger.warning("Could not put a disc in " + hitbox.getUniqueId() + ": " + e.getMessage());
            return true;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            // Through the inventory rather than by shrinking `held`, which is
            // a mirror on some servers and a copy on others.
            if (held.getAmount() > 1) {
                held.setAmount(held.getAmount() - 1);
                player.getInventory().setItemInMainHand(held);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        }
        player.swingMainHand();
        if (!song.get().isEmpty()) {
            Location at = centre(hitbox);
            at.getWorld().playSound(at, song.get(), SoundCategory.RECORDS,
                    VANILLA_VOLUME * jukebox.volume(), jukebox.pitch());
        }
        return true;
    }

    /**
     * Takes the disc out: it pops out of the top and the music stops for
     * everybody who could hear it. Called on a click, and when the piece is
     * broken — a disc is never broken with its jukebox.
     *
     * @param volume the jukebox's own volume, which is how far its sound reached
     */
    void eject(Interaction hitbox, float volume) {
        byte[] stored = hitbox.getPersistentDataContainer().get(discKey, PersistentDataType.BYTE_ARRAY);
        if (stored == null) {
            return;
        }
        ItemStack[] read;
        try {
            read = ItemBytes.read(stored);
        } catch (IOException e) {
            // Left where it is: refusing to eject is recoverable, a disc
            // dropped as nothing is not.
            logger.severe("The disc in " + hitbox.getUniqueId() + " could not be read and was left in it: "
                    + e.getMessage());
            return;
        }
        hitbox.getPersistentDataContainer().remove(discKey);
        ItemStack disc = read.length > 0 ? read[0] : null;
        if (disc == null) {
            return;
        }
        stop(hitbox, disc, volume);
        World world = hitbox.getWorld();
        Location top = hitbox.getLocation().add(0, Math.max(0.5, hitbox.getInteractionHeight()) + 0.1, 0);
        Item dropped = world.dropItem(top, disc);
        dropped.setVelocity(new Vector(0, 0.15, 0));
    }

    private void stop(Interaction hitbox, ItemStack disc, float volume) {
        Optional<String> song = discs.songOf(disc);
        if (song.isEmpty() || song.get().isEmpty()) {
            return;
        }
        Location at = centre(hitbox);
        double reach = RANGE * Math.max(1f, volume);
        double reachSquared = reach * reach;
        for (Player player : at.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(at) <= reachSquared) {
                player.stopSound(song.get(), SoundCategory.RECORDS);
            }
        }
    }

    private static Location centre(Interaction hitbox) {
        return hitbox.getLocation().add(0, 0.5, 0);
    }
}
