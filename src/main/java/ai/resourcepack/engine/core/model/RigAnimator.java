package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Keyframe;
import ai.resourcepack.engine.api.Placement;
import ai.resourcepack.engine.api.event.ModelAnimationEndEvent;
import ai.resourcepack.engine.api.event.ModelAnimationEvent;
import ai.resourcepack.engine.core.Host;
import ai.resourcepack.engine.core.animation.RigMath;

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

import java.util.ArrayList;
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
 * viewport applier:
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
    private static final int RANGE_SCAN_INTERVAL = 5;

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

        if (part != null && !RigAnimations.hasAnimationProgram(part)) {
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
        int animationIndex = RigAnimations.findAnimationIndex(rig, triggerType, pdc.get(animationKey, PersistentDataType.STRING));
        return animationIndex >= 0 && startAnimation(hitbox, rig, animationIndex, causeOf(triggerType), player);
    }

    /** Which cause an event should report for a trigger type. */
    private static ModelAnimationEvent.Cause causeOf(String triggerType) {
        if (RigAnimations.TRIGGER_PLACE.equals(triggerType)) return ModelAnimationEvent.Cause.PLACE;
        if (RigAnimations.TRIGGER_RIGHT_CLICK.equals(triggerType)) return ModelAnimationEvent.Cause.RIGHT_CLICK;
        if (RigAnimations.TRIGGER_LEFT_CLICK.equals(triggerType)) return ModelAnimationEvent.Cause.LEFT_CLICK;
        if (RigAnimations.TRIGGER_RANGE.equals(triggerType)) return ModelAnimationEvent.Cause.RANGE;
        return ModelAnimationEvent.Cause.API;
    }

    boolean hasTrigger(String modelId, String triggerType) {
        return RigAnimations.findAnimationIndex(rigs.get(modelId), triggerType) >= 0;
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
        if (!RigAnimations.hasAnimationProgram(part)) {
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
            RigAnimations.playbackAnimationIndex(rig, activeIndex, elapsed, pdc.get(animationKey, PersistentDataType.STRING));

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
                    RigAnimations.blendOf(RigAnimations.animationAt(rig, playbackIndex)),
                    posedFor == null ? 0 : RigAnimations.blendOf(RigAnimations.animationAt(rig, posedFor)));
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
                && !RigAnimations.shouldUpdatePose(playbackIndex, activeIndex, forceRestPose)) return;

        if (activeIndex != null && playbackIndex != activeIndex) {
            pdc.remove(activeAnimationKey);
            if (playbackIndex < 0) pdc.remove(animationStartKey);
        }

        RigStore.Animation animation = RigAnimations.animationAt(rig, playbackIndex);
        if (animation != null && RigAnimations.moves(animation, part)) {
            double t = RigAnimations.animationTime(animation, elapsed);
            float weight = RigAnimations.weightOf(animation);
            for (RigStore.Step step : part.program) {
                RigMath.applyStep(animationTransform, animation.animators, step.target, step.pivot, t, weight);
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
        HeadLook.applyTo(animationTransform, part, display);

        Matrix4f m = new Matrix4f();
        if (yaw != null && yaw != 0f) m.rotateY((float) Math.toRadians(-yaw));
        m.mul(RigMath.toItemDisplaySpace(animationTransform));
        applyRigScale(m, pdc);

        Transformation next = RigMath.toTransformation(m);
        if (blend != null) {
            float progress = blend.progress(tick);
            if (progress >= 1f) {
                blends.remove(display.getUniqueId());
            } else {
                next = RigMath.mix(blend.from, next, progress);
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
        m.mul(RigMath.toItemDisplaySpace(new Matrix4f()));
        applyRigScale(m, pdc);

        Transformation next = RigMath.toTransformation(m);
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
        RigStore.Animation animation = RigAnimations.animationAt(rig, animationIndex);
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

        // Read before the write, so the name is the OUTGOING animation's.
        String replaced = placements == null ? null : playingOn(hitbox);

        for (ItemDisplay display : displays) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            pdc.set(activeAnimationKey, PersistentDataType.INTEGER, animationIndex);
            pdc.set(animationStartKey, PersistentDataType.LONG, now);
            if (pdc.has(partKey, PersistentDataType.INTEGER)) pose(display, false);
        }

        if (placements != null && replaced != null && !replaced.equals(animation.name)) {
            end(hitbox, replaced, ModelAnimationEndEvent.Cause.REPLACED);
        }
        scheduleEnd(hitbox, animation, animationIndex, now);
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
        int index = RigAnimations.findAnimationIndexByName(rig, animationName);
        RigStore.Animation animation = RigAnimations.animationAt(rig, index);
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
            return RigAnimations.isMidOneShot(animation, pdc.get(activeAnimationKey, PersistentDataType.INTEGER),
                pdc.get(animationStartKey, PersistentDataType.LONG), animationIndex, now);
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
        int index = RigAnimations.findAnimationIndexByName(rig, animationName);
        if (index < 0) return false;
        List<ItemDisplay> displays = displaysOf(hitbox);
        // An animation that says which layer it belongs on is asking to play
        // OVER whatever is running, not instead of it. Routed here rather than
        // through a second method, so every caller gets it and none of them
        // has to know layers exist.
        if (RigAnimations.animationAt(rig, index).layer > 0) {
            return playOverlay(displays, modelIdOf(hitbox), animationName);
        }
        // A model with no rig parts places as one still display: it has a
        // hitbox and a model id like any rig, so without this the PDC below
        // would be written and success reported for something that cannot
        // move. Vanilla fires no event to notice that by.
        if (!hasMovingPart(displays)) return false;
        if (!restart && isMidOneShot(displays, RigAnimations.animationAt(rig, index), index, hitbox.getWorld().getGameTime())) {
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
        String was = playingOn(hitbox);
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
        if (wasPlaying && was != null) {
            end(hitbox, was, ModelAnimationEndEvent.Cause.STOPPED);
        }
        return wasPlaying;
    }

    /**
     * Says an animation ended, once per placement.
     *
     * <p>Placement-level rather than part-level for the same reason the start
     * event is: a model is one thing to everybody outside this class, and
     * eleven events for eleven cubes is not information.
     */
    private void end(Interaction hitbox, String animation, ModelAnimationEndEvent.Cause cause) {
        if (placements == null || animation == null || animation.isEmpty()) return;
        Bukkit.getPluginManager().callEvent(
            new ModelAnimationEndEvent(placements.apply(hitbox), animation, cause));
    }

    /**
     * Books the FINISHED event for a one-shot, for when it runs out.
     *
     * <p>A timer rather than a per-tick check, because the per-tick path is
     * per PART and already the hottest code here: it would have to work out
     * which of eleven displays speaks for the model, every tick, for something
     * that happens once.
     *
     * <p>The task re-reads the placement when it fires and says nothing unless
     * the same animation is still the one running from the same start, so an
     * animation replaced, stopped or removed in the meantime does not also
     * report finishing. A loop and a hold are never booked: neither runs out.
     * Nothing is booked across a restart either — the placement resumes from
     * its own clock, but the task that was going to speak for it is gone.
     */
    private void scheduleEnd(Interaction hitbox, RigStore.Animation animation, int index, long startedAt) {
        if (placements == null || RigAnimations.loops(animation) || RigAnimations.holds(animation)) return;
        double speed = RigAnimations.speedOf(animation);
        if (speed <= 0 || animation.length <= 0) return;

        long ticks = Math.max(1L, Math.round(animation.length / speed * 20.0));
        String name = animation.name;
        Bukkit.getScheduler().runTaskLater(host.plugin(), () -> {
            if (!hitbox.isValid() || !stillRunning(hitbox, index, startedAt)) return;
            end(hitbox, name, ModelAnimationEndEvent.Cause.FINISHED);
        }, ticks + 1);
    }

    /** Whether the placement is still on the same animation from the same moment. */
    private boolean stillRunning(Interaction hitbox, int index, long startedAt) {
        for (ItemDisplay display : displaysOf(hitbox)) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            if (!pdc.has(partKey, PersistentDataType.INTEGER)) continue;
            Integer active = pdc.get(activeAnimationKey, PersistentDataType.INTEGER);
            Long started = pdc.get(animationStartKey, PersistentDataType.LONG);
            return active != null && active == index && started != null && started == startedAt;
        }
        return false;
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
            RigStore.Animation animation = RigAnimations.animationAt(rig, RigAnimations.playbackAnimationIndex(rig, active, elapsed, chosen));
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
        return RigAnimations.animationNames(rigs.get(modelId));
    }

    /** Whether this model places as a rig that can move at all, rather than one still display. */
    boolean isAnimated(String modelId) {
        return RigAnimations.anyPartAnimates(rigs.get(modelId));
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
                RigAnimations.findAnimationIndex(rig, RigAnimations.TRIGGER_RANGE, hitboxPdc.get(animationKey, PersistentDataType.STRING));
            RigStore.Animation animation = RigAnimations.animationAt(rig, animationIndex);
            RigStore.Trigger trigger = RigAnimations.triggerOf(animation, RigAnimations.TRIGGER_RANGE);
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
                RigStore.Animation animation = RigAnimations.animationAt(rig, index);
                if (animation == null) {
                    continue;
                }
                double elapsed = Math.max(0, now - started) / 20.0;
                if (!RigAnimations.loops(animation) && !RigAnimations.holds(animation)
                        && elapsed * RigAnimations.speedOf(animation) > Math.max(0, animation.length)) {
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
            RigStore.Animation animation = RigAnimations.animationAt(rig, (int) one[1]);
            if (!RigAnimations.moves(animation, part)) {
                continue;
            }
            double t = RigAnimations.animationTime(animation, Math.max(0, now - one[2]) / 20.0);
            float weight = RigAnimations.weightOf(animation);
            for (RigStore.Step step : part.program) {
                RigMath.applyStep(m, animation.animators, step.target, step.pivot, t, weight);
            }
        }
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
        int index = RigAnimations.findAnimationIndexByName(rig, animationName);
        RigStore.Animation animation = RigAnimations.animationAt(rig, index);
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
                        RigStore.Animation other = RigAnimations.animationAt(rig, Integer.parseInt(one.substring(0, colon)));
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

}
