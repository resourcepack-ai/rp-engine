package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.event.ModelBindEvent;
import ai.resourcepack.engine.core.Host;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Wearing a model on an entity that already exists.
 *
 * <p>This is the thing servers pay ModelEngine for, and the old library never
 * did it: a mob spawned by MythicMobs, an NPC from Citizens, a shopkeeper, a
 * boss another plugin owns — anything with an id — can be given one of this
 * server's models to look like, and keeps its own AI, its own loot, its own
 * hitbox and its own everything else.
 *
 * <p><strong>The entity is never replaced or re-spawned.</strong> That is the
 * whole design: an implementation that spawned a new mob to carry the model
 * would break the reference every other plugin is holding, and the entity that
 * came back would not be the one MythicMobs is running a skill tree on.
 *
 * <p>How: the rig's part displays are made PASSENGERS of the host, so vanilla
 * moves and interpolates them — no per-tick teleport, and they ride through a
 * chunk unload and a restart as ordinary saved passengers. The vanilla body is
 * made invisible rather than removed, so the hitbox stays exactly where the
 * model looks. That is the same arrangement {@code CustomEntities} already
 * uses for a mob we spawned ourselves; the difference here is only who spawned
 * it, which is precisely the difference that should not matter.
 *
 * <p>The one thing a bound part needs that a placed one does not is its yaw,
 * which is its host's and changes every time the mob turns. That is a flag the
 * animator reads (see {@code RigAnimator.BOUND_KEY}) rather than a second
 * animator.
 */
public final class BoundModels {

    private final Items items;
    private final RigStore rigs;
    private final RigAnimator animator;
    private final RigSpawn spawns;
    private final NamespacedKey modelKey;
    private final NamespacedKey partKey;
    private final NamespacedKey boundKey;
    private final NamespacedKey scaleKey;

    /**
     * Somewhere a bind can be WRITTEN DOWN, for a host that outlives its own
     * entity.
     *
     * <p>A Citizens NPC is despawned and respawned on a chunk unload, a reload
     * and a restart, and the entity that comes back is a new one — so a model
     * bound to the old one is simply gone. The NPC itself survives, and can
     * hold the id.
     *
     * <p>A function rather than a Citizens call, because this class must not
     * name a type from a plugin that may not be installed. It answers whether
     * it took responsibility; nothing else here has to know what it is.
     */
    private volatile java.util.function.BiPredicate<Entity, ContentId> remember = (host, id) -> false;

    /** Wires the place a bind is remembered. Called once at startup. */
    public void remembersWith(java.util.function.BiPredicate<Entity, ContentId> remember) {
        this.remember = remember == null ? (host, id) -> false : remember;
    }

    public BoundModels(Host host, Items items, RigStore rigs, RigAnimator animator) {
        this.items = items;
        this.rigs = rigs;
        this.animator = animator;
        this.spawns = new RigSpawn(host, animator);
        this.modelKey = host.key("model-id");
        this.partKey = host.key("part-index");
        this.boundKey = host.key(RigAnimator.BOUND_KEY);
        this.scaleKey = host.key(RigAnimator.SCALE_KEY);
    }

    /**
     * Puts {@code modelId}'s model on {@code host}.
     *
     * <p>Rebinding replaces what was there, rather than stacking a second
     * model on the same body: an entity looks like one thing.
     *
     * @param modelId an item id whose model the entity wears — the same id a
     *                {@code place:} block would name, so a model can be stood
     *                in a world and worn by a mob without being defined twice
     * @return whether it took
     */
    public boolean bind(Entity host, ContentId modelId, float scale) {
        if (host == null || modelId == null || !host.isValid()) {
            return false;
        }
        ItemStack whole = items.create(modelId).orElse(null);
        if (whole == null) {
            return false;
        }
        // The entity belongs to somebody else — a MythicMobs boss, an NPC, a
        // mob a command block spawned — so the plugin that owns it gets to
        // refuse having it dressed.
        if (refused(host, modelId, ModelBindEvent.Action.BIND)) {
            return false;
        }
        // AFTER the unbind, never before it: unbind clears the remembered id,
        // so writing first and clearing second leaves an NPC that wears a
        // model until the next respawn and then forgets it.
        unbind(host);
        remember.test(host, modelId);

        float size = scale > 0 ? scale : 1f;
        String id = modelId.toString();
        RigStore.Rig rig = rigs.get(id);

        List<ItemDisplay> parts = new ArrayList<>();
        if (rig != null && rig.parts != null && !rig.parts.isEmpty()) {
            // An animated model: one display per part, exactly as a placed rig
            // gets. The yaw passed here is the host's at this instant and is
            // only the starting value — the animator reads the live one.
            parts.addAll(spawns.parts(host.getLocation().getBlock(), id, rig,
                    host.getLocation().getYaw(), null, size, part -> partStack(whole, part.item)));
        } else {
            // A still model is one display holding the item itself. Bound the
            // same way and read by the same code, so a model that gains an
            // animation later needs nothing changed here.
            parts.add(host.getWorld().spawn(host.getLocation(), ItemDisplay.class, d -> {
                d.setItemStack(whole);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, id);
                d.getPersistentDataContainer().set(partKey, PersistentDataType.INTEGER, 0);
                if (size != 1f) {
                    d.getPersistentDataContainer().set(scaleKey, PersistentDataType.FLOAT, size);
                }
            }));
        }
        if (parts.isEmpty()) {
            return false;
        }

        for (ItemDisplay part : parts) {
            part.getPersistentDataContainer().set(boundKey, PersistentDataType.STRING,
                    host.getUniqueId().toString());
            // Passengers, so vanilla carries them. A display that had to be
            // teleported every tick would cost a packet per part per player
            // per tick and still trail the body it belongs to.
            host.addPassenger(part);
            animator.track(part);
            animator.poseNow(part);
        }

        conceal(host, true);
        return true;
    }

    /** Whether a listener said no to this bind or unbind. */
    private static boolean refused(Entity host, ContentId model, ModelBindEvent.Action action) {
        ModelBindEvent asked = new ModelBindEvent(host, model, action);
        host.getServer().getPluginManager().callEvent(asked);
        return asked.isCancelled();
    }

    /** Takes the model off, and gives the entity its own body back. */
    public boolean unbind(Entity host) {
        if (host == null) {
            return false;
        }
        if (refused(host, modelOn(host).orElse(null), ModelBindEvent.Action.UNBIND)) {
            return false;
        }
        boolean had = false;
        for (Entity rider : List.copyOf(host.getPassengers())) {
            if (!isPart(rider)) {
                continue;
            }
            had = true;
            animator.untrack(rider.getUniqueId());
            rider.remove();
        }
        if (had) {
            conceal(host, false);
        }
        // Cleared whether or not anything was on: an NPC asked to take its
        // model off must not put it back on when it next respawns.
        remember.test(host, null);
        return had;
    }

    /** The model {@code host} is wearing, if it is wearing one of ours. */
    public Optional<ContentId> modelOn(Entity host) {
        if (host == null) {
            return Optional.empty();
        }
        for (Entity rider : host.getPassengers()) {
            if (isPart(rider)) {
                return ContentId.parse(
                        rider.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING));
            }
        }
        return Optional.empty();
    }

    /** Plays a named animation on what {@code host} is wearing. */
    public boolean play(Entity host, String animation, boolean restart) {
        List<ItemDisplay> parts = partsOf(host);
        return !parts.isEmpty() && animator.playOn(parts,
                parts.get(0).getPersistentDataContainer().get(modelKey, PersistentDataType.STRING),
                animation, restart);
    }

    /** Stops it, back to rest or to its idle loop. */
    public boolean stop(Entity host) {
        List<ItemDisplay> parts = partsOf(host);
        if (parts.isEmpty()) {
            return false;
        }
        animator.stopOn(parts);
        return true;
    }

    private List<ItemDisplay> partsOf(Entity host) {
        List<ItemDisplay> parts = new ArrayList<>();
        if (host == null) {
            return parts;
        }
        for (Entity rider : host.getPassengers()) {
            if (isPart(rider)) {
                parts.add((ItemDisplay) rider);
            }
        }
        return parts;
    }

    private boolean isPart(Entity entity) {
        return entity instanceof ItemDisplay
                && entity.getPersistentDataContainer().has(boundKey, PersistentDataType.STRING);
    }

    /**
     * Hides the real body, or gives it back.
     *
     * <p>Invisible rather than removed, and this is the load-bearing half of
     * "the entity is never replaced": the mob is still standing there with its
     * own hitbox, so it is hit where it looks like it is, every plugin
     * targeting it still finds it, and taking the model off leaves a perfectly
     * ordinary mob behind.
     */
    private static void conceal(Entity host, boolean hidden) {
        if (host instanceof LivingEntity) {
            ((LivingEntity) host).setInvisible(hidden);
        }
    }

    private ItemStack partStack(ItemStack whole, String partId) {
        ItemStack stack = whole.clone();
        stack.setAmount(1);
        int colon = partId.indexOf(':');
        if (colon < 0) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setItemModel(new NamespacedKey(partId.substring(0, colon), partId.substring(colon + 1)));
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
