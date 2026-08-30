package ai.resourcepack.engine.core.model;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Rig tags in the stack's persistent data, which every supported version has.
 *
 * <p>The arm used below 1.21.4, and the fallback the newer arm reads through.
 * Persistent data has been on item stacks since 1.14, so there is no version
 * question here at all.
 *
 * <p>One string with newlines between the tags rather than a list type,
 * because the tags are short, the count is two or three, and a newline cannot
 * occur in any of them — a part id is a resource location and the markers are
 * a prefix plus a name or a number.
 */
final class PersistentRigTags implements RigTags {

    /** Cannot appear in a resource location, an animation name or a number. */
    private static final String SEPARATOR = "\n";

    private final NamespacedKey key;

    PersistentRigTags(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "rig-tags");
    }

    @Override
    public List<String> read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Collections.emptyList();
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? Collections.emptyList() : readMeta(meta);
    }

    /** Split out so the string arm can fall back without re-fetching the meta. */
    List<String> readMeta(ItemMeta meta) {
        String joined = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (joined == null || joined.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(Arrays.asList(joined.split(SEPARATOR, -1)));
    }

    @Override
    public void write(ItemMeta meta, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            meta.getPersistentDataContainer().remove(key);
            return;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                String.join(SEPARATOR, tags));
    }
}
