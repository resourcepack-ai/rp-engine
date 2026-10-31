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
import org.bukkit.persistence.PersistentDataContainer;
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

    /**
     * Which way a worn model faces its host's heading at {@code turn: 0}: the
     * model's {@code -z}, the side Blockbench calls North, so this is -1.
     *
     * <p><strong>ModelEngine's convention, and kept for that reason.</strong>
     * A rig somebody already has, made for ModelEngine and worn through
     * {@code /rp bind} or MythicMobs' {@code rpmodel}, faces North in
     * Blockbench and has to walk forwards here without being re-authored. A
     * model drawn the other way round (Studio draws every model facing
     * {@code +z}) takes {@code turn: 180}, which Studio adds to the turn it
     * pushes. Vehicles differ, deliberately and for their own reasons: see
     * {@code VehicleRuntime.MODEL_YAW_OFFSET}.
     *
     * <p>Not read by anything at runtime: it is what the animator's arithmetic
     * (the heading as {@code rotateY(-yaw)}, then {@code RigMath.toItemDisplaySpace})
     * and the client drawing an item display's model half a turn round add up
     * to. {@code BoundFacingTest} holds the arithmetic to it, and Studio
     * reads this constant to check its half turn against it.
     */
    public static final int FRONT_Z = -1;

    private final Items items;
    private final RigStore rigs;
    private final RigAnimator animator;
    private final RigSpawn spawns;
    private final NamespacedKey modelKey;
    private final NamespacedKey partKey;
    private final NamespacedKey boundKey;
    private final NamespacedKey scaleKey;
    private final NamespacedKey yawKey;
    private final NamespacedKey turnKey;
    private final NamespacedKey headPivotKey;
    private final NamespacedKey dyingKey;
    private final NamespacedKey liftKey;

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
        this.yawKey = host.key("rig-yaw");
        this.turnKey = host.key(RigAnimator.TURN_KEY);
        this.headPivotKey = host.key(RigAnimator.HEAD_PIVOT_KEY);
        this.dyingKey = host.key(RigAnimator.DYING_KEY);
        this.liftKey = host.key(RigAnimator.LIFT_KEY);
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

        return dress(host, modelId.toString(), whole, part -> partStack(whole, part), scale, 0f, null);
    }

    /**
     * Puts art on {@code host} with nobody asked: a custom mob the engine
     * spawned itself, so there is no other owner to refuse it and nothing to
     * remember it by but the mob.
     *
     * <p>The art arrives as stacks rather than as an item id, because a mob
     * pushed from Studio has no item: its model is paper wearing a string,
     * exactly as a pushed vehicle's is.
     *
     * @param rigId    the rig store's key: an item id, or Studio's carrier string
     * @param partItem what a part renders as, from the part's own item string
     * @param turn     degrees added to the heading, for a model built facing elsewhere
     * @param headBone a bone that turns toward the target, or null
     */
    public boolean dress(Entity host, String rigId, ItemStack whole,
                         java.util.function.Function<String, ItemStack> partItem,
                         float scale, float turn, String headBone) {
        if (host == null || !host.isValid() || rigId == null || whole == null) {
            return false;
        }
        for (Entity rider : List.copyOf(host.getPassengers())) {
            if (isPart(rider)) {
                animator.untrack(rider.getUniqueId());
                rider.remove();
            }
        }
        float size = scale > 0 ? scale : 1f;
        RigStore.Rig rig = rigs.get(rigId);

        List<ItemDisplay> parts = new ArrayList<>();
        if (rig != null && rig.parts != null && !rig.parts.isEmpty()) {
            // An animated model: one display per part, exactly as a placed rig
            // gets. The yaw passed here is the host's at this instant and is
            // only the starting value — the animator reads the live one.
            parts.addAll(spawns.parts(host.getLocation().getBlock(), rigId, rig,
                    host.getLocation().getYaw(), null, size, part -> partItem.apply(part.item)));
        } else {
            // A still model is one display holding the item itself. Bound the
            // same way and read by the same code, so a model that gains an
            // animation later needs nothing changed here.
            parts.add(ai.resourcepack.engine.core.version.Spawning.spawn(host.getWorld(), host.getLocation(), ItemDisplay.class, d -> {
                d.setItemStack(whole);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, rigId);
                d.getPersistentDataContainer().set(partKey, PersistentDataType.INTEGER, 0);
                if (size != 1f) {
                    d.getPersistentDataContainer().set(scaleKey, PersistentDataType.FLOAT, size);
                }
            }));
        }
        if (parts.isEmpty()) {
            return false;
        }

        float[] headPivot = headBone == null || headBone.isEmpty() ? null : headPivot(rig, headBone);
        for (ItemDisplay part : parts) {
            PersistentDataContainer pdc = part.getPersistentDataContainer();
            pdc.set(boundKey, PersistentDataType.STRING, host.getUniqueId().toString());
            if (turn != 0f) {
                pdc.set(turnKey, PersistentDataType.FLOAT, turn);
            }
            // Every heading a worn part has is in its matrix, still parts
            // included (the animator poses those too, see poseWornStill), so
            // the entity itself faces nowhere in particular. RigSpawn leaves a
            // still part its spawn yaw, which here would turn it twice.
            part.setRotation(0f, 0f);
            if (headPivot != null && inLineage(rig, pdc.get(partKey, PersistentDataType.INTEGER), headBone)) {
                pdc.set(headPivotKey, PersistentDataType.STRING,
                        headPivot[0] + "," + headPivot[1] + "," + headPivot[2]);
            }
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

    /**
     * Where a named head bone turns about, in model pixels, or null if no
     * moving part is that bone.
     *
     * <p>A bone's own pivot is on the manifest only when it has a behaviour,
     * and a Studio rig carries none; what both kinds DO carry is the program
     * of every moving part, whose last bone step pivots about that bone. So a
     * head that never moves in any animation cannot turn either: it was
     * merged into the still remainder at pack build and has no display of its
     * own to turn.
     */
    private static float[] headPivot(RigStore.Rig rig, String headBone) {
        if (rig == null || rig.parts == null) {
            return null;
        }
        for (RigStore.Part part : rig.parts) {
            if (part == null || part.bone == null || !part.bone.equalsIgnoreCase(headBone)) {
                continue;
            }
            if (part.pivot != null && part.pivot.length == 3) {
                return part.pivot.clone();
            }
            if (part.program != null) {
                for (int i = part.program.size() - 1; i >= 0; i--) {
                    RigStore.Step step = part.program.get(i);
                    if (step != null && step.target != null && step.target.startsWith("g:")
                            && step.pivot != null && step.pivot.length == 3) {
                        return step.pivot.clone();
                    }
                }
            }
            if (part.anchor != null && part.anchor.length == 3) {
                return new float[]{part.anchor[0] + 8f, part.anchor[1] + 8f, part.anchor[2] + 8f};
            }
        }
        return null;
    }

    /** Whether part {@code index} is the head bone or inside it, so it turns with it. */
    private static boolean inLineage(RigStore.Rig rig, Integer index, String headBone) {
        if (rig == null || rig.parts == null || index == null || index < 0 || index >= rig.parts.size()) {
            return false;
        }
        RigStore.Part part = rig.parts.get(index);
        if (part == null) {
            return false;
        }
        if (part.bone != null && part.bone.equalsIgnoreCase(headBone)) {
            return true;
        }
        if (part.bones != null) {
            for (String bone : part.bones) {
                if (bone != null && bone.equalsIgnoreCase(headBone)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * How long an animation of what {@code host} wears runs, in seconds at its
     * own speed, or -1 when it has no such animation.
     */
    public double animationSeconds(Entity host, String animation) {
        List<ItemDisplay> parts = partsOf(host);
        if (parts.isEmpty() || animation == null) {
            return -1;
        }
        RigStore.Rig rig = rigs.get(parts.get(0).getPersistentDataContainer().get(modelKey, PersistentDataType.STRING));
        RigStore.Animation found = RigAnimations.animationAt(rig,
                RigAnimations.findAnimationIndexByName(rig, animation));
        if (found == null) {
            return -1;
        }
        return Math.max(0, found.length) / RigAnimations.speedOf(found);
    }

    /**
     * Lets go of what {@code host} wears so it can outlive the host for a
     * moment: a mob's death animation, which runs past the second vanilla
     * keeps a dead body.
     *
     * <p>Made non-persistent, so a part caught by a restart or an unload in
     * that window is not saved at all, and marked dying, so one that somehow
     * was is removed the moment it loads (see {@code RigAnimator.reaped}). Its
     * heading is written down, because a part whose host is gone reads the
     * stored one, and the one stored at spawn is wherever the mob faced then.
     *
     * @return the parts, for the caller to remove when the animation is over
     */
    public List<ItemDisplay> release(Entity host) {
        List<ItemDisplay> parts = partsOf(host);
        float yaw = host == null ? 0f : host.getLocation().getYaw();
        for (ItemDisplay part : parts) {
            PersistentDataContainer pdc = part.getPersistentDataContainer();
            Float turn = pdc.get(turnKey, PersistentDataType.FLOAT);
            pdc.set(yawKey, PersistentDataType.FLOAT, yaw + (turn == null ? 0f : turn));
            // And its height, for the same reason: once the body is removed
            // the part rides nothing, and the lift is measured off what it
            // rides. See RigAnimator.applyGroundLift.
            pdc.set(liftKey, PersistentDataType.FLOAT, animator.groundLift(part, pdc));
            pdc.set(dyingKey, PersistentDataType.BYTE, (byte) 1);
            part.setPersistent(false);
        }
        return parts;
    }

    /** Removes released parts, now, on the calling thread. */
    public void discard(List<ItemDisplay> parts) {
        for (ItemDisplay part : parts) {
            animator.untrack(part.getUniqueId());
            if (part.isValid()) {
                part.remove();
            }
        }
    }

    /**
     * How high a rig's {@code tag_name} bone stands above the model's floor at
     * rest, in model blocks (before any scale), or NaN when it has none. Its
     * pivot, which is the point the bone is placed by.
     */
    public double nameBone(String rigId) {
        RigStore.Rig rig = rigId == null ? null : rigs.get(rigId);
        if (rig == null || rig.parts == null) {
            return Double.NaN;
        }
        for (RigStore.Part part : rig.parts) {
            if (HeadLook.behaviourOf(part) == ai.resourcepack.engine.api.BoneBehaviour.NAMETAG
                    && part.pivot != null && part.pivot.length == 3) {
                return part.pivot[1] / 16.0;
            }
        }
        return Double.NaN;
    }

    /** The displays {@code host} wears, if it wears one of our models. */
    public List<ItemDisplay> parts(Entity host) {
        return partsOf(host);
    }

    /**
     * Whether {@code part} is a piece of an animated rig, rather than the one
     * display a still model is drawn as. A rig's parts are drawn through
     * item definitions this engine and Studio write (and tint); a still model
     * is the whole item, whose definition is the author's.
     */
    public boolean rigPart(ItemDisplay part) {
        String modelId = part.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
        RigStore.Rig rig = modelId == null ? null : rigs.get(modelId);
        return rig != null && rig.parts != null && !rig.parts.isEmpty();
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
        // See Items.wearModel: below 1.21.4 a model is a number, not a name.
        ContentId.parse(partId).ifPresent(part -> items.wearModel(stack, part));
        return stack;
    }
}
