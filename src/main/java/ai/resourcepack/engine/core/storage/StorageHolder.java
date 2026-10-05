package ai.resourcepack.engine.core.storage;

import org.bukkit.inventory.ItemStack;

import java.io.IOException;

/**
 * Where a container's contents live between openings.
 *
 * <p><strong>The seam that keeps {@link Storages} from knowing what it is
 * storing FOR.</strong> A placed model keeps its chest on its hitbox entity; a
 * custom block will keep its own in the chunk, keyed by position; a personal
 * wardrobe keeps it on the player. Opening, sharing, saving on close and
 * spilling on removal are the same for all of them, so they are written once
 * and each holder only answers where the bytes go. {@link PdcStorage} is the
 * one every case so far needs.
 *
 * <p>Main thread only, like everything that touches an inventory.
 */
public interface StorageHolder {

    /**
     * Who this is, for sharing.
     *
     * <p>Two opens with the same key are handed the same live inventory, which
     * is what makes two players looking into one chest see each other's moves
     * rather than two copies racing to be saved last — the duplication bug
     * every chest plugin has had once. So it must be stable for as long as the
     * thing exists and unique across everything that can be open at once: an
     * entity's uuid, a block's world and position.
     */
    String key();

    /**
     * The contents as last saved, or an empty array for a container nothing
     * has been put in.
     *
     * @throws IOException when what is stored cannot be read. The container is
     *                     then not opened at all — see {@link ItemBytes} for why
     *                     that is never the same as empty
     */
    ItemStack[] load() throws IOException;

    /** Keeps {@code contents}, replacing whatever was kept before. */
    void save(ItemStack[] contents);

    /**
     * The thing this was part of is gone, and its contents have been handed
     * back; forget them, so nothing can be opened or spilled twice.
     */
    void onRemoved();
}
