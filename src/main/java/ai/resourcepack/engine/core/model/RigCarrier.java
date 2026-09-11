package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Placement;
import ai.resourcepack.engine.core.Host;
import ai.resourcepack.engine.core.animation.RigMath;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An animated rig worn by something that MOVES AND TURNS and is not ridden.
 *
 * <p>A vehicle, and a model an emote carries only part of which moves — a
 * barbell lifted off its bench. The awkward shape it solves turned out to be
 * general, which is why it was a class of its own rather than eight methods
 * inside {@code Vehicles} before there was a second caller for it.
 *
 * <p>The two differ in exactly one thing, and it is {@link CarriedRig#pose}:
 * a vehicle's parent transform is an attitude and nothing else, so it needs
 * only {@link CarriedRig#tilt}, while an emote prop hangs under a posed hand
 * and carries an offset and keyframes of its own.
 *
 * <h2>Why neither existing arrangement fits</h2>
 *
 * <p>There are already two ways a rig stands in the world, and a vehicle can
 * use neither:
 *
 * <ul>
 *   <li>A <strong>placed</strong> rig ({@code RigPlacementListener}) bakes the
 *       yaw somebody put it down at into every part and never moves again. A
 *       vehicle turns.</li>
 *   <li>A <strong>bound</strong> rig ({@link BoundModels}) makes the parts
 *       passengers of the host, so vanilla moves them and the animator reads
 *       the host's live yaw off {@code display.getVehicle()}. A vehicle cannot
 *       do this: <strong>CraftBukkit refuses {@code teleport} on an entity
 *       that is being ridden</strong>, and an exact teleport of the chassis is
 *       the whole of how a vehicle moves. Making the rig a passenger would
 *       freeze the vehicle.</li>
 * </ul>
 *
 * <p>So the parts are moved by whoever owns them, exactly as the single still
 * display they replace already was — <strong>position and heading together, on
 * one per-tick teleport.</strong>
 *
 * <p>That last part is not an implementation detail. A placed rig bakes its yaw
 * into the pose matrix, which is re-sent every {@link RigAnimator#PERIOD_TICKS}
 * ticks and interpolated over that window; that is free for a statue, whose yaw
 * never changes. Do it to a vehicle and its position updates at 20Hz while its
 * heading steps round at 10Hz on a different interpolation window — the art
 * visibly lags and chunks through corners, and steering feels like it is not
 * being read. So a carried part keeps its yaw on its ENTITY, which is the rule
 * {@code RigSpawn} already states for a still part, and its matrix carries only
 * the animation.
 *
 * <h2>The anchor is not a hitbox</h2>
 *
 * <p>The yaw host doubles as the {@link Interaction} the animator hangs
 * playback state on, because that is the identity {@link Placement} is built
 * around and reusing it means a vehicle's animation blends, layers and
 * one-shots exactly like every other animation in the engine rather than being
 * a second implementation.
 *
 * <p>It is spawned <strong>zero-sized</strong>, and that is load-bearing
 * rather than tidy: an {@code Interaction} carrying a {@code model-id} is what
 * {@code RigPlacementListener} treats as a placed statue, so a full-sized one
 * would let any player punch a moving vehicle's bodywork off it. Nothing can
 * click a box with no width.
 */
public final class RigCarrier {

    private final RigStore rigs;
    private final RigAnimator animator;
    private final ModelsImpl models;
    private final RigSpawn spawns;
    private final NamespacedKey modelKey;
    private final NamespacedKey partKey;
    private final NamespacedKey displaysKey;
    private final NamespacedKey yawHostKey;

    public RigCarrier(Host host, RigStore rigs, RigAnimator animator, ModelsImpl models) {
        this.rigs = rigs;
        this.animator = animator;
        this.models = models;
        this.spawns = new RigSpawn(host, animator);
        this.modelKey = host.key("model-id");
        this.partKey = host.key("part-index");
        this.displaysKey = host.key("display-uuids");
        this.yawHostKey = host.key(RigAnimator.YAW_HOST_KEY);
    }

    /**
     * Whether {@code modelId} has a rig with anything that actually moves.
     *
     * <p>False for a model with no rig at all AND for one whose rig is all
     * still parts — the distinction {@code RigInfo.animated()} already draws.
     * A caller that gets false should keep whatever it was doing before, which
     * for a vehicle is one plain display.
     */
    public boolean animates(String modelId) {
        return RigAnimations.anyPartAnimates(modelId == null ? null : rigs.get(modelId));
    }

    /** Whether the model has separately spawned rig parts, moving or still. */
    public boolean hasRig(String modelId) {
        RigStore.Rig rig = modelId == null ? null : rigs.get(modelId);
        return rig != null && rig.parts != null && !rig.parts.isEmpty();
    }

    /**
     * Puts {@code modelId}'s rig at {@code anchor}, facing {@code yaw}.
     *
     * @param partItem what one part renders as, given the part's item name —
     *                 the one thing a studio pack and an authored one disagree
     *                 about, exactly as in {@link RigSpawn}
     * @return the rig, or empty when this model has no separately spawned parts
     */
    public Optional<CarriedRig> carry(Location anchor, String modelId, float yaw,
                                      DisplayCarry glide, Function<String, ItemStack> partItem) {
        return carry(anchor, modelId, yaw, glide, partItem, 1f);
    }

    /**
     * The same, drawn {@code scale} times as big.
     *
     * <p>An overload rather than a sixth parameter on the one above, so every
     * existing caller keeps compiling and keeps getting the size the model was
     * built at.
     *
     * <p>Nothing here has to correct for the growth: {@code RigSpawn} stamps
     * the scale on every part and {@code RigAnimator.applyRigScale} grows each
     * posed matrix about the floor, so a scaled rig stands where an unscaled
     * one did. Which is why {@code anchor} is passed through untouched — the
     * caller's lift is already right at any size.
     */
    public Optional<CarriedRig> carry(Location anchor, String modelId, float yaw,
                                      DisplayCarry glide, Function<String, ItemStack> partItem,
                                      float scale) {
        if (anchor == null || anchor.getWorld() == null || !hasRig(modelId)) {
            return Optional.empty();
        }
        RigStore.Rig rig = rigs.get(modelId);

        Location facing = anchor.clone();
        facing.setYaw(yaw);
        facing.setPitch(0);

        // Spawned BEFORE the parts, because every part has to be told which
        // entity to read its yaw off and that means having its id in hand.
        Interaction yawHost = anchor.getWorld().spawn(facing, Interaction.class, box -> {
            // See the class note: zero-sized so nothing can click it, and
            // never saved, because it is derived from the vehicle exactly as
            // the parts are.
            box.setInteractionWidth(0f);
            box.setInteractionHeight(0f);
            box.setResponsive(false);
            box.setPersistent(false);
            box.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, modelId);
        });

        // Spawned at yaw ZERO on purpose. RigSpawn bakes the yaw it is given
        // into every moving part's pose, and a carried rig is turned by its
        // entity yaw instead (see RigAnimator.yawOf) — passing the real yaw
        // here would apply it twice for the one frame before the first move.
        // The `moveTo` at the END of this method is what puts the real heading
        // on, and it lives there rather than in the caller because a caller
        // that never moves its rig had one facing south for ever.
        List<ItemDisplay> parts = spawns.parts(anchor, modelId, rig, 0f, null, scale,
                part -> partItem.apply(part.item));

        List<String> ids = new ArrayList<>(parts.size());
        // The parts nothing animates. The animator never poses them — a
        // still part's transform is written once at spawn — so when the
        // carrier tilts the body they have to be turned from here. See
        // CarriedRig.tilt.
        List<String> still = new ArrayList<>();
        Map<String, List<String>> bones = new LinkedHashMap<>();
        for (ItemDisplay part : parts) {
            ids.add(part.getUniqueId().toString());
            // A carried rig is derived from its owner (vehicle or emote) and
            // has no independent life to restore after a server restart.
            part.setPersistent(false);
            if (!animator.animates(part)) {
                still.add(part.getUniqueId().toString());
            }
            Integer index = part.getPersistentDataContainer().get(partKey, PersistentDataType.INTEGER);
            if (index != null && index >= 0 && index < rig.parts.size()) {
                RigStore.Part definition = rig.parts.get(index);
                if (definition != null && definition.bone != null && !definition.bone.isBlank()) {
                    bones.computeIfAbsent(definition.bone, ignored -> new ArrayList<>())
                            .add(part.getUniqueId().toString());
                }
            }
            part.getPersistentDataContainer()
                    .set(yawHostKey, PersistentDataType.STRING, yawHost.getUniqueId().toString());
            // Without this a carried rig STROBES. A placed rig never moves, so
            // it has never needed a teleport duration; a carried one is
            // teleported every tick by whatever owns it, and a display told to
            // be somewhere new simply appears there. The single still display
            // this replaces was carried from the start, which is why an
            // animated vehicle looked worse than an unanimated one.
            //
            // The duration is the caller's to choose because it has to match
            // whatever else is moving alongside — for a vehicle that is its
            // riders, and being a tick tighter than them is the bug rather
            // than an improvement. See Vehicles.MODEL_GLIDE_TICKS.
            glide.carry(part);
        }
        yawHost.getPersistentDataContainer()
                .set(displaysKey, PersistentDataType.STRING, String.join(",", ids));
        // Both keys are on it now, which is what makes it a thing the animator
        // will accept — see RigAnimator.track's Interaction arm.
        animator.track(yawHost);

        CarriedRig carried = new CarriedRig(yawHost.getUniqueId(), ids, still, bones, scale, parts.size());
        // **The heading goes on HERE, not left to the caller.** The parts are
        // spawned at yaw zero deliberately (see above), and a carried part's
        // matrix never carries a yaw either — RigAnimator.yawOf returns 0 for
        // anything with a yaw host, because the entity is supposed to hold it.
        // So between spawning and the first moveTo a carried rig faces world
        // south whatever it was asked for.
        //
        // A vehicle never noticed: it moves every tick, so its first move was a
        // tick away. An EMOTE prop is not moved at all unless its wearer is
        // walking — follow() runs for a stance or a rider and for nothing else
        // — so a one-shot emote's model simply stayed facing south while the
        // rig it belongs to turned with the player. It read as the player being
        // rotated, by exactly their own yaw, which is why it looked like a
        // different angle every time somebody tried it.
        carried.moveTo(anchor, yaw);
        return Optional.of(carried);
    }

    /** One rig riding one moving thing. Obtained from {@link #carry}. */
    public final class CarriedRig {

        private final UUID anchorId;
        private final List<String> partIds;
        private final List<String> stillIds;
        private final Map<String, List<String>> boneIds;
        private final float scale;
        private final int size;

        /** The attitude last written to the still parts, so an unchanged one is not re-sent. */
        private Transformation stillPose;

        private CarriedRig(UUID anchorId, List<String> partIds, List<String> stillIds,
                           Map<String, List<String>> boneIds, float scale, int size) {
            this.anchorId = anchorId;
            this.partIds = partIds;
            this.stillIds = stillIds;
            this.boneIds = boneIds;
            this.scale = scale;
            this.size = size;
        }

        /**
         * Tilts the whole rig: pitch nose-up and roll right-side-down, in
         * degrees, about the anchor.
         *
         * <p>Two arms, because a rig's parts are posed two ways. The moving
         * parts are composed by the animator every tick and take the tilt
         * from it ({@link RigAnimator#tilt}); the still parts were written
         * once at spawn and are re-written here, only when the attitude has
         * changed, glided over the same window as a carried animation frame
         * so a still bonnet and a spinning wheel tilt together.
         */
        public void tilt(float pitch, float roll) {
            animator.tilt(anchorId, pitch, roll);
            Matrix4f m = Math.abs(pitch) < 0.05f && Math.abs(roll) < 0.05f
                    ? new Matrix4f()
                    : RigAnimator.tiltMatrix(pitch, roll);
            poseStill(m);
        }

        /**
         * Wraps every model bone in an authored model-space transform.
         *
         * <p>Used by emote props: the matrix is the player's root/bone chain,
         * the prop offset and its outer keyframes. The model's own animation
         * remains inside it and is driven through {@link #placement()}.
         */
        public void pose(Matrix4f pose) {
            animator.carrierPose(anchorId, pose);
            poseStill(RigMath.toItemDisplaySpace(
                    pose == null ? new Matrix4f() : new Matrix4f(pose)));
        }

        private void poseStill(Matrix4f m) {
            if (stillIds.isEmpty()) return;
            if (scale != 1f) {
                // The same growth-about-the-floor a still part was spawned
                // with; see RigSpawn and RigAnimator.applyRigScale.
                m.scaleLocal(scale);
                m.translateLocal(0f, 0.5f * (scale - 1f), 0f);
            }
            Transformation next = RigMath.toTransformation(m);
            if (next.equals(stillPose)) {
                return;
            }
            stillPose = next;
            for (String id : stillIds) {
                Entity part = entity(id);
                if (!(part instanceof ItemDisplay)) {
                    continue;
                }
                ItemDisplay display = (ItemDisplay) part;
                display.setInterpolationDelay(1);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(RigAnimator.CARRIED_GLIDE_TICKS);
                display.setTransformation(next);
            }
        }

        /** How many part displays it is made of. Zero is a rig that failed to spawn. */
        public int parts() {
            return size;
        }

        /** Model bone names that have their own display and can be detached. */
        public List<String> bones() {
            return List.copyOf(boneIds.keySet());
        }

        /**
         * Removes one bone display from this rig without deleting the display.
         * The caller becomes responsible for it and may turn it into debris.
         */
        public List<ItemDisplay> detach(String bone) {
            List<String> ids = bone == null ? null : boneIds.remove(bone);
            if (ids == null || ids.isEmpty()) return List.of();
            List<ItemDisplay> detached = new ArrayList<>();
            for (String id : ids) {
                Entity entity = entity(id);
                if (!(entity instanceof ItemDisplay)) continue;
                ItemDisplay display = (ItemDisplay) entity;
                partIds.remove(id);
                stillIds.remove(id);
                animator.bones().detach(display);
                animator.untrack(display.getUniqueId());
                display.getPersistentDataContainer().remove(modelKey);
                display.getPersistentDataContainer().remove(partKey);
                display.getPersistentDataContainer().remove(yawHostKey);
                detached.add(display);
            }
            Entity yawHost = Bukkit.getEntity(anchorId);
            if (yawHost != null) {
                yawHost.getPersistentDataContainer().set(
                        displaysKey, PersistentDataType.STRING, String.join(",", partIds));
            }
            return List.copyOf(detached);
        }

        /** Removes one named display entirely, for a part detached before this rig was rebuilt. */
        public boolean hide(String bone) {
            List<ItemDisplay> detached = detach(bone);
            detached.forEach(Entity::remove);
            return !detached.isEmpty();
        }

        /** Visits each live part display, for viewer-specific hiding. */
        public void forEachPart(Consumer<ItemDisplay> action) {
            if (action == null) return;
            for (String id : partIds) {
                Entity part = entity(id);
                if (part instanceof ItemDisplay) action.accept((ItemDisplay) part);
            }
        }

        /**
         * Puts every part, and the yaw host, at {@code anchor} facing
         * {@code yaw}.
         *
         * <p>Every part goes to the SAME point — a rig's parts share one
         * anchor and carry their offsets in their transformation matrices, so
         * moving them apart here would pull the model to pieces.
         *
         * <p>The yaw is written to the host and to nothing else. That single
         * teleport is what turns the whole rig.
         */
        public void moveTo(Location anchor, float yaw) {
            if (anchor == null || anchor.getWorld() == null) {
                return;
            }
            Location facing = anchor.clone();
            facing.setYaw(yaw);
            facing.setPitch(0);
            Entity yawHost = Bukkit.getEntity(anchorId);
            if (yawHost != null) {
                yawHost.teleport(facing);
            }
            // The yaw rides the SAME teleport as the position, which is the
            // whole point: both then update every tick and glide over the same
            // window, instead of the position moving at 20Hz while a
            // matrix-baked heading stepped round at 10Hz.
            for (String id : partIds) {
                Entity part = entity(id);
                if (part != null) {
                    part.teleport(facing);
                }
            }
        }

        /** Whether the yaw host is still there, which is whether the rig is. */
        public boolean isValid() {
            Entity yawHost = Bukkit.getEntity(anchorId);
            return yawHost != null && yawHost.isValid();
        }

        private Entity entity(String id) {
            try {
                return Bukkit.getEntity(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                // A malformed id costs that one part and nothing else, the
                // same rule indexDisplays follows.
                return null;
            }
        }

        /**
         * The handle the rest of the engine animates this through.
         *
         * <p>A {@link Placement}, deliberately: a vehicle's animation is then
         * the same object with the same blending, priority and one-shot rules
         * as a statue's, rather than a parallel implementation that would
         * drift from it.
         */
        public Optional<Placement> placement() {
            Entity yawHost = Bukkit.getEntity(anchorId);
            return yawHost instanceof Interaction
                    ? Optional.ofNullable(models.handleFor((Interaction) yawHost))
                    : Optional.empty();
        }



        /** Takes every entity of it out of the world. */
        public void despawn() {
            animator.tilt(anchorId, 0f, 0f);
            animator.carrierPose(anchorId, null);
            Entity yawHost = Bukkit.getEntity(anchorId);
            if (yawHost != null) {
                yawHost.remove();
            }
            for (String id : partIds) {
                Entity part = entity(id);
                if (part != null) {
                    part.remove();
                }
            }
        }
    }
}
