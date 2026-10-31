package ai.resourcepack.engine.core.entity;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;

/**
 * The two things a custom mob does to an item that need 1.21.4: tint a worn
 * part red, and hold a bow nobody can see.
 *
 * <p>Its own class because both are 1.21.4 API ({@code custom_model_data}'s
 * colour list, the {@code item_model} component), and it is only made on a
 * server that has them, so an older one never loads it. Listed in
 * {@code version-arms.txt}.
 */
final class MobItems {

    /**
     * {@code stack} with its first {@code custom_model_data} colour set to
     * {@code rgb}, or cleared when {@code rgb} is negative. Everything else on
     * the stack, the rig's string tags included, is kept.
     */
    ItemStack tint(ItemStack stack, int rgb) {
        if (stack == null) {
            return null;
        }
        ItemStack out = stack.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta == null) {
            return out;
        }
        CustomModelDataComponent data = meta.getCustomModelDataComponent();
        data.setColors(rgb < 0 ? List.of() : List.of(Color.fromRGB(rgb & 0xFFFFFF)));
        meta.setCustomModelDataComponent(data);
        out.setItemMeta(meta);
        return out;
    }

    /**
     * A bow that draws as nothing: vanilla's own {@code minecraft:air} item
     * model, which every 1.21.4+ client has and which has no faces. A skeleton
     * aims and keeps its distance only while it holds a bow, and an invisible
     * mob still draws what it holds, so this is what lets a skeleton's brain
     * shoot from inside a model without a bow floating in front of it.
     */
    ItemStack hiddenBow() {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        if (meta != null) {
            meta.setItemModel(NamespacedKey.minecraft("air"));
            bow.setItemMeta(meta);
        }
        return bow;
    }
}
