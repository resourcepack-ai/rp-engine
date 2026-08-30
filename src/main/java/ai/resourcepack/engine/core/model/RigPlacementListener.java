package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.event.ModelBreakEvent;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import ai.resourcepack.engine.core.Host;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Makes ResourcePack AI model items placeable. The panel gives models as
 * paper with a string custom_model_data, which vanilla can't place.
 * Right-clicking a block with one spawns a "rig" in the adjacent space:
 *
 *   - Static models: one ItemDisplay holding the item itself, block-centered,
 *     yaw snapped to the placement direction.
 *   - Animated models (see {@link RigStore}): one ItemDisplay per rig part,
 *     each showing its "&lt;model&gt;__part&lt;n&gt;" item. Moving parts store
 *     placement yaw for {@link RigAnimator}; the static remainder keeps a
 *     fixed entity yaw and is excluded from animator ticks.
 *
 * Either way an Interaction entity fills the block space as the hitbox, since
 * displays aren't clickable. It dispatches click triggers; punching otherwise
 * breaks the rig and drops the item back. Everything is PDC-tagged, so rigs
 * survive restarts as ordinary chunk-saved entities.
 */
public final class RigPlacementListener implements Listener {

    /** Prefix of the custom_model_data string carrying an animation choice. */
    static final String ANIMATION_MARKER = "rpai_anim:";

    /**
     * Prefix of the custom_model_data string carrying a size multiplier. The
     * panel writes it (see Studio's carrier-scale option); an absent marker
     * means the model's own size, which is what every stack given before this
     * existed meant and must keep meaning.
     */
    static final String SCALE_MARKER = "rpai_scale:";

    /** Clamped to the same range the panel offers, since this is parsed text. */
    private static final float MIN_SCALE = 0.125f;
    private static final float MAX_SCALE = 8f;

    private final Host host;
    private final NamespacedKey modelKey;
    private final NamespacedKey partKey;
    private final NamespacedKey yawKey;
    private final NamespacedKey animationKey;
    private final NamespacedKey scaleKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey displaysKey;
    private final RigStore rigs;
    private final RigAnimator animator;
    private final RigSpawn spawns;
    /**
     * The tag the content folder's own placements carry.
     *
     * <p>Both kinds of placement wear the animator's {@code model-id} key,
     * because both are animated by it. Only this listener's are studio's, and
     * an authored piece broken here would drop a paper carrier instead of the
     * item it was placed from — so the two clicks below step aside when they
     * see somebody else's.
     */
    private final NamespacedKey authoredKey;

    public RigPlacementListener(Host host, RigStore rigs, RigAnimator animator) {
        this.host = host;
        this.modelKey = host.key("model-id");
        this.partKey = host.key("part-index");
        this.yawKey = host.key("rig-yaw");
        this.animationKey = host.key(RigAnimator.ANIMATION_CHOICE_KEY);
        this.scaleKey = host.key(RigAnimator.SCALE_KEY);
        this.displayKey = host.key("display-uuid");
        this.displaysKey = host.key("display-uuids");
        this.rigs = rigs;
        this.animator = animator;
        this.spawns = new RigSpawn(host, animator);
        this.authoredKey = host.key("model");
    }

    /** The model id a panel-given item carries, or null if it's not one of ours. */
    private static String modelIdOf(ItemStack item) {
        List<String> strings = customModelStrings(item);
        return strings.isEmpty() ? null : strings.get(0);
    }

    /**
     * The animation a panel-given item asks for, or null. Several animations
     * can claim one trigger and only one may run, so the panel writes the
     * choice onto the stack as a "rpai_anim:&lt;name&gt;" custom_model_data
     * string. Only index 0 drives the model select, so it can't affect
     * rendering.
     */
    static String animationOf(ItemStack item) {
        for (String value : customModelStrings(item)) {
            if (value != null && value.startsWith(ANIMATION_MARKER)) {
                String name = value.substring(ANIMATION_MARKER.length());
                return name.isEmpty() ? null : name;
            }
        }
        return null;
    }

    /**
     * The size multiplier a panel-given item asks for, or 1. Unparseable and
     * out-of-range values fall back to the model's own size rather than
     * refusing the placement: the stack is otherwise valid, and a rig that
     * does not appear is a worse answer than one that appears as built.
     */
    static float scaleOf(ItemStack item) {
        for (String value : customModelStrings(item)) {
            if (value == null || !value.startsWith(SCALE_MARKER)) continue;
            try {
                float parsed = Float.parseFloat(value.substring(SCALE_MARKER.length()));
                if (!Float.isFinite(parsed) || parsed <= 0f) return 1f;
                return Math.max(MIN_SCALE, Math.min(MAX_SCALE, parsed));
            } catch (NumberFormatException ignored) {
                return 1f;
            }
        }
        return 1f;
    }

    private static List<String> customModelStrings(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return Collections.emptyList();
        return tags.read(item);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        // Offhand fires a second, redundant event for the same click.
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        String modelId = modelIdOf(item);
        if (modelId == null) return;
        // Part items shouldn't be placeable on their own (a player could
        // fish one out of the creative inventory or a broken rig edge case).
        if (modelId.contains("__part")) return;

        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        // Chests/doors/etc. keep their vanilla click behavior unless the
        // player sneaks - same rule as placing an ordinary block.
        if (clicked.getType().isInteractable() && !player.isSneaking()) return;

        // Ours from here on: never also run the vanilla use.
        event.setCancelled(true);

        Block target = clicked.getRelative(event.getBlockFace());
        if (!target.getType().isAir()) return;
        if (findRig(target) != null) return;

        // Everything above is "can a rig physically go here". Whether one is
        // allowed to is not ours to decide - see ModelPlaceEvent.
        // A studio model has a slug rather than a content id, so it is
        // announced under the namespace it came from — which is true, reads
        // correctly in a listener, and cannot collide with a content pack.
        ModelPlaceEvent ask = new ModelPlaceEvent(player,
                ai.resourcepack.engine.api.ContentId.of("studio", modelId.toLowerCase(java.util.Locale.ROOT))
                        .orElse(null), target);
        Bukkit.getPluginManager().callEvent(ask);
        if (ask.isCancelled()) return;

        // Nearest cardinal, so the model fronts the player like a placed
        // furnace would.
        float yaw = Math.round(player.getLocation().getYaw() / 90f) * 90f;

        // The stack's choice becomes the placement's, stored on the entities
        // so it survives restarts and two copies of one model can stand side
        // by side playing different animations.
        spawn(target, modelId, item, yaw, animationOf(item), scaleOf(item), player);

        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        player.swingMainHand();
    }

    /**
     * Puts a rig into a block space and returns its hitbox.
     *
     * <p>The shared half of placing a model: the entities, their persistent
     * data, the Bedrock mirror and the place trigger. Everything ABOVE it
     * differs by who asked - a player click has an item to take and an event to
     * ask first, a call from code has neither - so those stay with their
     * callers and this stays the one description of what a placed rig is.
     *
     * @return the hitbox that anchors the new rig.
     */
    Interaction spawn(Block target, String modelId, ItemStack sourceItem, float yaw,
            String animation, float scale, Player placer) {
        RigStore.Rig rig = rigs.get(modelId);
        List<String> displayIds =
            rig != null && rig.parts != null && !rig.parts.isEmpty()
                ? spawnAnimatedRig(target, modelId, rig, yaw, animation, scale)
                : Collections.singletonList(
                    spawnStaticDisplay(target, modelId, sourceItem, yaw, scale).getUniqueId().toString());

        World world = target.getWorld();
        // Interaction hitboxes anchor at their feet, not their center.
        Location base = target.getLocation().add(0.5, 0.0, 0.5);
        Interaction hitbox = world.spawn(base, Interaction.class, i -> {
            // The hitbox grows with the rig. Without this a 4x statue is only
            // punchable in the one block it was placed in, so most of what you
            // can see cannot be broken or right-clicked — and the part you can
            // hit is buried inside it.
            i.setInteractionWidth(scale);
            i.setInteractionHeight(scale);
            i.setResponsive(true);
            i.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, modelId);
            i.getPersistentDataContainer().set(displaysKey, PersistentDataType.STRING, String.join(",", displayIds));
            if (scale != 1f) i.getPersistentDataContainer().set(scaleKey, PersistentDataType.FLOAT, scale);
            // Set here rather than after spawn: the place trigger fires below
            // and resolves the choice off this container.
            if (animation != null) {
                i.getPersistentDataContainer().set(animationKey, PersistentDataType.STRING, animation);
            }
        });
        animator.track(hitbox);
        animator.trigger(hitbox, RigAnimator.TRIGGER_PLACE, placer);
        return hitbox;
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction)) return;
        Interaction hitbox = (Interaction) event.getRightClicked();
        if (hitbox.getPersistentDataContainer().has(authoredKey, PersistentDataType.STRING)) return;
        String modelId = hitbox.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
        if (modelId == null || !animator.hasTrigger(modelId, RigAnimator.TRIGGER_RIGHT_CLICK)) return;
        event.setCancelled(true);
        animator.trigger(hitbox, RigAnimator.TRIGGER_RIGHT_CLICK, event.getPlayer());
    }

    private ItemDisplay spawnStaticDisplay(Block target, String modelId, ItemStack sourceItem, float yaw, float scale) {
        ItemStack displayItem = sourceItem.clone();
        displayItem.setAmount(1);
        // Item displays render the model centered on the entity position,
        // so block-center puts a 16px cube exactly in the block space.
        Location center = target.getLocation().add(0.5, 0.5, 0.5);
        center.setYaw(yaw);
        return target.getWorld().spawn(center, ItemDisplay.class, d -> {
            d.setItemStack(displayItem);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, modelId);
            if (scale != 1f) {
                d.getPersistentDataContainer().set(scaleKey, PersistentDataType.FLOAT, scale);
                d.setTransformation(RigAnimator.scaledTransformation(scale));
            }
        });
    }

    /**
     * The studio half of placing a rig: paper parts wearing a
     * {@code custom_model_data} string. Everything that is not the part item
     * is {@link RigSpawn}, which the content folder's own placement shares.
     */
    private List<String> spawnAnimatedRig(Block target, String modelId, RigStore.Rig rig, float yaw, String animation, float scale) {
        return spawns.parts(target, modelId, rig, yaw, animation, scale,
                        part -> itemWithModelData(part.item))
                .stream().map(display -> display.getUniqueId().toString()).toList();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Interaction)) return;
        Interaction hitbox = (Interaction) event.getEntity();
        if (hitbox.getPersistentDataContainer().has(authoredKey, PersistentDataType.STRING)) return;
        String modelId = hitbox.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
        if (modelId == null) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();

        // A left-click trigger consumes the hit permanently: choosing it makes
        // the placement an unbreakable prop rather than overloading punch.
        if (animator.hasTrigger(modelId, RigAnimator.TRIGGER_LEFT_CLICK)) {
            animator.trigger(hitbox, RigAnimator.TRIGGER_LEFT_CLICK, player);
            return;
        }

        // Same question the place path asks, from the other end: a rig is
        // entities, so taking one apart fires none of the events breaking a
        // block would, and nothing could refuse it.
        ModelBreakEvent ask = new ModelBreakEvent(
                ai.resourcepack.engine.api.ContentId.of("studio", modelId.toLowerCase(java.util.Locale.ROOT))
                        .orElse(null),
                hitbox.getLocation(), player, player.getGameMode() != GameMode.CREATIVE);
        Bukkit.getPluginManager().callEvent(ask);
        if (ask.isCancelled()) return;

        breakRig(hitbox, modelId, ask.isDropItem());
    }

    /**
     * Takes a rig apart, with no event and no opinion about who asked.
     *
     * <p>The shared half of breaking a model, for the same reason
     * {@link #spawn} is shared: a punch has an event to ask and a game mode to
     * read, a call from code has its own answer to both.
     */
    void breakRig(Interaction hitbox, String modelId, boolean dropItem) {
        // Prefer the exact item a static display holds. Animated rigs rebuild
        // from the model id: their displays hold part items, which aren't a
        // valid inventory form.
        ItemStack drop = null;
        for (String raw : displayIdsOf(hitbox)) {
            Entity display;
            try {
                display = Bukkit.getEntity(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!(display instanceof ItemDisplay)) continue;
            animator.untrack(display.getUniqueId());
            if (drop == null && !display.getPersistentDataContainer().has(partKey, PersistentDataType.INTEGER)) {
                ItemStack held = ((ItemDisplay) display).getItemStack();
                if (held != null && held.getType() != Material.AIR) {
                    drop = held.clone();
                    drop.setAmount(1);
                }
            }
            display.remove();
        }
        // The rebuild has to carry the animation choice, or breaking and
        // replacing a model resets it to the rig's first animation.
        String animation = hitbox.getPersistentDataContainer().get(animationKey, PersistentDataType.STRING);
        Float placedScale = hitbox.getPersistentDataContainer().get(scaleKey, PersistentDataType.FLOAT);
        float scale = placedScale != null ? placedScale : 1f;
        Location where = hitbox.getLocation().add(0, 0.5, 0);
        World world = hitbox.getWorld();
        animator.untrackHitbox(hitbox.getUniqueId());
        hitbox.remove();

        if (!dropItem) return;
        world.dropItemNaturally(where, drop != null ? drop : itemWithModelData(modelId, animation, scale));
    }

    /** The handle an event carries. Set by the library at startup; see RigAnimator. */
    private java.util.function.Function<Interaction, ai.resourcepack.engine.api.Placement> placements;

    public void placements(java.util.function.Function<Interaction, ai.resourcepack.engine.api.Placement> placements) {
        this.placements = placements;
    }

    private ai.resourcepack.engine.api.Placement placement(Interaction hitbox) {
        return placements == null ? null : placements.apply(hitbox);
    }

    private List<String> displayIdsOf(Interaction hitbox) {
        String joined = hitbox.getPersistentDataContainer().get(displaysKey, PersistentDataType.STRING);
        // Rigs placed by the pre-animation plugin build stored a single id
        // under the old key.
        if (joined == null) joined = hitbox.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
        if (joined == null || joined.isEmpty()) return Collections.emptyList();
        List<String> ids = new ArrayList<>();
        Collections.addAll(ids, joined.split(","));
        return ids;
    }

    static ItemStack itemWithModelData(String modelData) {
        return itemWithModelData(modelData, null, 1f);
    }

    static ItemStack itemWithModelData(String modelData, String animation, float scale) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> values = new ArrayList<>(3);
            values.add(modelData);
            // Rebuilt without the Bedrock slot marker the panel writes at
            // index 1: nothing here can know the cmd, and only index 0 is read
            // for rendering. The animation marker is found by prefix, not
            // position, so it survives the gap.
            if (animation != null) values.add(ANIMATION_MARKER + animation);
            // And the size, for the same reason: breaking a 4x statue and
            // putting it back down must not quietly return it to 1x.
            if (scale != 1f) values.add(SCALE_MARKER + trimFloat(scale));
            // Where these end up is the server's version, not this method's
            // business — see RigTags.
            tags.write(meta, values);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Where a part item's tags live on this server.
     *
     * <p>Static because both readers and the writer above are, and they are
     * static because they are reached from the animator and the emote
     * director as well as from here. Starts inert so that a stack inspected
     * before the plugin has resolved its compatibility answers "no tags"
     * rather than throwing.
     */
    private static volatile RigTags tags = RigTags.NONE;

    /** Set from the plugin once the server's version is known. */
    public static void tags(RigTags resolved) {
        if (resolved != null) {
            tags = resolved;
        }
    }

    /** "2.0" reads as 2 on the item; the panel writes whole numbers where it can. */
    private static String trimFloat(float value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.valueOf(value);
    }

    /** An existing rig hitbox occupying this block space, if any. */
    Interaction findRig(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (Entity e : block.getWorld().getNearbyEntities(center, 0.45, 0.55, 0.45)) {
            if (e instanceof Interaction && e.getPersistentDataContainer().has(modelKey, PersistentDataType.STRING)) {
                return (Interaction) e;
            }
        }
        return null;
    }
}
