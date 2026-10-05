package ai.resourcepack.engine.core.storage;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Contents kept as bytes under one key of a persistent-data container.
 *
 * <p>Works for anything whose container is LIVE — an entity, a chunk, a
 * player — because those write through as they are changed and are saved with
 * the thing they belong to. Not for an item stack, whose container is a copy
 * that only counts once it is put back on the stack; a stack's contents are
 * moved by hand instead, see {@link Storages#keepInside}.
 *
 * <p><strong>Nothing is kept for an empty container.</strong> The key is
 * removed rather than written as an array of nulls, so a chest somebody opened
 * once and left empty costs the world nothing.
 */
public final class PdcStorage implements StorageHolder {

    private final String key;
    private final PersistentDataContainer data;
    private final NamespacedKey dataKey;
    private final Logger logger;

    /**
     * @param key     the sharing identity; see {@link StorageHolder#key()}
     * @param data    a live container: an entity's, a chunk's or a player's
     * @param dataKey the key the bytes go under
     * @param logger  where a save that could not be written is reported
     */
    public PdcStorage(String key, PersistentDataContainer data, NamespacedKey dataKey, Logger logger) {
        this.key = key;
        this.data = data;
        this.dataKey = dataKey;
        this.logger = logger;
    }

    /**
     * The storage for a custom block: its chunk's container, one key per block.
     *
     * <p>Chunk data rather than a file of our own for the reason a placed model
     * is an entity rather than a row somewhere: the chunk is saved with the
     * blocks it holds, so the contents cannot drift out of step with the world
     * — a rolled-back region rolls the chest back with it, and a deleted one
     * takes it along.
     *
     * <p>The key carries the position in block coordinates, which are unique
     * within a chunk's world; the chunk itself is what makes them unique across
     * worlds.
     */
    public static PdcStorage forBlock(Plugin plugin, Block block) {
        String position = block.getX() + "_" + block.getY() + "_" + block.getZ();
        NamespacedKey dataKey = new NamespacedKey(plugin, "block-storage/" + position.toLowerCase(Locale.ROOT));
        return new PdcStorage(block.getWorld().getUID() + "/" + position,
                block.getChunk().getPersistentDataContainer(), dataKey, plugin.getLogger());
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public ItemStack[] load() throws IOException {
        return ItemBytes.read(data.get(dataKey, PersistentDataType.BYTE_ARRAY));
    }

    @Override
    public void save(ItemStack[] contents) {
        if (!ItemBytes.anything(contents)) {
            data.remove(dataKey);
            return;
        }
        try {
            data.set(dataKey, PersistentDataType.BYTE_ARRAY, ItemBytes.write(contents));
        } catch (IOException | RuntimeException e) {
            // Loud, because the alternative is silence about somebody's items.
            // What was stored before stays stored: a failed write changes
            // nothing rather than writing half of something.
            if (logger != null) {
                logger.severe("Could not save the contents of " + key + ": " + e.getMessage()
                        + ". The last saved contents are kept.");
            }
        }
    }

    @Override
    public void onRemoved() {
        data.remove(dataKey);
    }

    /** The bytes as stored, unread, for keeping somewhere when they cannot be read. */
    byte[] raw() {
        return data.get(dataKey, PersistentDataType.BYTE_ARRAY);
    }
}
