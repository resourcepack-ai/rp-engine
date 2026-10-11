package ai.resourcepack.engine.core.armor3d;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 1.21.4 and up: the {@code item_model} an authored set's art is drawn by.
 *
 * <p>Its own class, and in {@code version-arms.txt}, because
 * {@code setItemModel} does not exist before 1.21.4. {@link Armor3dItems}
 * reaches it only through a method reference it builds after asking
 * {@code Compatibility}, so a server without the method never loads it. The
 * same class-boundary rule {@code NativeRigItems} follows, for the same method.
 */
final class ArmourItemModels {

    private ArmourItemModels() {
    }

    static void name(ItemMeta meta, String itemModel) {
        NamespacedKey key = NamespacedKey.fromString(itemModel);
        if (key != null) {
            meta.setItemModel(key);
        }
    }
}
