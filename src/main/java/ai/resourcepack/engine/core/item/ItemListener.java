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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final org.bukkit.plugin.Plugin plugin;

    /**
     * What each dead player is owed, until they are standing up again.
     *
     * <p>In memory: a server restart between somebody dying and respawning is
     * a case where they have not lost anything anyway, since the world was
     * saved before they died.
     */
    private final Map<UUID, List<ItemStack>> keeping = new ConcurrentHashMap<>();

    public ItemListener(org.bukkit.plugin.Plugin plugin, Items items, ActionRunner actions) {
        this.plugin = plugin;
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
        if (rightClick && info.map(ItemInfo::hat).orElse(false) && wear(player, stack)) {
            event.setCancelled(true);
            return;
        }
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
        // Somebody who logs out on the respawn screen: their kept items went
        // with the death, and holding them for a player who may never come
        // back is the same growing map.
        keeping.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Puts an item on somebody's head, if their head is free.
     *
     * <p>Vanilla already lets anybody wear anything by dragging it into the
     * helmet slot; this is the click that saves the drag, and nothing more.
     * A head that is already wearing something is left alone rather than
     * swapped: a click that silently takes your helmet off in a fight is a
     * worse outcome than a click that does nothing.
     *
     * @return whether it went on
     */
    private static boolean wear(Player player, ItemStack stack) {
        ItemStack worn = player.getInventory().getHelmet();
        if (worn != null && worn.getType() != org.bukkit.Material.AIR) {
            return false;
        }
        ItemStack one = stack.clone();
        one.setAmount(1);
        player.getInventory().setHelmet(one);
        stack.setAmount(stack.getAmount() - 1);
        return true;
    }

    /**
     * Items that survive dying.
     *
     * <p>Taken out of the drops and given straight back, which is the only
     * way to do it: vanilla has no per-item keep flag, and keepInventory is
     * all or nothing for the whole server.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }
        List<ItemStack> kept = new ArrayList<>();
        event.getDrops().removeIf(dropped -> {
            boolean keep = items.idOf(dropped)
                    .flatMap(items::info)
                    .map(ItemInfo::keepOnDeath)
                    .orElse(false);
            if (keep) {
                kept.add(dropped);
            }
            return keep;
        });
        if (kept.isEmpty()) {
            return;
        }
        // Held until they RESPAWN, not handed back a tick later. The
        // inventory is cleared as part of dying and the player is sitting on
        // a respawn screen after it, so anything put in it before they are
        // back in the world is racing something that will win.
        keeping.put(event.getEntity().getUniqueId(), kept);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        List<ItemStack> kept = keeping.remove(event.getPlayer().getUniqueId());
        if (kept == null) {
            return;
        }
        Player player = event.getPlayer();
        // One tick later even here: the respawn event fires before the player
        // is fully in the world, and an inventory change made during it does
        // not always reach the client.
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            for (ItemStack one : kept) {
                player.getInventory().addItem(one).values()
                        .forEach(over -> player.getWorld().dropItemNaturally(player.getLocation(), over));
            }
        });
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
