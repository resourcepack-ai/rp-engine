package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.api.event.ModelBreakEvent;
import ai.resourcepack.engine.api.event.ModelInteractEvent;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import ai.resourcepack.engine.core.Host;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private final Seats seats;
    private final NamespacedKey idKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey solidKey;

    /**
     * The rig half, which a piece uses only if its model has keyframes in it.
     *
     * <p>These are the animator's own keys rather than this listener's,
     * because they are read by the animator: a placed rig is found, posed and
     * triggered by exactly the same code whether it came out of a content
     * folder or off a studio push. That is the whole point of building it this
     * way, and it is why an authored piece is not a second animation system.
     */
    private final RigStore rigs;
    private final RigAnimator animator;
    private final RigSpawn spawns;
    private final NamespacedKey rigModelKey;
    private final NamespacedKey displaysKey;
    private final NamespacedKey partKey;

    private volatile Map<ContentId, ModelInfo> model = Map.of();

    public ModelPlacementListener(Plugin plugin, Items items, Seats seats,
                                  Host host, RigStore rigs, RigAnimator animator) {
        this.plugin = plugin;
        this.items = items;
        this.seats = seats;
        this.idKey = new NamespacedKey(plugin, "model");
        this.displayKey = new NamespacedKey(plugin, "model-display");
        this.solidKey = new NamespacedKey(plugin, "model-solid");
        this.rigs = rigs;
        this.animator = animator;
        this.spawns = new RigSpawn(host, animator);
        this.rigModelKey = host.key("model-id");
        this.displaysKey = host.key("display-uuids");
        this.partKey = host.key("part-index");
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

        // An animated piece is several displays the server retimes rather than
        // one still one. Everything below — the hitbox, the barrier, the
        // persistent data — is the same either way, which is why this is a
        // branch over what to spawn rather than a second place().
        RigStore.Rig rig = rigs == null ? null : rigs.get(info.id().toString());
        List<String> partIds = rig != null && rig.parts != null && !rig.parts.isEmpty()
                ? spawns.parts(target, info.id().toString(), rig, yaw, null, info.scale(),
                        part -> partStack(info, part))
                : null;

        // Block centre, because an item display renders its model centred on
        // the entity position: a model built from y=0 upward then sits exactly
        // on the block floor.
        Location centre = target.getLocation().add(0.5, 0.5, 0.5);
        centre.setYaw(yaw);
        ItemDisplay display = partIds != null ? null : world.spawn(centre, ItemDisplay.class, d -> {
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
                    partIds != null ? String.join(",", partIds) : display.getUniqueId().toString());
            if (partIds != null) {
                // What the animator looks for. The id it wants is the model's,
                // and the list is the same list under its own name — written
                // twice rather than sharing a key, because this listener's
                // break path and the animator's tick both have to keep working
                // if the other one changes.
                i.getPersistentDataContainer().set(rigModelKey, PersistentDataType.STRING, info.id().toString());
                i.getPersistentDataContainer().set(displaysKey, PersistentDataType.STRING,
                        String.join(",", partIds));
            }
            if (info.solid()) {
                i.getPersistentDataContainer().set(solidKey, PersistentDataType.BYTE, (byte) 1);
            }
        });

        if (partIds != null) {
            animator.track(hitbox);
            animator.trigger(hitbox, RigAnimator.TRIGGER_PLACE, null);
        }

        if (info.solid()) {
            // A display entity has no collision whatsoever. This is the only
            // way to make a table something you cannot walk through.
            target.setType(Material.BARRIER, false);
        }
        return hitbox;
    }

    /**
     * What one part of an authored piece renders as.
     *
     * <p>The piece's own material wearing the part's item model, which the
     * pack builder wrote beside the whole one. Studio's parts are paper with a
     * {@code custom_model_data} string instead — it has no plugin to define an
     * item and has to borrow a vanilla one. We are the plugin, so we do not.
     */
    private ItemStack partStack(ModelInfo info, RigStore.Part part) {
        ItemStack stack = items.create(info.item()).orElse(null);
        if (stack == null) {
            return null;
        }
        stack.setAmount(1);
        int colon = part.item.indexOf(':');
        if (colon < 0) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setItemModel(new NamespacedKey(part.item.substring(0, colon), part.item.substring(colon + 1)));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static ItemStack asOne(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        return one;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Interaction)) {
            return;
        }
        Interaction hitbox = (Interaction) event.getRightClicked();
        Optional<ContentId> id = idOf(hitbox);
        if (id.isEmpty()) {
            // Somebody else's Interaction entity. Not ours to speak for.
            return;
        }
        ModelInteractEvent clicked = new ModelInteractEvent(event.getPlayer(), id.get(), hitbox);
        Bukkit.getPluginManager().callEvent(clicked);
        if (clicked.isCancelled()) {
            return;
        }

        // A right-click animation gets the click before sitting does. An
        // author who gave a piece both asked for a chair that does something
        // when you use it, and a seat is what SHIFT-clicking a seat still is.
        if (animator != null && animator.hasTrigger(id.get().toString(), RigAnimator.TRIGGER_RIGHT_CLICK)
                && !event.getPlayer().isSneaking()) {
            event.setCancelled(true);
            animator.trigger(hitbox, RigAnimator.TRIGGER_RIGHT_CLICK, event.getPlayer());
            return;
        }

        // Sitting is the one behaviour the engine does provide, because the
        // model already said it is a seat and there is exactly one sensible
        // thing to do about that. Everything else a click might mean is still
        // a decision about somebody's server, which is what the event is for —
        // and a listener that cancels gets its way before this runs.
        ModelInfo info = model.get(id.get());
        if (info != null && info.sittable()) {
            Location seat = hitbox.getLocation().add(0, info.seat() * info.scale(), 0);
            seat.setYaw(hitbox.getLocation().getYaw());
            seats.sit(event.getPlayer(), seat);
        }
    }

    /** The model standing as {@code hitbox}, or empty if that is not one of ours. */
    public Optional<ContentId> idOf(Interaction hitbox) {
        if (hitbox == null) {
            return Optional.empty();
        }
        return ContentId.parse(hitbox.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    // ---- breaking ------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPunch(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Interaction)) {
            return;
        }
        Interaction hitbox = (Interaction) event.getEntity();
        Optional<ContentId> id = idOf(hitbox);
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

        // Every display, because an animated piece is several and one left
        // behind is a limb standing in an empty block.
        ItemStack drop = null;
        for (String raw : displayIdsOf(hitbox)) {
            Entity display;
            try {
                display = Bukkit.getEntity(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                // A malformed id costs that one display and nothing else.
                continue;
            }
            if (!(display instanceof ItemDisplay)) {
                continue;
            }
            animator.untrack(display.getUniqueId());
            // Drops what it was placed holding, so a renamed or enchanted
            // piece comes back as itself rather than as a fresh one — but
            // never a PART, which is a sub-model with no inventory form. An
            // animated piece falls through to items.create below.
            if (drop == null && !display.getPersistentDataContainer().has(partKey, PersistentDataType.INTEGER)) {
                drop = ((ItemDisplay) display).getItemStack();
            }
            display.remove();
        }
        animator.untrackHitbox(hitbox.getUniqueId());

        // Anybody sitting on it stands up first. A seat that outlives its
        // chair is an invisible thing a player can stand on for ever.
        for (Entity rider : hitbox.getWorld().getNearbyEntities(where, 1.5, 2.5, 1.5)) {
            if (rider instanceof Player && seats.isSeated((Player) rider)) {
                seats.stand((Player) rider);
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

    /** The displays a placement owns: several for a rig, one for a still piece. */
    private List<String> displayIdsOf(Interaction hitbox) {
        String joined = hitbox.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
        if (joined == null || joined.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        java.util.Collections.addAll(ids, joined.split(","));
        return ids;
    }

    private ContentId modelItem(ContentId id) {
        ModelInfo info = model.get(id);
        return info == null ? id : info.item();
    }

    /**
     * Every model placed within {@code radius} blocks of {@code centre}.
     *
     * <p>There is no index of placed models and deliberately never will be:
     * they are chunk-saved entities, so the world IS the index and cannot
     * drift out of step with itself. The cost is that finding them means
     * asking the world, which is why this takes a radius rather than
     * pretending a whole-server list is cheap.
     */
    public java.util.List<Interaction> near(Location centre, double radius) {
        java.util.List<Interaction> found = new java.util.ArrayList<>();
        if (centre == null || centre.getWorld() == null) {
            return found;
        }
        for (Entity entity : centre.getWorld().getNearbyEntities(centre, radius, radius, radius)) {
            if (entity instanceof Interaction && idOf((Interaction) entity).isPresent()) {
                found.add((Interaction) entity);
            }
        }
        return found;
    }

    /**
     * Whether a placed model is one the loaded content still knows about.
     *
     * <p>Deleting a content pack does not delete what was placed from it, and
     * should not: reinstalling the pack brings somebody's build back rather
     * than leaving a hole in it. But it does leave models standing that
     * nothing can explain, and being able to ask is the difference between a
     * mystery and a decision.
     */
    public boolean isOrphan(Interaction hitbox) {
        return idOf(hitbox).filter(model::containsKey).isEmpty();
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
