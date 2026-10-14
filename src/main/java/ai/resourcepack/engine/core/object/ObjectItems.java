package ai.resourcepack.engine.core.object;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;

/**
 * The item an object's display shows: paper wearing the object's item model,
 * tinted with its controls.
 *
 * <p>Its own class because both calls are 1.21.4 API; it is only made on a
 * server that has them, so an older one never loads it.
 */
public final class ObjectItems {

    /** The item for {@code model} (e.g. {@code rpai:shader3d_0}) carrying the tint {@code color}. */
    public ItemStack item(String model, int color) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        NamespacedKey key = NamespacedKey.fromString(model);
        if (key != null) {
            meta.setItemModel(key);
        }
        CustomModelDataComponent data = meta.getCustomModelDataComponent();
        data.setColors(List.of(Color.fromRGB(color & 0xFFFFFF)));
        meta.setCustomModelDataComponent(data);
        item.setItemMeta(meta);
        return item;
    }
}
