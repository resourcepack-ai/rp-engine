package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.event.ModelBreakEvent;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Optional;

/**
 * Makes placed model model, breakable, and able to survive a restart.
 *
 * <p>A placed piece is two entities. An {@link ItemDisplay} is what you see; an
 * {@link Interaction} is what you can hit, because a display entity cannot be
 * clicked or collided with at all. Both are tagged in persistent data, so a
 * piece is an ordinary chunk-saved entity and needs no file of its own and no
 * loading step — the world already remembers where everything is.
 *
 * <p>Solid placed model also gets a barrier block at its anchor, which is the only
 * way a display entity can stop anybody walking through it.
 *
 * <p>The key is namespaced by the plugin, which is the concrete reason this
 * plugin can never be renamed: every piece standing in somebody's world is
 * keyed to it.
 */
public final class ModelPlacementListener implements Listener {

    private final Plugin plugin;
    private final Items items;
    private final NamespacedKey idKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey solidKey;

    private volatile Map<ContentId, ModelInfo> model = Map.of();

    public ModelPlacementListener(Plugin plugin, Items items) {
        this.plugin = plugin;
        this.items = items;
        this.idKey = new NamespacedKey(plugin, "model");
        this.displayKey = new NamespacedKey(plugin, "model-display");
        this.solidKey = new NamespacedKey(plugin, "model-solid");
    }

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, ModelInfo> loaded) {
        this.model = loaded == null ? Map.of() : Map.copyOf(loaded);
    }

    private Optional<ModelInfo> byItem(ContentId item) {
        for (ModelInfo one : model.values()) {
            if (one.item().equals(item)) {
                return Optional.of(one);
            }
        }
        return Optional.empty();
    }

    // ---- placing -------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack held = event.getItem();
        Optional<ContentId> id = items.idOf(held);
        if (id.isEmpty()) {
            return;
        }
        Optional<ModelInfo> found = byItem(id.get());
        if (found.isEmpty()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        // A chest keeps its vanilla click unless the player sneaks, which is
        // the same rule as placing an ordinary block. Anything else and
        // placed model would make containers unopenable while it is in hand.
        if (clicked.getType().isInteractable() && !event.getPlayer().isSneaking()) {
            return;
        }

        event.setCancelled(true);

        Block target = clicked.getRelative(event.getBlockFace());
        if (!target.getType().isAir() || findAt(target) != null) {
            return;
        }

        Player player = event.getPlayer();
        ModelInfo info = found.get();

        // Everything above is "can this physically go here". Whether it is
        // ALLOWED to is a rule about somebody's server, which we cannot see.
        ModelPlaceEvent ask = new ModelPlaceEvent(player, info.id(), target);
        Bukkit.getPluginManager().callEvent(ask);
        if (ask.isCancelled()) {
            return;
        }

        place(target, info, yawFor(player, info), held);

        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        player.swingMainHand();
    }

    /** Snaps the player's yaw the way this piece asked to be faced. */
    private static float yawFor(Player player, ModelInfo info) {
        float yaw = player.getLocation().getYaw();
        switch (info.facing()) {
            case CARDINAL:
                return Math.round(yaw / 90f) * 90f;
            case DIAGONAL:
                return Math.round(yaw / 45f) * 45f;
            case FIXED:
                return 0f;
            default:
                return yaw;
        }
    }

    /** Puts a piece into a block space. Main thread only. */
    public Interaction place(Block target, ModelInfo info, float yaw, ItemStack source) {
        World world = target.getWorld();
        ItemStack shown = source != null && source.getType() != Material.AIR
                ? asOne(source)
                : items.create(info.item()).orElse(null);

        // Block centre, because an item display renders its model centred on
        // the entity position: a model built from y=0 upward then sits exactly
        // on the block floor.
        Location centre = target.getLocation().add(0.5, 0.5, 0.5);
        centre.setYaw(yaw);
        ItemDisplay display = world.spawn(centre, ItemDisplay.class, d -> {
            d.setItemStack(shown);
            // NONE, and this is load-bearing. Every other transform applies the
            // model's own `display` block, and the vanilla block/block parent
            // that generated models inherit sets `fixed` to scale 0.5 with a
            // translation — so a two-block statue renders one block tall and
            // slightly off the ground. NONE applies nothing, leaving 16 model
            // units to the block, which is the only scale a placed model can
            // honestly be.
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setRotation(yaw, 0f);
            if (info.scale() != 1f) {
                Transformation transformation = d.getTransformation();
                transformation.getScale().set(info.scale());
                d.setTransformation(transformation);
            }
            // Culled otherwise: a large piece disappears when its anchor block
            // leaves the frustum, which reads as flickering model.
            d.setViewRange(1.5f);
            d.setDisplayWidth(Math.max(1f, info.width() * info.scale()));
            d.setDisplayHeight(Math.max(1f, info.height() * info.scale()));
            d.setBillboard(Display.Billboard.FIXED);
        });

        // An Interaction anchors at its feet rather than its centre, so this
        // one sits on the block floor and grows upward.
        Location base = target.getLocation().add(0.5, 0.0, 0.5);
        Interaction hitbox = world.spawn(base, Interaction.class, i -> {
            i.setInteractionWidth(info.width() * info.scale());
            i.setInteractionHeight(info.height() * info.scale());
            i.setResponsive(true);
            i.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, info.id().toString());
            i.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING,
                    display.getUniqueId().toString());
            if (info.solid()) {
                i.getPersistentDataContainer().set(solidKey, PersistentDataType.BYTE, (byte) 1);
            }
        });

        if (info.solid()) {
            // A display entity has no collision whatsoever. This is the only
            // way to make a table something you cannot walk through.
            target.setType(Material.BARRIER, false);
        }
        return hitbox;
    }

    private static ItemStack asOne(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        return one;
    }

    // ---- breaking ------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPunch(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Interaction)) {
            return;
        }
        Interaction hitbox = (Interaction) event.getEntity();
        String raw = hitbox.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        Optional<ContentId> id = ContentId.parse(raw);
        if (id.isEmpty()) {
            // Somebody else's Interaction entity. Not ours to remove.
            return;
        }
        event.setCancelled(true);

        Player player = event.getDamager() instanceof Player ? (Player) event.getDamager() : null;
        boolean drop = player == null || player.getGameMode() != GameMode.CREATIVE;
        remove(hitbox, id.get(), player, drop);
    }

    /** Takes a piece apart, dropping its item unless told otherwise. */
    public void remove(Interaction hitbox, ContentId id, Player breaker, boolean dropItem) {
        ModelBreakEvent ask = new ModelBreakEvent(id, hitbox.getLocation(), breaker, dropItem);
        Bukkit.getPluginManager().callEvent(ask);
        if (ask.isCancelled()) {
            return;
        }

        Location where = hitbox.getLocation();
        World world = where.getWorld();

        ItemStack drop = null;
        String displayId = hitbox.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
        if (displayId != null) {
            Entity display = Bukkit.getEntity(java.util.UUID.fromString(displayId));
            if (display instanceof ItemDisplay) {
                // Drops what it was placed holding, so a renamed or enchanted
                // piece comes back as itself rather than as a fresh one.
                drop = ((ItemDisplay) display).getItemStack();
                display.remove();
            }
        }

        if (hitbox.getPersistentDataContainer().has(solidKey, PersistentDataType.BYTE)) {
            Block anchor = where.getBlock();
            if (anchor.getType() == Material.BARRIER) {
                anchor.setType(Material.AIR, false);
            }
        }
        hitbox.remove();

        if (!ask.isDropItem() || world == null) {
            return;
        }
        ItemStack fallback = drop != null ? drop : items.create(modelItem(id)).orElse(null);
        if (fallback != null) {
            world.dropItemNaturally(where.add(0, 0.5, 0), fallback);
        }
    }

    private ContentId modelItem(ContentId id) {
        ModelInfo info = model.get(id);
        return info == null ? id : info.item();
    }

    /** The hitbox of the piece standing in {@code block}, or null. */
    public Interaction findAt(Block block) {
        Location centre = block.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : block.getWorld().getNearbyEntities(centre, 0.5, 0.5, 0.5)) {
            if (entity instanceof Interaction
                    && entity.getPersistentDataContainer().has(idKey, PersistentDataType.STRING)) {
                return (Interaction) entity;
            }
        }
        return null;
    }
}
