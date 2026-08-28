package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemAction;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.event.ItemUseEvent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
    private final ActionRunner actions;

    public ItemListener(Items items, ActionRunner actions) {
        this.items = items;
        this.actions = actions;
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
            // A listener that refused this use has refused the whole use, so
            // the item's own actions do not run either. The event is the
            // stronger statement of the two, which is the point of it.
            return;
        }

        boolean rightClick = action == ItemUseEvent.Action.RIGHT_CLICK
                || action == ItemUseEvent.Action.RIGHT_CLICK_BLOCK;
        if (actions.run(player, id.get(),
                rightClick ? ItemAction.Trigger.RIGHT_CLICK : ItemAction.Trigger.LEFT_CLICK, stack)) {
            event.setCancelled(true);
        }
    }

    /**
     * The other four triggers.
     *
     * <p>They have no {@link ItemUseEvent} of their own and are deliberately
     * not being given one: that event is about USING an item, and dropping or
     * eating one is not a use. A plugin that wants these has Bukkit's own
     * events for them, which are the ones this reads.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getDamager();
        ItemStack held = player.getInventory().getItemInMainHand();
        items.idOf(held).ifPresent(id -> {
            if (actions.run(player, id, ItemAction.Trigger.ATTACK, held)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        items.idOf(dropped).ifPresent(id -> {
            if (actions.run(event.getPlayer(), id, ItemAction.Trigger.DROP, dropped)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack eaten = event.getItem();
        items.idOf(eaten).ifPresent(id -> {
            if (actions.run(event.getPlayer(), id, ItemAction.Trigger.CONSUME, eaten)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        items.idOf(held).ifPresent(id -> {
            if (actions.run(event.getPlayer(), id, ItemAction.Trigger.BLOCK_BREAK, held)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        ItemStack bow = event.getBow();
        items.idOf(bow).ifPresent(id -> {
            if (actions.run((Player) event.getEntity(), id, ItemAction.Trigger.SHOOT, bow)) {
                event.setCancelled(true);
            }
        });
    }

    /**
     * The item has already broken by the time this fires and the event cannot
     * be cancelled, so a {@code cancel} step on this trigger does nothing.
     * That is vanilla's shape rather than ours; the trigger is still worth
     * having, for the sound and the message.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onItemBreak(PlayerItemBreakEvent event) {
        ItemStack broken = event.getBrokenItem();
        items.idOf(broken).ifPresent(id ->
                actions.run(event.getPlayer(), id, ItemAction.Trigger.BREAK, broken));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        ItemStack picked = event.getItem().getItemStack();
        items.idOf(picked).ifPresent(id -> {
            if (actions.run((Player) event.getEntity(), id, ItemAction.Trigger.PICKUP, picked)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Cooldowns are seconds long and live in memory. Keeping them for
        // somebody who has gone is a map that only grows.
        actions.forget(event.getPlayer().getUniqueId());
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
