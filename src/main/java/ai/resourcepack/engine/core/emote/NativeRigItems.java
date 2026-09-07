package ai.resourcepack.engine.core.emote;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The item a native rig's bone is worn as.
 *
 * <p>A Studio rig's bone is paper carrying a {@code custom_model_data} string
 * that vanilla paper's own model file, shipped in the pushed pack, dispatches
 * on. A native rig cannot use that file — a pushed pack may already own it
 * and two packs cannot both — so its bone is paper with an {@code item_model}
 * component naming the model directly, in the {@code rpengine} namespace
 * where {@link RigBaker} wrote it.
 *
 * <p>Its own class, and in {@code version-arms.txt}, because
 * {@code setItemModel} does not exist before 1.21.4. A class that names a
 * method the server may not have is only ever loaded once something has
 * checked the server has it: nothing constructs a native rig on an older
 * server ({@link RigAssets#bake} bakes nothing there), so
 * {@link EmoteStore#rigFor} never answers with one, so this is never reached.
 * The same class-boundary rule {@code DefinitionsWiring} follows, for the same
 * method.
 */
final class NativeRigItems {

    private NativeRigItems() {
    }

    /** Whether a bone item id names a rig baked here rather than one Studio pushed. */
    static boolean isNative(String modelId) {
        return modelId != null && modelId.startsWith(RigBaker.PREFIX);
    }

    static ItemStack item(String modelId) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setItemModel(new NamespacedKey(RigBaker.NAMESPACE, modelId));
            item.setItemMeta(meta);
        }
        return item;
    }
}
