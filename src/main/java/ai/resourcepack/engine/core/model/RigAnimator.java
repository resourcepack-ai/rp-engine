package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Keyframe;
import ai.resourcepack.engine.api.BoneBehaviour;
import ai.resourcepack.engine.api.Placement;
import ai.resourcepack.engine.api.event.ModelAnimationEvent;
import ai.resourcepack.engine.core.Host;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives placed animation rigs: a repeating task samples each model's
 * animation on a per-placement clock and retimes every tracked part
 * display's transformation, letting vanilla's own display interpolation
 * tween the 2-tick gaps into smooth motion. The math mirrors the editor's
 * viewport applier (the editor's animation applier):
 * pose = T(pivot + position) * Rxyz * S * T(-pivot),
 * composed per program step, with the placement yaw baked in up front
 * (part displays spawn with entity yaw 0).
 *
 * Rig part displays are ordinary persistent entities - after a restart or
 * chunk load they're re-tracked via the world scan in {@link #start()} and
 * {@link EntitiesLoadEvent}, keyed off the part-index marker in their
 * persistent data.
 */
public final class RigAnimator implements Listener {

    static final int PERIOD_TICKS = 2;
    /** PDC key holding a placement's chosen animation name, if it has one. */
    static final String ANIMATION_CHOICE_KEY = "rig-animation";

    /** PDC key holding a placed rig's size multiplier. See ModelPlacementListener. */
    static final String SCALE_KEY = "rig-scale";

    /**
     * PDC key marking a part that is worn by a living entity rather than
     * standing in a block space.
     *
     * <p>The one thing a bound part needs that a placed one does not: its yaw
     * is wherever its host is looking THIS tick, not the yaw somebody placed
     * it at. Everything else about animating it is identical, which is why
     * binding is a flag on a part rather than a second animator.
     */
    static final String BOUND_KEY = "rig-bound";

    /**
     * PDC key holding the overlays a part is playing: {@code index:startTick}
     * pairs, semicolon separated.
     *
     * <p>Its own key rather than a second copy of the base machinery, and the
     * base path is untouched. A rig with no overlays behaves byte for byte as
     * it did before layers existed \u2014 which matters, because this is the most
     * load-bearing code in the plugin and every pushed rig in the world runs
     * through it.
     */
    static final String OVERLAY_KEY = "rig-overlays";
    static final String TRIGGER_LOOP = "loop";
    static final String TRIGGER_RIGHT_CLICK = "right_click";
    static final String TRIGGER_LEFT_CLICK = "left_click";
    static final String TRIGGER_RANGE = "range";
    static final String TRIGGER_PLACE = "place";

    /** What an animation does when it reaches its end. */
    static final String MODE_LOOP = "loop";
    static final String MODE_HOLD = "hold";
    static final String MODE_ONCE = "once";

    private static final int RANGE_SCAN_INTERVAL = 5;

    /**
     * How far a neck turns, in degrees. Vanilla's own for a player's head,
     * and near enough for everything else — the alternative is a mob whose
     * head is on backwards, which is what the raw numbers say.
     */
    private static final float MAX_NECK_YAW = 75f;
    private static final float MAX_NECK_PITCH = 89f;

    private static final float[] ZERO = { 0f, 0f, 0f };
    private static final float[] ONE = { 1f, 1f, 1f };

    private final Host host;
    private final RigStore rigs;
    private final BoneParts bones;
    private final NamespacedKey modelKey;
    private final NamespacedKey partKey;
    private final NamespacedKey yawKey;
    private final NamespacedKey animationStartKey;
    private final NamespacedKey activeAnimationKey;
    private final NamespacedKey animationKey;
    private final NamespacedKey scaleKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey displaysKey;
    private final NamespacedKey boundKey;
    private final NamespacedKey overlayKey;

    private final Map<UUID, ItemDisplay> tracked = new ConcurrentHashMap<>();
    private final Map<UUID, Interaction> hitboxes = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> rangeOccupants = new ConcurrentHashMap<>();
    // part display id -> its hitbox id. A display holds no back-reference, so
    // without this the code API's placementOf(display) has to read every
    // tracked hitbox's id list to find the one that claims it. Built from the
    // hitbox's own list when it's tracked, so it needs nothing at spawn time.
    private final Map<UUID, UUID> hitboxOfDisplay = new ConcurrentHashMap<>();

    /**
     * A crossfade in progress, per part display.
     *
     * <p><strong>In memory on purpose.</strong> A blend is a fraction of a
     * second, so a chunk unload or a restart in the middle of one costs
     * nothing worth persisting — the part arrives at the new pose the moment
     * it is tracked again, which is where it was going anyway. Writing it to
     * persistent data would be ten floats on every part on every tick of every
     * transition, saved to a world, to smooth something already over.
     */
    private final Map<UUID, Blend> blends = new ConcurrentHashMap<>();

    /** Which animation each display was last posed for, so a change is visible. */
    private final Map<UUID, Integer> lastPosed = new ConcurrentHashMap<>();

    /** Where a part was when its animation changed, and how long it has to arrive. */
    private static final class Blend {

        private final Transformation from;
        private final long startedTick;
        private final double ticks;

        Blend(Transformation from, long startedTick, double seconds) {
            this.from = from;
            this.startedTick = startedTick;
            this.ticks = seconds * 20;
        }

        /** 0 at the moment it started, 1 when it is over. */
        float progress(long now) {
            if (ticks <= 0) return 1f;
            return (float) Math.min(1, Math.max(0, (now - startedTick) / ticks));
        }
    }
    private int taskId = -1;
    private int rangeScanCountdown;
    /**
     * How to turn a hitbox into the handle {@link ModelAnimationEvent} carries.
     *
     * <p>Set once by the library at startup rather than passed in, because the
     * thing that builds placements is itself built on top of this animator -
     * and an event nobody can listen to yet is not worth a constructor cycle.
     */
    private java.util.function.Function<Interaction, Placement> placements;

    public RigAnimator(Host host, RigStore rigs) {
        this.host = host;
        this.rigs = rigs;
        this.bones = new BoneParts(host);
        this.modelKey = host.key("model-id");
        this.partKey = host.key("part-index");
        this.yawKey = host.key("rig-yaw");
        this.animationStartKey = host.key("rig-animation-start");
        this.activeAnimationKey = host.key("rig-active-animation");
        this.animationKey = host.key(ANIMATION_CHOICE_KEY);
        this.scaleKey = host.key(SCALE_KEY);
        this.displayKey = host.key("display-uuid");
        this.displaysKey = host.key("display-uuids");
        this.boundKey = host.key(BOUND_KEY);
        this.overlayKey = host.key(OVERLAY_KEY);
    }

    public void placements(java.util.function.Function<Interaction, Placement> placements) {
        this.placements = placements;
    }

    public void start() {
        taskId = Bukkit.getScheduler().runTaskTimer(host.plugin(), this::tick, PERIOD_TICKS, PERIOD_TICKS).getTaskId();
        // Pick up rigs already standing in loaded chunks (plugin reload,
        // server restart). Unloaded ones arrive via EntitiesLoadEvent.
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                track(display);
            }
            for (Interaction hitbox : world.getEntitiesByClass(Interaction.class)) {
                track(hitbox);
            }
        }
    }

    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        tracked.clear();
        hitboxes.clear();
        rangeOccupants.clear();
        hitboxOfDisplay.clear();
        blends.clear();
        lastPosed.clear();
    }

    /** Registers an entity if it's one of our moving rig part displays. */
    void track(Entity entity) {
        if (entity instanceof Interaction) {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            if (pdc.has(modelKey, PersistentDataType.STRING)
                && (pdc.has(displaysKey, PersistentDataType.STRING) || pdc.has(displayKey, PersistentDataType.STRING))) {
                hitboxes.put(entity.getUniqueId(), (Interaction) entity);
                indexDisplays((Interaction) entity);
            }
            return;
        }
        if (!(entity instanceof ItemDisplay)) return;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (!pdc.has(partKey, PersistentDataType.INTEGER)) return;
        String modelId = pdc.get(modelKey, PersistentDataType.STRING);
        Integer partIndex = pdc.get(partKey, PersistentDataType.INTEGER);
        RigStore.Rig rig = rigs.get(modelId);
        RigStore.Part part = rig != null && rig.parts != null && partIndex != null
                && partIndex >= 0 && partIndex < rig.parts.size()
                ? rig.parts.get(partIndex)
                : null;

        // Before the early-out below, because a bone's behaviour has nothing
        // to do with whether it animates: a seat on a still bone is a chair,
        // and a hitbox on one is most of a statue. Rebuilt rather than saved,
        // so a rig whose bones were renamed gets the right set back on the
        // next reload; attach clears before it builds.
        if (part != null) {
            bones.attach((ItemDisplay) entity, part);
        }

        if (part != null && !hasAnimationProgram(part)) {
            // Static remainder parts keep their entity yaw and never need
            // transformation metadata resent by the animation task.
            return;
        }
        // In world game ticks, so parts placed in the same tick stay in phase
        // across chunk loads and restarts. Older rigs start when first tracked.
        if (!pdc.has(animationStartKey, PersistentDataType.LONG)) {
            pdc.set(animationStartKey, PersistentDataType.LONG, entity.getWorld().getGameTime());
        }
        tracked.put(entity.getUniqueId(), (ItemDisplay) entity);
    }

    /** The sub-entities bones asked for, so the listener can read them. */
    public BoneParts bones() {
        return bones;
    }

    void untrack(UUID id) {
        tracked.remove(id);
        blends.remove(id);
        lastPosed.remove(id);
    }

    void untrackHitbox(UUID id) {
        hitboxes.remove(id);
        rangeOccupants.remove(id);
        hitboxOfDisplay.values().removeIf(id::equals);
    }

    /** Records which hitbox owns each of its part displays. See hitboxOfDisplay. */
    private void indexDisplays(Interaction hitbox) {
        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        String joined = pdc.get(displaysKey, PersistentDataType.STRING);
        if (joined == null) joined = pdc.get(displayKey, PersistentDataType.STRING);
        if (joined == null || joined.isEmpty()) return;
        for (String raw : joined.split(",")) {
            try {
                hitboxOfDisplay.put(UUID.fromString(raw), hitbox.getUniqueId());
            } catch (IllegalArgumentException ignored) {
                // A malformed or stale id costs that one part its back-reference
                // and nothing else, exactly as in displaysOf.
            }
        }
    }

    /** Immediate one-off pose - placement calls this so a freshly spawned rig never flashes unrotated. */
    void poseNow(ItemDisplay display) {
        pose(display, true);
    }

    /**
     * Starts the animation this placement plays for the event: its chosen one
     * where that claims the trigger, else the first claimant. All rig parts
     * receive the same clock.
     */
    boolean trigger(Interaction hitbox, String triggerType) {
        return trigger(hitbox, triggerType, null);
    }

    /**
     * As {@link #trigger(Interaction, String)}, naming whoever set it off.
     *
     * <p>Only so {@link ModelAnimationEvent} can say who clicked. Nothing about
     * the animation itself depends on it - a rig plays the same whoever is
     * standing in front of it.
     */
    boolean trigger(Interaction hitbox, String triggerType, Player player) {
        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        String modelId = pdc.get(modelKey, PersistentDataType.STRING);
        RigStore.Rig rig = rigs.get(modelId);
        int animationIndex = findAnimationIndex(rig, triggerType, pdc.get(animationKey, PersistentDataType.STRING));
        return animationIndex >= 0 && startAnimation(hitbox, rig, animationIndex, causeOf(triggerType), player);
    }

    /** Which cause an event should report for a trigger type. */
    private static ModelAnimationEvent.Cause causeOf(String triggerType) {
        if (TRIGGER_PLACE.equals(triggerType)) return ModelAnimationEvent.Cause.PLACE;
        if (TRIGGER_RIGHT_CLICK.equals(triggerType)) return ModelAnimationEvent.Cause.RIGHT_CLICK;
        if (TRIGGER_LEFT_CLICK.equals(triggerType)) return ModelAnimationEvent.Cause.LEFT_CLICK;
        if (TRIGGER_RANGE.equals(triggerType)) return ModelAnimationEvent.Cause.RANGE;
        return ModelAnimationEvent.Cause.API;
    }

    boolean hasTrigger(String modelId, String triggerType) {
        return findAnimationIndex(rigs.get(modelId), triggerType) >= 0;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            track(entity);
        }
    }

    private void tick() {
        Iterator<Map.Entry<UUID, ItemDisplay>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            ItemDisplay display = it.next().getValue();
            // Removed (broken rig) or unloaded with its chunk - either way,
            // drop it; a chunk reload re-tracks via EntitiesLoadEvent.
            if (!display.isValid()) {
                it.remove();
                // And the bookkeeping that hangs off it. Both are keyed by
                // entity id and nothing else prunes them, so a server whose
                // rigs load and unload all day would grow two maps for ever.
                blends.remove(display.getUniqueId());
                lastPosed.remove(display.getUniqueId());
                continue;
            }
            pose(display, false);
        }
        if (--rangeScanCountdown <= 0) {
            rangeScanCountdown = RANGE_SCAN_INTERVAL;
            scanRanges();
        }
    }

    private void pose(ItemDisplay display, boolean forceRestPose) {
        PersistentDataContainer pdc = display.getPersistentDataContainer();
        String modelId = pdc.get(modelKey, PersistentDataType.STRING);
        Integer partIndex = pdc.get(partKey, PersistentDataType.INTEGER);
        if (modelId == null || partIndex == null) return;
        RigStore.Rig rig = rigs.get(modelId);
        if (rig == null || rig.parts == null || partIndex < 0 || partIndex >= rig.parts.size()) {
            // The rig changed shape under a placement already in the world,
            // usually a re-push that renumbered parts. Returning here left a
            // part caught mid-animation hidden or displaced forever, so put it
            // back to rest; it stays tracked and resumes if the rig returns.
            applyRestPose(display, pdc);
            return;
        }
        RigStore.Part part = rig.parts.get(partIndex);
        if (!hasAnimationProgram(part)) {
            // Covers a part tracked before its rig manifest became available.
            tracked.remove(display.getUniqueId());
            return;
        }
        Float yaw = yawOf(display, pdc);
        Long animationStart = pdc.get(animationStartKey, PersistentDataType.LONG);

        Matrix4f animationTransform = new Matrix4f();

        Integer activeIndex = pdc.get(activeAnimationKey, PersistentDataType.INTEGER);
        double elapsed = animationStart == null
            ? 0
            : Math.max(0, display.getWorld().getGameTime() - animationStart) / 20.0;
        // The choice decides the resting loop too, not just one-shots: two
        // animations can both claim `loop`.
        int playbackIndex =
            playbackAnimationIndex(rig, activeIndex, elapsed, pdc.get(animationKey, PersistentDataType.STRING));

        // Event-only rigs are dormant between triggers. Resending the resting
        // transform every tick restarts the client's interpolation, which
        // looks like the animation is stuck leaving its first frame.
        //
        // A bound part is exempt: its yaw is its host's, so "nothing has
        // changed" is false the moment the mob turns its head. The transform
        // is compared below anyway, so a host standing still still sends
        // nothing.
        // A change of what is playing — including to and from nothing — is
        // where a crossfade starts. Reading it off the last pose rather than
        // off the animation start means going BACK to rest eases out too,
        // which is the half a "lerp out" setting usually means.
        long tick = display.getWorld().getGameTime();
        Integer posedFor = lastPosed.get(display.getUniqueId());
        if (posedFor == null || posedFor != playbackIndex) {
            double seconds = Math.max(
                    blendOf(animationAt(rig, playbackIndex)),
                    posedFor == null ? 0 : blendOf(animationAt(rig, posedFor)));
            if (seconds > 0 && posedFor != null) {
                blends.put(display.getUniqueId(), new Blend(display.getTransformation(), tick, seconds));
            }
            lastPosed.put(display.getUniqueId(), playbackIndex);
        }
        Blend blend = blends.get(display.getUniqueId());

        // An overlay plays over a base that may itself be at rest, so
        // "nothing is animating" is not a reason to stop sending frames.
        boolean overlaid = pdc.has(overlayKey, PersistentDataType.STRING);
        boolean bound = pdc.has(boundKey, PersistentDataType.STRING);
        // A blend has to keep sending frames even where nothing else would:
        // the animation is not changing, the pose on the way to it is.
        if (!bound && !overlaid && blend == null
                && !shouldUpdatePose(playbackIndex, activeIndex, forceRestPose)) return;

        if (activeIndex != null && playbackIndex != activeIndex) {
            pdc.remove(activeAnimationKey);
            if (playbackIndex < 0) pdc.remove(animationStartKey);
        }

        RigStore.Animation animation = animationAt(rig, playbackIndex);
        if (animation != null && moves(animation, part)) {
            double t = animationTime(animation, elapsed);
            float weight = weightOf(animation);
            for (RigStore.Step step : part.program) {
                applyStep(animationTransform, animation.animators, step.target, step.pivot, t, weight);
            }
        }
        // Layers above the base, composed on top of it in layer order. A
        // wave over a walk cycle: the arm's rotation from the wave multiplies
        // into whatever the walk had already done to it, rather than
        // replacing it.
        applyOverlays(animationTransform, rig, part, pdc, display.getWorld().getGameTime());

        // After the animations, not instead of them: a head bone still plays
        // whatever the walk cycle does to it, and looking around is composed
        // on top. Doing it the other way makes an idle animation drag the
        // head back off whatever it was looking at.
        applyHeadLook(animationTransform, part, display);

        Matrix4f m = new Matrix4f();
        if (yaw != null && yaw != 0f) m.rotateY((float) Math.toRadians(-yaw));
        m.mul(toItemDisplaySpace(animationTransform));
        applyRigScale(m, pdc);

        Transformation next = toTransformation(m);
        if (blend != null) {
            float progress = blend.progress(tick);
            if (progress >= 1f) {
                blends.remove(display.getUniqueId());
            } else {
                next = mix(blend.from, next, progress);
            }
        }
        // Keyframe holds sample to an identical transform - nothing to send,
        // and skipping keeps the delay toggle below from re-arming idle parts.
        if (next.equals(display.getTransformation())) return;

        // The client re-arms interpolation only when the start-delta metadata
        // item actually arrives, and an unchanged value is deduplicated out of
        // the packet: a plain setInterpolationDelay(0) is sent once, and every
        // later transform then evaluates against that long-past start tick, so
        // it snaps instead of tweening and playback steps at 10 Hz. Toggling
        // dirties the item so every packet re-arms from the rendered pose.
        display.setInterpolationDelay(1);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(forceRestPose && animation == null ? 0 : PERIOD_TICKS);
        display.setTransformation(next);
    }

    /**
     * Which way this part is facing.
     *
     * <p>For a placed rig, the yaw somebody put it down at, stored once. For a
     * bound one, whichever way its host is looking right now \u2014 read off the
     * vehicle rather than stored, because a mob turns and nothing would write
     * a new value on the tick it did.
     */
    private Float yawOf(ItemDisplay display, PersistentDataContainer pdc) {
        if (pdc.has(boundKey, PersistentDataType.STRING)) {
            Entity host = display.getVehicle();
            return host == null
                    // Its host is gone, or the chunk holding it is. The last
                    // stored yaw beats snapping to north.
                    ? pdc.get(yawKey, PersistentDataType.FLOAT)
                    : host.getLocation().getYaw();
        }
        return pdc.get(yawKey, PersistentDataType.FLOAT);
    }

    // The pose a part holds when nothing is animating it: placement yaw only.
    // Sent without interpolation, since this is recovery, not playback.
    private void applyRestPose(ItemDisplay display, PersistentDataContainer pdc) {
        Float yaw = pdc.get(yawKey, PersistentDataType.FLOAT);
        Matrix4f m = new Matrix4f();
        if (yaw != null && yaw != 0f) m.rotateY((float) Math.toRadians(-yaw));
        m.mul(toItemDisplaySpace(new Matrix4f()));
        applyRigScale(m, pdc);

        Transformation next = toTransformation(m);
        if (next.equals(display.getTransformation())) return;
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);
        display.setTransformation(next);
    }

    /**
     * Grows a posed matrix by the rig's placement scale, about the block floor.
     *
     * Pre-multiplied, not appended: every part display of a rig sits at the
     * SAME block centre and carries its offset from that centre in the matrix
     * translation, so scaling the whole thing about that shared origin moves
     * the parts apart by exactly as much as it grows them. Appending instead
     * would scale each part in place and leave the rig hollow at the joints.
     *
     * Then lifted by half the growth: an item display renders centred on the
     * entity, so a 2x model would otherwise sink half a block into the floor.
     * This keeps the base of the rig on the block it was placed against, which
     * is what "bigger" means to somebody placing a statue.
     */
    private void applyRigScale(Matrix4f m, PersistentDataContainer pdc) {
        Float scale = pdc.get(scaleKey, PersistentDataType.FLOAT);
        if (scale == null || scale == 1f || !Float.isFinite(scale) || scale <= 0f) return;
        m.scaleLocal(scale);
        m.translateLocal(0f, 0.5f * (scale - 1f), 0f);
    }

    /** The same transform for a part with no animation program — see applyRigScale. */
    static Transformation scaledTransformation(float scale) {
        Matrix4f m = new Matrix4f();
        m.scaleLocal(scale);
        m.translateLocal(0f, 0.5f * (scale - 1f), 0f);
        return toTransformation(m);
    }

    static boolean hasAnimationProgram(RigStore.Part part) {
        return part != null && part.program != null && !part.program.isEmpty();
    }

    /**
     * ItemDisplayRenderer rotates the rendered item 180 degrees around Y
     * after applying the display transformation. The editor animation is
     * authored before that built-in rotation, so conjugate the complete
     * model-space transform into the item's displayed coordinate space.
     */
    public static Matrix4f toItemDisplaySpace(Matrix4f modelTransform) {
        return new Matrix4f()
            .rotateY((float) Math.PI)
            .mul(modelTransform)
            .rotateY((float) -Math.PI);
    }

    static int findAnimationIndex(RigStore.Rig rig, String triggerType) {
        return findAnimationIndex(rig, triggerType, null);
    }

    /**
     * The animation this event should start, preferring the placement's own
     * choice (see {@link ModelPlacementListener}). Several animations can
     * claim one trigger and only one may run, so without a choice the first
     * in rig order wins and the second is unreachable.
     *
     * Falling back to the first claimant is deliberate: a choice names ONE
     * animation, and a model's other triggers usually belong to different
     * ones, so picking "Close" for right-click leaves the idle loop alone.
     */
    static int findAnimationIndex(RigStore.Rig rig, String triggerType, String chosenName) {
        if (rig == null || rig.animations == null || triggerType == null) return -1;
        if (chosenName != null && !chosenName.isEmpty()) {
            for (int i = 0; i < rig.animations.size(); i++) {
                RigStore.Animation animation = rig.animations.get(i);
                if (animation != null && chosenName.equals(animation.name) && hasTrigger(animation, triggerType)) {
                    return i;
                }
            }
        }
        // Highest priority among the claimants, and rig order between equals
        // — which is exactly what this did before priority existed, since
        // every animation then had the same one.
        int best = -1;
        for (int i = 0; i < rig.animations.size(); i++) {
            if (!hasTrigger(rig.animations.get(i), triggerType)) continue;
            if (best < 0 || priorityOf(rig.animations.get(i)) > priorityOf(rig.animations.get(best))) {
                best = i;
            }
        }
        return best;
    }

    /**
     * The animation with this name, and the resolver the code API runs on.
     *
     * **It asks nothing about triggers**, which is the whole difference from
     * {@link #findAnimationIndex} and the whole point of the API. A model's
     * animations are all shipped in the rig manifest whether or not they
     * claim a trigger — Studio's Minecraft metadata says of an empty trigger list
     * that it "deliberately makes it editor-only" — so those have been
     * sitting on every synced server unreachable, because until now the only
     * way in was by trigger. This is the way in.
     *
     * Names are free text in the editor and nothing makes them unique, so
     * the first match in model order wins: the same rule findAnimationIndex
     * already uses when several animations claim one trigger. An exact match
     * is preferred over a case-insensitive one, so a model that really does
     * have both "Wave" and "wave" keeps both addressable; without that the
     * second would be unreachable, which is the bug this method exists to
     * fix, one level down.
     */
    static int findAnimationIndexByName(RigStore.Rig rig, String name) {
        if (rig == null || rig.animations == null || name == null || name.isEmpty()) return -1;
        int looseMatch = -1;
        for (int i = 0; i < rig.animations.size(); i++) {
            RigStore.Animation animation = rig.animations.get(i);
            if (animation == null || animation.name == null) continue;
            if (name.equals(animation.name)) return i;
            if (looseMatch < 0 && name.equalsIgnoreCase(animation.name)) looseMatch = i;
        }
        return looseMatch;
    }

    /**
     * Every animation this model has, in model order — the list a caller
     * picks a name out of. Unnamed entries are dropped rather than reported
     * as empty strings, since a name is how this API addresses them at all.
     */
    static List<String> animationNames(RigStore.Rig rig) {
        if (rig == null || rig.animations == null) return Collections.emptyList();
        List<String> names = new java.util.ArrayList<>(rig.animations.size());
        for (RigStore.Animation animation : rig.animations) {
            if (animation != null && animation.name != null && !animation.name.isEmpty()) names.add(animation.name);
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Resolves the pose source without causing playback. With no active
     * event, only a loop may animate. A completed one-shot returns to the
     * resting transform unless a separate loop animation is available.
     */
    static int playbackAnimationIndex(RigStore.Rig rig, Integer activeIndex, double elapsed) {
        return playbackAnimationIndex(rig, activeIndex, elapsed, null);
    }

    static int playbackAnimationIndex(RigStore.Rig rig, Integer activeIndex, double elapsed, String chosenName) {
        RigStore.Animation active = animationAt(rig, activeIndex);
        // A held animation never runs out: it stops on its last frame and
        // stays there until something else is asked for. That is what makes
        // an open door stay open rather than swinging shut on its own.
        if (active != null && (loops(active) || holds(active)
                || elapsed * speedOf(active) <= Math.max(0, active.length))) {
            return activeIndex;
        }
        return findAnimationIndex(rig, TRIGGER_LOOP, chosenName);
    }

    static boolean shouldUpdatePose(int playbackIndex, Integer activeIndex, boolean forceRestPose) {
        return playbackIndex >= 0 || activeIndex != null || forceRestPose;
    }

    static boolean hasTrigger(RigStore.Animation animation, String triggerType) {
        if (animation == null || triggerType == null) return false;
        // Backward compatibility with manifests created before the trigger
        // list: loops stayed active; one-shots ran once when placed.
        if (animation.triggers == null) {
            return triggerType.equals(animation.loop ? TRIGGER_LOOP : TRIGGER_PLACE);
        }
        for (RigStore.Trigger trigger : animation.triggers) {
            if (trigger != null && triggerType.equals(trigger.type)) return true;
        }
        return false;
    }

    static double animationTime(RigStore.Animation animation, double elapsed) {
        if (animation.length <= 0) return 0;
        double at = elapsed * speedOf(animation);
        return loops(animation) ? at % animation.length : Math.min(at, animation.length);
    }

    private static boolean loops(RigStore.Animation animation) {
        if (MODE_LOOP.equals(animation.mode)) return true;
        if (MODE_HOLD.equals(animation.mode) || MODE_ONCE.equals(animation.mode)) return false;
        return animation.triggers == null ? animation.loop : hasTrigger(animation, TRIGGER_LOOP);
    }

    /**
     * How fast it plays. Absent or nonsensical is 1 rather than a refusal:
     * every manifest written before this has no speed at all, and a rig that
     * froze because somebody typed a zero would be worse than one that runs
     * at the speed it was authored.
     */
    static double speedOf(RigStore.Animation animation) {
        return animation == null || animation.speed <= 0 ? 1 : animation.speed;
    }

    /** Higher wins. Equal falls back to rig order, as it always did. */
    static int priorityOf(RigStore.Animation animation) {
        return animation == null ? 0 : animation.priority;
    }

    /** Seconds of ease in and out. 0, the old behaviour, is a hard cut. */
    static double blendOf(RigStore.Animation animation) {
        return animation == null || animation.blend <= 0 ? 0 : Math.min(5, animation.blend);
    }

    /** Whether it stops on its last frame instead of going back to rest. */
    static boolean holds(RigStore.Animation animation) {
        return animation != null && MODE_HOLD.equals(animation.mode);
    }

    private boolean startAnimation(Interaction hitbox, RigStore.Rig rig, int animationIndex,
            ModelAnimationEvent.Cause cause, Player player) {
        return startAnimation(hitbox, rig, animationIndex, false, cause, player);
    }

    /**
     * @param restart rewinds a one-shot that is still running. Only the code
     *                API passes true: a trigger firing twice means two
     *                players clicked, which must not re-cut the animation.
     */
    private boolean startAnimation(Interaction hitbox, RigStore.Rig rig, int animationIndex, boolean restart,
            ModelAnimationEvent.Cause cause, Player player) {
        RigStore.Animation animation = animationAt(rig, animationIndex);
        if (animation == null) return false;
        List<ItemDisplay> displays = displaysOf(hitbox);
        if (displays.isEmpty()) return false;
        long now = hitbox.getWorld().getGameTime();

        // Range scans and multiple players may request the same animation in
        // one moment. Do not repeatedly rewind a one-shot that is still live.
        if (!restart && isMidOneShot(displays, animation, animationIndex, now)) return true;

        // Asked after the cheap refusals and before anything moves, so a
        // listener is not woken for a start that was never going to happen.
        // An idle loop resuming on its own does not come through here.
        if (placements != null) {
            ModelAnimationEvent ask =
                new ModelAnimationEvent(placements.apply(hitbox), animation.name, cause, player);
            Bukkit.getPluginManager().callEvent(ask);
            if (ask.isCancelled()) return false;
        }

        for (ItemDisplay display : displays) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            pdc.set(activeAnimationKey, PersistentDataType.INTEGER, animationIndex);
            pdc.set(animationStartKey, PersistentDataType.LONG, now);
            if (pdc.has(partKey, PersistentDataType.INTEGER)) pose(display, false);
        }
        // The library tells Bedrock viewers to play the same keyframes
        // natively here. Nothing in this engine implements that seam yet; when
        // Geyser support lands it goes back exactly here.
        return true;
    }

    /**
     * Plays an animation on displays that have no hitbox.
     *
     * <p>For a rig bound to a living entity: there is nothing to punch, so
     * there is nothing for the trigger and event machinery above to hang off.
     * The clock and the choice live on the part displays either way \u2014 that is
     * what {@link #pose} reads \u2014 so this is the same write with the placement
     * half left out.
     *
     * @return whether anything started
     */
    boolean playOn(List<ItemDisplay> displays, String modelId, String animationName, boolean restart) {
        RigStore.Rig rig = rigs.get(modelId);
        int index = findAnimationIndexByName(rig, animationName);
        RigStore.Animation animation = animationAt(rig, index);
        if (animation == null || displays.isEmpty()) return false;
        if (animation.layer > 0) {
            return playOverlay(displays, modelId, animationName);
        }

        long now = displays.get(0).getWorld().getGameTime();
        if (!restart && isMidOneShot(displays, animation, index, now)) return true;

        for (ItemDisplay display : displays) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            if (!pdc.has(partKey, PersistentDataType.INTEGER)) continue;
            pdc.set(activeAnimationKey, PersistentDataType.INTEGER, index);
            pdc.set(animationStartKey, PersistentDataType.LONG, now);
            pose(display, false);
        }
        return true;
    }

    /** Puts those displays back to rest, or to their idle loop. */
    void stopOn(List<ItemDisplay> displays) {
        // Overlays go too: "stop" means stop, and a wave still playing over a
        // rig that has been told to stop is exactly the sort of thing that
        // reads as the plugin ignoring you.
        clearOverlays(displays);
        for (ItemDisplay display : displays) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            if (!pdc.has(partKey, PersistentDataType.INTEGER)) continue;
            pdc.remove(activeAnimationKey);
            pdc.set(animationStartKey, PersistentDataType.LONG, display.getWorld().getGameTime());
            pose(display, true);
        }
    }

    /**
     * Whether a one-shot at this index is still running. Read off the first
     * display only — every part of a rig is started on one clock, so they
     * agree by construction and asking them all is the same answer N times.
     */
    private boolean isMidOneShot(List<ItemDisplay> displays, RigStore.Animation animation, int animationIndex, long now) {
        for (ItemDisplay display : displays) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            return isMidOneShot(animation, pdc.get(activeAnimationKey, PersistentDataType.INTEGER),
                pdc.get(animationStartKey, PersistentDataType.LONG), animationIndex, now);
        }
        return false;
    }

    /**
     * The rule itself, with the entity read taken out so it can be pinned by a
     * test: is a one-shot at {@code wantIndex} still running?
     *
     * A loop is never "mid" anything — it has no end to be before, so asking
     * for one always restarts its phase. Ticks rather than seconds because
     * that's what the clock on the display is in.
     */
    static boolean isMidOneShot(RigStore.Animation animation, Integer activeIndex, Long startedTick,
            int wantIndex, long now) {
        if (animation == null || loops(animation)) return false;
        return activeIndex != null && activeIndex == wantIndex && startedTick != null
            && now - startedTick <= Math.ceil(animation.length * 20.0);
    }

    /**
     * Whether a rig has anything that can move, which is what separates a real
     * rig from a model that places as one still display. The still one still
     * gets a hitbox and a model id, so this is the only thing telling them
     * apart — see the guard in {@link #play}.
     */
    static boolean anyPartAnimates(RigStore.Rig rig) {
        if (rig == null || rig.parts == null) return false;
        for (RigStore.Part part : rig.parts) {
            if (hasAnimationProgram(part)) return true;
        }
        return false;
    }

    // --- the code API (ModelAnimations / ModelPlacement) ---------------
    // Everything below is reached from RigPlacements. It lives here because
    // the PDC keys and the rig store do, and a second class reading those
    // is a second place for them to be read wrongly.

    /**
     * Plays a named animation on a placement regardless of what triggers it
     * claims — see {@link #findAnimationIndexByName}. False rather than an
     * exception for every ordinary way this fails: the name is unknown, the
     * rig has nothing that moves, or the placement was broken a tick ago.
     */
    boolean play(Interaction hitbox, String animationName, boolean restart) {
        if (hitbox == null || !hitbox.isValid()) return false;
        RigStore.Rig rig = rigOf(hitbox);
        int index = findAnimationIndexByName(rig, animationName);
        if (index < 0) return false;
        List<ItemDisplay> displays = displaysOf(hitbox);
        // An animation that says which layer it belongs on is asking to play
        // OVER whatever is running, not instead of it. Routed here rather than
        // through a second method, so every caller gets it and none of them
        // has to know layers exist.
        if (animationAt(rig, index).layer > 0) {
            return playOverlay(displays, modelIdOf(hitbox), animationName);
        }
        // A model with no rig parts places as one still display: it has a
        // hitbox and a model id like any rig, so without this the PDC below
        // would be written and success reported for something that cannot
        // move. Vanilla fires no event to notice that by.
        if (!hasMovingPart(displays)) return false;
        if (!restart && isMidOneShot(displays, animationAt(rig, index), index, hitbox.getWorld().getGameTime())) {
            // Deliberately false where a trigger gets true: a trigger means
            // "this event is handled", an API call asks "did my request take
            // effect", and it did not.
            return false;
        }
        return startAnimation(hitbox, rig, index, restart, ModelAnimationEvent.Cause.API, null);
    }

    /**
     * Puts a placement back to rest, or to its idle loop if it has one —
     * the same place a one-shot goes when it runs out.
     *
     * The clock is restarted rather than cleared: a null start reads as
     * elapsed 0 on every later tick, which would freeze an idle loop on its
     * first frame instead of letting it run.
     */
    boolean stop(Interaction hitbox) {
        if (hitbox == null || !hitbox.isValid()) return false;
        clearOverlays(displaysOf(hitbox));
        boolean wasPlaying = false;
        for (ItemDisplay display : displaysOf(hitbox)) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            if (!pdc.has(partKey, PersistentDataType.INTEGER)) continue;
            if (pdc.has(activeAnimationKey, PersistentDataType.INTEGER)) wasPlaying = true;
            pdc.remove(activeAnimationKey);
            pdc.set(animationStartKey, PersistentDataType.LONG, display.getWorld().getGameTime());
            pose(display, true);
        }
        return wasPlaying;
    }

    /**
     * What a placement is showing, idle loop included — resolved exactly the
     * way {@link #pose} resolves it, so this reports what is on screen
     * rather than only what was last asked for. Null when at rest.
     */
    String playingOn(Interaction hitbox) {
        if (hitbox == null || !hitbox.isValid()) return null;
        RigStore.Rig rig = rigOf(hitbox);
        String chosen = hitbox.getPersistentDataContainer().get(animationKey, PersistentDataType.STRING);
        for (ItemDisplay display : displaysOf(hitbox)) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            if (!pdc.has(partKey, PersistentDataType.INTEGER)) continue;
            Integer active = pdc.get(activeAnimationKey, PersistentDataType.INTEGER);
            Long started = pdc.get(animationStartKey, PersistentDataType.LONG);
            double elapsed = started == null
                ? 0
                : Math.max(0, display.getWorld().getGameTime() - started) / 20.0;
            RigStore.Animation animation = animationAt(rig, playbackAnimationIndex(rig, active, elapsed, chosen));
            return animation == null ? null : animation.name;
        }
        return null;
    }

    /** The rig hitbox an entity belongs to: itself, or the one owning this part display. */
    Interaction hitboxOf(Entity entity) {
        if (entity == null) return null;
        if (entity instanceof Interaction) {
            return isRigHitbox(entity) ? (Interaction) entity : null;
        }
        if (!(entity instanceof ItemDisplay)) return null;
        // Straight off the reverse index — a display holds no back-reference,
        // and searching every tracked hitbox's id list instead would make this
        // cost grow with how many rigs are standing in loaded chunks.
        UUID owner = hitboxOfDisplay.get(entity.getUniqueId());
        if (owner == null) return null;
        Interaction hitbox = hitboxes.get(owner);
        return hitbox != null && hitbox.isValid() ? hitbox : null;
    }

    boolean isRigHitbox(Entity entity) {
        if (!(entity instanceof Interaction)) return false;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(modelKey, PersistentDataType.STRING)
            && (pdc.has(displaysKey, PersistentDataType.STRING) || pdc.has(displayKey, PersistentDataType.STRING));
    }

    String modelIdOf(Interaction hitbox) {
        return hitbox == null ? null : hitbox.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
    }

    List<String> animationNamesOf(String modelId) {
        return animationNames(rigs.get(modelId));
    }

    /** Whether this model places as a rig that can move at all, rather than one still display. */
    boolean isAnimated(String modelId) {
        return anyPartAnimates(rigs.get(modelId));
    }

    private RigStore.Rig rigOf(Interaction hitbox) {
        return rigs.get(modelIdOf(hitbox));
    }

    private boolean hasMovingPart(List<ItemDisplay> displays) {
        for (ItemDisplay display : displays) {
            if (display.getPersistentDataContainer().has(partKey, PersistentDataType.INTEGER)) return true;
        }
        return false;
    }

    private void scanRanges() {
        Iterator<Map.Entry<UUID, Interaction>> it = hitboxes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Interaction> entry = it.next();
            Interaction hitbox = entry.getValue();
            if (!hitbox.isValid()) {
                it.remove();
                rangeOccupants.remove(entry.getKey());
                continue;
            }
            PersistentDataContainer hitboxPdc = hitbox.getPersistentDataContainer();
            String modelId = hitboxPdc.get(modelKey, PersistentDataType.STRING);
            RigStore.Rig rig = rigs.get(modelId);
            int animationIndex =
                findAnimationIndex(rig, TRIGGER_RANGE, hitboxPdc.get(animationKey, PersistentDataType.STRING));
            RigStore.Animation animation = animationAt(rig, animationIndex);
            RigStore.Trigger trigger = triggerOf(animation, TRIGGER_RANGE);
            if (trigger == null) {
                rangeOccupants.remove(entry.getKey());
                continue;
            }

            double distance = trigger.distance > 0 ? Math.min(64, Math.max(0.5, trigger.distance)) : 5;
            double distanceSquared = distance * distance;
            Set<UUID> current = new HashSet<>();
            for (Player player : hitbox.getWorld().getPlayers()) {
                if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) continue;
                if (player.getLocation().distanceSquared(hitbox.getLocation()) <= distanceSquared) {
                    current.add(player.getUniqueId());
                }
            }
            Set<UUID> previous = rangeOccupants.put(entry.getKey(), current);
            if (hasNewEntrant(previous, current)) {
                startAnimation(hitbox, rig, animationIndex, ModelAnimationEvent.Cause.RANGE, null);
            }
        }
    }

    static boolean hasNewEntrant(Set<UUID> previous, Set<UUID> current) {
        if (current == null || current.isEmpty()) return false;
        if (previous == null || previous.isEmpty()) return true;
        for (UUID player : current) {
            if (!previous.contains(player)) return true;
        }
        return false;
    }

    private List<ItemDisplay> displaysOf(Interaction hitbox) {
        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        String joined = pdc.get(displaysKey, PersistentDataType.STRING);
        if (joined == null) joined = pdc.get(displayKey, PersistentDataType.STRING);
        List<ItemDisplay> displays = new java.util.ArrayList<>();
        if (joined == null || joined.isEmpty()) return displays;
        for (String raw : joined.split(",")) {
            try {
                Entity entity = Bukkit.getEntity(UUID.fromString(raw));
                if (entity instanceof ItemDisplay) displays.add((ItemDisplay) entity);
            } catch (IllegalArgumentException ignored) {
                // Ignore a malformed/stale id and keep the rest of the rig usable.
            }
        }
        return displays;
    }

    private static RigStore.Animation animationAt(RigStore.Rig rig, Integer index) {
        if (rig == null || rig.animations == null || index == null || index < 0 || index >= rig.animations.size()) return null;
        return rig.animations.get(index);
    }

    private static RigStore.Trigger triggerOf(RigStore.Animation animation, String triggerType) {
        if (animation == null || animation.triggers == null) return null;
        for (RigStore.Trigger trigger : animation.triggers) {
            if (trigger != null && triggerType.equals(trigger.type)) return trigger;
        }
        return null;
    }

    /**
     * Composes every overlay this part is playing, in layer order.
     *
     * <p>An overlay that has run out is skipped rather than removed here:
     * this is called from the pose loop, and writing persistent data on every
     * tick of every part to tidy up something that costs one comparison is a
     * worse trade than leaving it. {@link #playOverlay} rewrites the list.
     */
    private void applyOverlays(Matrix4f m, RigStore.Rig rig, RigStore.Part part,
                               PersistentDataContainer pdc, long now) {
        String written = pdc.get(overlayKey, PersistentDataType.STRING);
        if (written == null || written.isEmpty()) {
            return;
        }
        List<long[]> playing = new ArrayList<>();
        for (String one : written.split(";")) {
            int colon = one.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                int index = Integer.parseInt(one.substring(0, colon));
                long started = Long.parseLong(one.substring(colon + 1));
                RigStore.Animation animation = animationAt(rig, index);
                if (animation == null) {
                    continue;
                }
                double elapsed = Math.max(0, now - started) / 20.0;
                if (!loops(animation) && !holds(animation)
                        && elapsed * speedOf(animation) > Math.max(0, animation.length)) {
                    continue;
                }
                playing.add(new long[]{animation.layer, index, started});
            } catch (NumberFormatException ignored) {
                // One malformed entry costs that overlay and nothing else.
            }
        }
        if (playing.isEmpty()) {
            return;
        }
        playing.sort((a, b) -> Long.compare(a[0], b[0]));

        for (long[] one : playing) {
            RigStore.Animation animation = animationAt(rig, (int) one[1]);
            if (!moves(animation, part)) {
                continue;
            }
            double t = animationTime(animation, Math.max(0, now - one[2]) / 20.0);
            float weight = weightOf(animation);
            for (RigStore.Step step : part.program) {
                applyStep(m, animation.animators, step.target, step.pivot, t, weight);
            }
        }
    }

    /**
     * How strongly an animation applies. Absent is full strength, which is
     * what every manifest written before weights means.
     */
    static float weightOf(RigStore.Animation animation) {
        if (animation == null || animation.weight <= 0) {
            return 1f;
        }
        return (float) Math.min(1, animation.weight);
    }

    /**
     * Whether this animation touches this part at all.
     *
     * <p>A bone mask names bones, and a part belongs to one \u2014 but masking a
     * torso has to reach the arms inside it, so the match is against the
     * part's whole LINEAGE rather than its own name. That is why the lineage
     * is on the manifest at all.
     *
     * <p>No mask means every part, which is what an animation without one has
     * always done.
     */
    static boolean moves(RigStore.Animation animation, RigStore.Part part) {
        if (animation == null || animation.bones == null || animation.bones.length == 0) {
            return true;
        }
        if (part == null || part.bones == null) {
            // A loose cube, or a part from a manifest older than lineages.
            // Left out rather than in: a mask is an author saying "only
            // these", and answering "and also everything I cannot identify"
            // is the opposite of what they asked for.
            return false;
        }
        for (String wanted : animation.bones) {
            if (wanted == null) {
                continue;
            }
            for (String mine : part.bones) {
                if (wanted.equalsIgnoreCase(mine)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Starts an animation on its own layer, over whatever the base is doing.
     *
     * <p>One animation per layer: starting a second on the same one replaces
     * the first, which is what a layer IS. An animation on layer 0 is not an
     * overlay at all and goes through the ordinary path instead \u2014 asking for
     * one here is a caller confusing "play this as well" with "play this".
     *
     * @return whether it started
     */
    boolean playOverlay(List<ItemDisplay> displays, String modelId, String animationName) {
        RigStore.Rig rig = rigs.get(modelId);
        int index = findAnimationIndexByName(rig, animationName);
        RigStore.Animation animation = animationAt(rig, index);
        if (animation == null || animation.layer <= 0 || displays.isEmpty()) {
            return false;
        }
        long now = displays.get(0).getWorld().getGameTime();

        for (ItemDisplay display : displays) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            if (!pdc.has(partKey, PersistentDataType.INTEGER)) {
                continue;
            }
            StringBuilder next = new StringBuilder();
            String written = pdc.get(overlayKey, PersistentDataType.STRING);
            if (written != null) {
                for (String one : written.split(";")) {
                    int colon = one.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    try {
                        RigStore.Animation other = animationAt(rig, Integer.parseInt(one.substring(0, colon)));
                        // Anything on another layer is kept; this layer is
                        // being taken over.
                        if (other != null && other.layer != animation.layer) {
                            next.append(one).append(';');
                        }
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                }
            }
            next.append(index).append(':').append(now);
            pdc.set(overlayKey, PersistentDataType.STRING, next.toString());
            pose(display, false);
        }
        return true;
    }

    /** Clears every overlay, leaving the base animation alone. */
    void clearOverlays(List<ItemDisplay> displays) {
        for (ItemDisplay display : displays) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            if (pdc.has(overlayKey, PersistentDataType.STRING)) {
                pdc.remove(overlayKey);
                pose(display, true);
            }
        }
    }

    /**
     * Turns a head bone toward whatever its host is looking at.
     *
     * <p>Only on a model worn by an entity: a placed statue has nothing to
     * look with, and a head that swivelled to follow a passing player would be
     * a different feature and a much creepier one.
     *
     * <p><strong>The yaw is a difference, not an angle.</strong> The whole rig
     * already turns with the body (see {@code yawOf}), so applying the head's
     * absolute yaw here would turn it twice and leave a mob whose head faces
     * backwards while it walks. What is left over is exactly how far the head
     * is turned relative to the shoulders, which is what a neck does.
     *
     * <p>Clamped the way vanilla clamps a neck. Without it a mob looking
     * behind itself gets its head on backwards, which is what the numbers
     * literally say and not what anybody wants to see.
     */
    private void applyHeadLook(Matrix4f m, RigStore.Part part, ItemDisplay display) {
        if (part.behaviour == null || part.pivot == null || part.pivot.length != 3) return;
        if (!isHeadBone(part)) return;
        Entity host = display.getVehicle();
        if (!(host instanceof LivingEntity)) return;

        float[] look = lookOf((LivingEntity) host);
        float yaw = clamp(wrap(look[0]), MAX_NECK_YAW);
        float pitch = clamp(look[1], MAX_NECK_PITCH);
        if (yaw == 0f && pitch == 0f) return;

        float px = (part.pivot[0] - 8f) / 16f;
        float py = (part.pivot[1] - 8f) / 16f;
        float pz = (part.pivot[2] - 8f) / 16f;
        m.translate(px, py, pz);
        // Yaw negated for the same reason the placement yaw is: the rig's
        // space turns the other way round from the world's.
        m.rotateY((float) Math.toRadians(-yaw));
        m.rotateX((float) Math.toRadians(pitch));
        m.translate(-px, -py, -pz);
    }

    /**
     * How far the head is turned from the body: yaw relative to the shoulders,
     * then pitch.
     *
     * <p><strong>Bukkit does not expose a mob's head yaw.</strong>
     * {@code getEyeLocation()} carries the entity's own yaw, which IS the body
     * yaw for everything that is not a player — so the obvious implementation
     * subtracts a number from itself, gets zero, and quietly does nothing but
     * pitch while looking like it does more.
     *
     * <p>So the answer is taken from what the mob is actually looking AT. A
     * mob with a target is turning its head toward it, which is both the thing
     * a boss should visibly do and the only version of this the API can
     * honestly support. A mob with no target faces the way its body does,
     * which is exactly right: an idle mob's head is straight ahead.
     */
    private static float[] lookOf(LivingEntity host) {
        Location eyes = host.getEyeLocation();
        float pitch = eyes.getPitch();
        if (!(host instanceof Mob)) {
            // A player: their yaw already IS their head yaw, and the body is
            // drawn from it, so there is nothing left over.
            return new float[]{0f, pitch};
        }
        LivingEntity target = ((Mob) host).getTarget();
        if (target == null || !target.getWorld().equals(host.getWorld())) {
            return new float[]{0f, pitch};
        }
        Vector to = target.getEyeLocation().toVector().subtract(eyes.toVector());
        if (to.lengthSquared() < 1.0E-4) {
            return new float[]{0f, pitch};
        }
        float wanted = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        float flat = (float) Math.sqrt(to.getX() * to.getX() + to.getZ() * to.getZ());
        return new float[]{
                wanted - host.getLocation().getYaw(),
                (float) Math.toDegrees(Math.atan2(-to.getY(), flat))};
    }

    private static boolean isHeadBone(RigStore.Part part) {
        BoneBehaviour behaviour = behaviourOf(part);
        return behaviour.isHead();
    }

    /** A part's behaviour, or {@link BoneBehaviour#NONE} on an older manifest. */
    static BoneBehaviour behaviourOf(RigStore.Part part) {
        if (part == null || part.behaviour == null) return BoneBehaviour.NONE;
        for (BoneBehaviour behaviour : BoneBehaviour.values()) {
            if (behaviour.name().equalsIgnoreCase(part.behaviour)) return behaviour;
        }
        return BoneBehaviour.NONE;
    }

    /** Into -180..180, so a turn across north is a small number and not 350 degrees. */
    static float wrap(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped > 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }

    static float clamp(float degrees, float limit) {
        return Math.max(-limit, Math.min(limit, degrees));
    }

    /**
     * Composes one animator target's pose into {@code m}, about {@code pivot}.
     *
     * Takes the animator map rather than an Animation so emotes can share it:
     * an emote's keyframes are these keyframes, and its bones are animator
     * targets with a pivot, so the only thing that differed was the wrapper
     * type. One implementation means the editor, the rigs and the emotes
     * cannot drift on what a keyframe means.
     */
    public static void applyStep(
            Matrix4f m,
            Map<String, Map<String, List<Keyframe>>> animators,
            String target,
            float[] pivot,
            double t) {
        applyStep(m, animators, target, pivot, t, 1f);
    }

    /**
     * As above, at {@code weight} of full strength.
     *
     * <p>What lets a layer be half a wave rather than a wave. Weighting is
     * applied to the SAMPLED VALUES rather than to the finished matrix, which
     * is the only place it means anything: half of a rotation is a smaller
     * rotation, and half of a matrix is not a transform at all.
     *
     * <p>Scale is weighted toward 1 rather than toward 0, because 1 is what
     * "no scaling" is. Half of a bone scaled to 2 is 1.5, not 1.
     *
     * <p>A weight of exactly 1 takes the same arithmetic as before this
     * existed, so nothing that was not weighted changed.
     */
    public static void applyStep(
            Matrix4f m,
            Map<String, Map<String, List<Keyframe>>> animators,
            String target,
            float[] pivot,
            double t,
            float weight) {
        if (pivot == null || pivot.length != 3) return;
        if (weight <= 0f) return;
        Map<String, List<Keyframe>> animator = animators == null ? null : animators.get(target);
        // Same px -> block-space mapping as the editor viewport: (v-8)/16,
        // with the entity sitting at the block center.
        float px = (pivot[0] - 8f) / 16f;
        float py = (pivot[1] - 8f) / 16f;
        float pz = (pivot[2] - 8f) / 16f;
        float[] rot = sample(animator, "rotation", t, ZERO);
        float[] pos = sample(animator, "position", t, ZERO);
        float[] scl = sample(animator, "scale", t, ONE);
        float w = Math.min(1f, weight);
        m.translate(px + pos[0] * w / 16f, py + pos[1] * w / 16f, pz + pos[2] * w / 16f);
        m.rotateXYZ((float) Math.toRadians(rot[0] * w),
                (float) Math.toRadians(rot[1] * w),
                (float) Math.toRadians(rot[2] * w));
        m.scale(nonSingular(1f + (scl[0] - 1f) * w),
                nonSingular(1f + (scl[1] - 1f) * w),
                nonSingular(1f + (scl[2] - 1f) * w));
        m.translate(-px, -py, -pz);
    }

    // Scaling a part to exactly 0 is the normal way to hide it mid-animation,
    // and it also makes the matrix singular: rotation extraction divides by
    // the basis length, so the client gets a NaN quaternion, and since it
    // interpolates from its rendered pose the NaN propagates forever and the
    // part never comes back. A thousandth of a block keeps the basis
    // invertible and isn't visible.
    private static final float MIN_SCALE = 1e-3f;

    static float nonSingular(float scale) {
        if (Float.isNaN(scale)) return MIN_SCALE;
        if (Math.abs(scale) >= MIN_SCALE) return scale;
        return scale < 0 ? -MIN_SCALE : MIN_SCALE;
    }

    /**
     * A pose {@code amount} of the way from {@code from} to {@code to}.
     *
     * <p><strong>Rotations are slerped, not lerped.</strong> A component-wise
     * average of two quaternions is not a rotation: it shortens as the two
     * diverge, which shows up as a limb shrinking into itself halfway through
     * a transition and springing back out. Position and scale are ordinary
     * linear interpolation, where an average IS the answer.
     */
    static Transformation mix(Transformation from, Transformation to, float amount) {
        float t = Math.min(1f, Math.max(0f, amount));
        return new Transformation(
                new Vector3f(from.getTranslation()).lerp(to.getTranslation(), t),
                new Quaternionf(from.getLeftRotation()).slerp(to.getLeftRotation(), t),
                new Vector3f(from.getScale()).lerp(to.getScale(), t),
                new Quaternionf(from.getRightRotation()).slerp(to.getRightRotation(), t));
    }

    // Decompose into Bukkit's Transformation. Exact for a single program step;
    // a nested bone+cube step with non-uniform bone scale is approximate.
    public static Transformation toTransformation(Matrix4f m) {
        Vector3f translation = m.getTranslation(new Vector3f());
        // Animated scale means the basis vectors aren't unit length.
        // getNormalizedRotation assumes they are and can emit a non-unit
        // quaternion, which the client interpolates as a rapid spin.
        Quaternionf rotation = m.getUnnormalizedRotation(new Quaternionf()).normalize();
        // Belt and braces alongside nonSingular(): a bone scale and a cube
        // scale multiplying out to near zero can still degenerate the basis.
        // Identity is safe, since a basis this small is invisible anyway.
        if (!isFinite(rotation)) rotation = new Quaternionf();
        Vector3f scale = m.getScale(new Vector3f());
        return new Transformation(translation, rotation, scale, new Quaternionf());
    }

    private static boolean isFinite(Quaternionf q) {
        return Float.isFinite(q.x) && Float.isFinite(q.y) && Float.isFinite(q.z) && Float.isFinite(q.w);
    }

    // Mirrors sampleChannel in the editor (the editor's animation applier): hold before the
    // first and after the last keyframe; a segment is stepped if its left
    // key is "step", Catmull-Rom if either end is "smooth", else linear.
    public static float[] sample(Map<String, List<Keyframe>> animator, String channel, double time, float[] fallback) {
        if (animator == null) return fallback;
        List<Keyframe> frames = animator.get(channel);
        if (frames == null || frames.isEmpty()) return fallback;
        Keyframe first = frames.get(0);
        if (first.value == null) return fallback;
        if (time <= first.time) return first.value;
        Keyframe last = frames.get(frames.size() - 1);
        if (time >= last.time) return last.value;
        for (int i = 1; i < frames.size(); i++) {
            Keyframe b = frames.get(i);
            if (time <= b.time) {
                Keyframe a = frames.get(i - 1);
                if (a.value == null || b.value == null) return fallback;
                if ("step".equals(a.interpolation)) return a.value;
                double span = b.time - a.time;
                float ft = span > 0 ? (float) ((time - a.time) / span) : 1f;
                if ("smooth".equals(a.interpolation) || "smooth".equals(b.interpolation)) {
                    Keyframe p0 = i >= 2 ? frames.get(i - 2) : a;
                    Keyframe p3 = i + 1 < frames.size() ? frames.get(i + 1) : b;
                    float[] out = new float[3];
                    for (int axis = 0; axis < 3; axis++) {
                        out[axis] = catmullRom(p0.value[axis], a.value[axis], b.value[axis], p3.value[axis], ft);
                    }
                    return out;
                }
                float[] out = new float[3];
                for (int axis = 0; axis < 3; axis++) {
                    out[axis] = a.value[axis] + (b.value[axis] - a.value[axis]) * ft;
                }
                return out;
            }
        }
        return last.value;
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        return 0.5f * (2f * p1
            + (-p0 + p2) * t
            + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t
            + (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t);
    }
}
