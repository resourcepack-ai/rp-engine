package ai.resourcepack.engine.core.recipe;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.Items;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts an item made of parts together out of what was crafted into it.
 *
 * <p>A recipe names its result once, and the parts it is made of are whatever
 * the player laid in the grid: each ingredient that is one of a slot's options
 * fills that slot. So one recipe with {@code a | b} ingredients makes every
 * combination, and each comes out looking like what went in.
 *
 * <p>After the vanilla guard, which may have emptied the result.
 */
final class PartAssembly implements Listener {

    private final Items items;

    PartAssembly(Items items) {
        this.items = items;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        ItemInfo made = items.idOf(result).flatMap(items::info).orElse(null);
        if (made == null || made.parts().isEmpty()) {
            return;
        }
        Map<String, ContentId> filled = new LinkedHashMap<>();
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            ContentId part = ingredient == null ? null : items.idOf(ingredient).orElse(null);
            if (part == null) {
                continue;
            }
            for (Map.Entry<String, List<ContentId>> slot : made.parts().entrySet()) {
                if (!filled.containsKey(slot.getKey()) && slot.getValue().contains(part)) {
                    filled.put(slot.getKey(), part);
                    break;
                }
            }
        }
        if (filled.isEmpty()) {
            return;
        }
        items.withParts(result, filled).ifPresent(assembled -> event.getInventory().setResult(assembled));
    }
}
