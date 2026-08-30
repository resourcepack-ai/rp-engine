package ai.resourcepack.engine.core.model;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.Collections;
import java.util.List;

/**
 * 1.21.4 and up: rig tags in {@code custom_model_data}'s string list.
 *
 * <p>Written here rather than in persistent data for one reason — a person can
 * type it. Studio's panel produces a rig part with a {@code /give} command and
 * no plugin call, and that only works while the tags live somewhere a command
 * can put them.
 *
 * <p>Reads fall through to persistent data when there are no strings, so part
 * items placed by an older build of the engine on an older server keep working
 * after an upgrade.
 */
final class StringRigTags implements RigTags {

    private final PersistentRigTags fallback;

    StringRigTags(PersistentRigTags fallback) {
        this.fallback = fallback;
    }

    @Override
    public List<String> read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Collections.emptyList();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Collections.emptyList();
        }
        List<String> strings = meta.getCustomModelDataComponent().getStrings();
        return strings.isEmpty() ? fallback.readMeta(meta) : strings;
    }

    @Override
    public void write(ItemMeta meta, List<String> tags) {
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setStrings(tags);
        meta.setCustomModelDataComponent(component);
    }
}
