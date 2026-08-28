package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Keyframe;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    static final String TRIGGER_LOOP = "loop";
    static final String TRIGGER_RIGHT_CLICK = "right_click";
    static final String TRIGGER_LEFT_CLICK = "left_click";
    static final String TRIGGER_RANGE = "range";
    static final String TRIGGER_PLACE = "place";

    private static final int RANGE_SCAN_INTERVAL = 5;

    private static final float[] ZERO = { 0f, 0f, 0f };
    private static final float[] ONE = { 1f, 1f, 1f };

    private final Host host;
    private final RigStore rigs;
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

    private final Map<UUID, ItemDisplay> tracked = new ConcurrentHashMap<>();
    private final Map<UUID, Interaction> hitboxes = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> rangeOccupants = new ConcurrentHashMap<>();
    // part display id -> its hitbox id. A display holds no back-reference, so
    // without this the code API's placementOf(display) has to read every
    // tracked hitbox's id list to find the one that claims it. Built from the
    // hitbox's own list when it's tracked, so it needs nothing at spawn time.
    private final Map<UUID, UUID> hitboxOfDisplay = new ConcurrentHashMap<>();
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
        if (rig != null && rig.parts != null && partIndex != null
            && partIndex >= 0 && partIndex < rig.parts.size()
            && !hasAnimationProgram(rig.parts.get(partIndex))) {
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

    void untrack(UUID id) {
        tracked.remove(id);
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
        boolean bound = pdc.has(boundKey, PersistentDataType.STRING);
        if (!bound && !shouldUpdatePose(playbackIndex, activeIndex, forceRestPose)) return;

        if (activeIndex != null && playbackIndex != activeIndex) {
            pdc.remove(activeAnimationKey);
            if (playbackIndex < 0) pdc.remove(animationStartKey);
        }

        RigStore.Animation animation = animationAt(rig, playbackIndex);
        if (animation != null) {
            double t = animationTime(animation, elapsed);
            for (RigStore.Step step : part.program) {
                applyStep(animationTransform, animation.animators, step.target, step.pivot, t);
            }
        }

        Matrix4f m = new Matrix4f();
        if (yaw != null && yaw != 0f) m.rotateY((float) Math.toRadians(-yaw));
        m.mul(toItemDisplaySpace(animationTransform));
        applyRigScale(m, pdc);

        Transformation next = toTransformation(m);
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
        for (int i = 0; i < rig.animations.size(); i++) {
            if (hasTrigger(rig.animations.get(i), triggerType)) return i;
        }
        return -1;
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
        if (active != null && (loops(active) || elapsed <= Math.max(0, active.length))) {
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
        return loops(animation) ? elapsed % animation.length : Math.min(elapsed, animation.length);
    }

    private static boolean loops(RigStore.Animation animation) {
        return animation.triggers == null ? animation.loop : hasTrigger(animation, TRIGGER_LOOP);
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
        if (pivot == null || pivot.length != 3) return;
        Map<String, List<Keyframe>> animator = animators == null ? null : animators.get(target);
        // Same px -> block-space mapping as the editor viewport: (v-8)/16,
        // with the entity sitting at the block center.
        float px = (pivot[0] - 8f) / 16f;
        float py = (pivot[1] - 8f) / 16f;
        float pz = (pivot[2] - 8f) / 16f;
        float[] rot = sample(animator, "rotation", t, ZERO);
        float[] pos = sample(animator, "position", t, ZERO);
        float[] scl = sample(animator, "scale", t, ONE);
        m.translate(px + pos[0] / 16f, py + pos[1] / 16f, pz + pos[2] / 16f);
        m.rotateXYZ((float) Math.toRadians(rot[0]), (float) Math.toRadians(rot[1]), (float) Math.toRadians(rot[2]));
        m.scale(nonSingular(scl[0]), nonSingular(scl[1]), nonSingular(scl[2]));
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
