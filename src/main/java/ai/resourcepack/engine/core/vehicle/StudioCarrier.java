package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.core.model.RigTags;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * A stack that renders a Studio model.
 *
 * <p>A pack Studio builds is a zip with no plugin behind it, so it cannot give
 * anybody a custom item — every model in it borrows a vanilla one instead:
 * paper, dispatched by a {@code minecraft:select} on a string
 * {@code custom_model_data}. That string is the model's own id, and it is the
 * only handle a pushed model has.
 *
 * <p>A pack built HERE has a plugin behind it and borrows nothing, which is
 * why an authored vehicle names an item id instead. Both arrive at an
 * {@link ItemStack}; only the naming differs.
 *
 * <p>{@code RigPlacementListener} builds the same stack for a rig's parts, and
 * this is deliberately not a call into it: that class is a listener holding
 * the placement rules, and a vehicle wants the one line of it that is about
 * how art is named. If a third caller turns up, the two should become one —
 * but not by making a listener's internals public.
 */
final class StudioCarrier {

    private StudioCarrier() {
    }

    /**
     * Paper wearing {@code modelId}.
     *
     * <p>{@code tags} is passed in rather than read off a static, because
     * WHERE a string {@code custom_model_data} lives is a version fork
     * ({@code Feature.ITEM_STRING_TAGS}) and resolving it is the plugin's job,
     * done once at startup. This class only knows that the string goes on.
     */
    static ItemStack item(RigTags tags, String modelId) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            tags.write(meta, List.of(modelId));
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
