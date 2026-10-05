package ai.resourcepack.engine.core.dialog;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 1.21.4 and up: the first string of a stack's {@code custom_model_data} — what
 * a Studio item handed out as itself carries as its name (its model id), and
 * what a dialog's icon map is keyed by. Internal.
 *
 * <p>A version arm (see {@code version-arms.txt}): it names an API the engine's
 * floor does not have, and is only reached while filling a dialog, which needs
 * 1.21.6. {@link DialogItems} still calls it inside a catch, so a server that
 * somehow got here without it draws the "no picture" icon rather than failing
 * the dialog.
 */
final class DialogItemModels {

    private DialogItemModels() {
    }

    /** The first {@code custom_model_data} string, or null for none. */
    static String firstString(ItemStack stack) {
        ItemMeta meta = stack == null ? null : stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        List<String> strings = meta.getCustomModelDataComponent().getStrings();
        return strings == null || strings.isEmpty() ? null : strings.get(0);
    }
}
