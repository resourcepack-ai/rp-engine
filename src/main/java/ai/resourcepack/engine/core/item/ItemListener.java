package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.event.ItemUseEvent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Turns a click with a custom item into an event, and enforces the permission
 * a pack put on it.
 *
 * <p>Runs at {@link EventPriority#LOW} on purpose. A cancelled interact is
 * skipped by everything after it, and the model placement listener runs at
 * normal priority with {@code ignoreCancelled} — so a listener that cancels an
 * {@link ItemUseEvent} stops the item being placed as well, rather than having
 * its refusal quietly overruled by the next handler along.
 */
public final class ItemListener implements Listener {

    private final Items items;

    public ItemListener(Items items) {
        this.items = items;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        // Main hand only. Bukkit fires this once per hand, and firing our own
        // event twice for one click would have every listener guarding against
        // it.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack stack = event.getItem();
        Optional<ContentId> id = items.idOf(stack);
        if (id.isEmpty()) {
            return;
        }
        ItemUseEvent.Action action = actionOf(event.getAction());
        if (action == null) {
            return;
        }

        Player player = event.getPlayer();
        Optional<ItemInfo> info = items.info(id.get());
        Optional<String> permission = info.flatMap(ItemInfo::permission);
        if (permission.isPresent() && !player.hasPermission(permission.get())) {
            // Cancelled as well as refused: an item that is a bucket
            // underneath would otherwise still fill with water while telling
            // somebody they may not use it.
            event.setCancelled(true);
            player.sendMessage(ChatColor.GRAY + "You cannot use that.");
            return;
        }

        ItemUseEvent used = new ItemUseEvent(player, id.get(), stack, action, event.getClickedBlock());
        player.getServer().getPluginManager().callEvent(used);
        if (used.isCancelled()) {
            event.setCancelled(true);
        }
    }

    private static ItemUseEvent.Action actionOf(Action action) {
        switch (action) {
            case RIGHT_CLICK_AIR:
                return ItemUseEvent.Action.RIGHT_CLICK;
            case RIGHT_CLICK_BLOCK:
                return ItemUseEvent.Action.RIGHT_CLICK_BLOCK;
            case LEFT_CLICK_AIR:
                return ItemUseEvent.Action.LEFT_CLICK;
            case LEFT_CLICK_BLOCK:
                return ItemUseEvent.Action.LEFT_CLICK_BLOCK;
            default:
                // PHYSICAL is stepping on a pressure plate, which is not a use
                // of whatever happens to be in your hand.
                return null;
        }
    }
}
