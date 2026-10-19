package ai.resourcepack.engine.core.vfx;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;

/**
 * The calls a VFX sprite needs that are newer than the engine's floor: the
 * item model a sprite is drawn through and the custom_model_data float that
 * picks its level (1.21.4), and a display's glide between teleports (1.20.2).
 *
 * <p>Its own class, reached only from a play, which only starts on a server
 * that has {@link ai.resourcepack.engine.api.Feature#VFX}, so an older server
 * never loads it.
 */
final class VfxDisplays {

    private VfxDisplays() {
    }

    /** Paper wearing {@code model}, showing level {@code level}. */
    static ItemStack item(String model, int level) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        NamespacedKey key = NamespacedKey.fromString(model);
        if (key != null) {
            meta.setItemModel(key);
        }
        CustomModelDataComponent data = meta.getCustomModelDataComponent();
        data.setFloats(List.of((float) level));
        meta.setCustomModelDataComponent(data);
        stack.setItemMeta(meta);
        return stack;
    }

    static void glide(Display display, int ticks) {
        display.setTeleportDuration(ticks);
    }
}
