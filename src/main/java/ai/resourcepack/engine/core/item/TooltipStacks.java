package ai.resourcepack.engine.core.item;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Puts a Studio tooltip on a stack: 1.21.5 and newer only.
 *
 * <p>Bukkit has no API for most of what a tooltip is (a lore line in another
 * font with no shadow, the hidden lines), so the stack goes through the
 * server's own item parser: the item's components as the server writes them,
 * minus the ones the tooltip sets, then the tooltip's, read back the way
 * {@code /give} reads its argument. The same on Spigot and Paper, and nothing
 * is held about either's internals.
 *
 * <p>A version arm (see {@code version-arms.txt}): both calls are newer than
 * the engine's floor, so this is constructed only after Compatibility says the
 * server has {@code Feature.ITEM_TOOLTIPS}.
 */
final class TooltipStacks {

    /**
     * {@code stack} wearing {@code tooltip}, or null when the server's parser
     * would not take it, with the reason in {@code problem[0]}.
     */
    ItemStack wear(ItemStack stack, ItemTooltip tooltip, String[] problem) {
        ItemMeta meta = stack.getItemMeta();
        String own = meta == null ? "[]" : meta.getAsComponentString();
        String spec = tooltip.itemString(stack.getType().getKey().toString(), own);
        try {
            ItemStack out = Bukkit.getItemFactory().createItemStack(spec);
            out.setAmount(stack.getAmount());
            return out;
        } catch (RuntimeException e) {
            // IllegalArgumentException is what the API promises for an item
            // string it refuses; anything else here is the same answer.
            problem[0] = e.getMessage();
            return null;
        }
    }
}
