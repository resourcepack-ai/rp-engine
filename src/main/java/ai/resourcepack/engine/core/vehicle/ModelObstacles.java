package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ModelShape;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The placed models a vehicle cannot drive through, shaped like the models.
 *
 * <p><strong>A placed model is two entities and neither of them collides with
 * anything.</strong> What you see is an {@link ItemDisplay}, which has no
 * collision at all; what you can punch is an {@link Interaction}, which has a
 * box and still does not stop a moving thing. So a car went through a fence, a
 * statue and a parked chair as if none of them were there, and there was
 * nothing in the world for {@link BlockSurfaces} to find — its questions are
 * all about BLOCKS, and a model is not one.
 *
 * <p>The fix could have been a block: put barriers under the piece and every
 * existing question answers itself. That is what {@code place: solid: true}
 * already does for a walking player, and it is exactly why it is not the answer
 * here — it writes to the world (one cube, at the anchor, whatever shape the
 * model is), it has to be swept up when the piece breaks, and it stops people
 * as well as vehicles. Collision for a vehicle should cost the world nothing
 * and should follow the piece's real shape, so this asks the entities instead.
 *
 * <h2>The shape is the model's own elements</h2>
 *
 * <p><strong>The first cut of this used the Interaction's box and that was the
 * whole complaint: you could drive through everything except one block of it.</strong>
 * A Studio placement's hitbox is sized by the placement's SCALE rather than by
 * the art, so every model was a 1×1×1 cube however big it was drawn — and even
 * the authored side, which does measure, measures a square column around the
 * whole model, so a chair was a crate.
 *
 * <p>A block model is already a list of boxes. Every element in one is a
 * cuboid, so the real collision volume needs no approximation and no
 * voxelisation — see {@link ModelShape}, which is the elements in the model's
 * own frame. This puts them in the world:
 *
 * <pre>
 *   world = base + R(yaw) · (scale · local_xz),  y = base_y + scale · local_y
 * </pre>
 *
 * <p>and every question is asked the other way round, by pulling the query
 * point back into the model's frame rather than pushing the boxes out into the
 * world. <strong>That is what makes any yaw exact and not just the four
 * cardinals</strong>: rotating a box gives something that is no longer a box
 * and has to be over-approximated, while rotating a POINT gives a point. One
 * sine and one cosine per obstacle, and the boxes are compared as they were
 * authored.
 *
 * <p>The transform is the placement's, not a second opinion about it: a display
 * sits at the block centre and Minecraft renders its model centred there, and
 * {@code RigMath.scaledTransformation} lifts a scaled model by
 * {@code 0.5(s-1)} so it still stands on the floor. Both are folded into
 * {@link ModelShape}'s two conversions, which is why only an origin, a yaw and
 * a scale are needed here.
 *
 * <p><strong>A model with no readable shape falls back to its hitbox</strong>,
 * which is what every version before this used. That covers a pack pushed
 * before shapes could be read, a model whose file will not parse, and anything
 * placed from a source that has no geometry to measure.
 *
 * <p>Gathered once per tick and then asked several times, because the answers
 * are arithmetic and the gathering is a chunk scan: {@link VehicleRuntime}'s
 * Ride puts four or five questions to it on a tick where a vehicle hits
 * something, and doing the scan per question would be four scans for one wall.
 *
 * <p>Heights are absolute world Y and {@link Double#NaN} means nothing is
 * there, matching {@link BlockSurfaces} exactly — the two are read side by side
 * and a second convention would be a bug waiting for the first vehicle to park
 * on a model at y=0.
 */
final class ModelObstacles {

    /** Nothing in the way. The answer for most vehicles on most ticks. */
    static final ModelObstacles NONE = new ModelObstacles(List.of());

    private final List<Obstacle> obstacles;

    private ModelObstacles(List<Obstacle> obstacles) {
        this.obstacles = obstacles;
    }

    /** Engine internal; one placement, for tests that have no world to scan. */
    static ModelObstacles of(ModelShape shape, double x, double y, double z,
                             double yaw, double scale) {
        return new ModelObstacles(List.of(new Obstacle(shape, x, y, z, yaw, scale)));
    }

    /** Whether there is anything at all, so a caller can skip the arithmetic. */
    boolean isEmpty() {
        return obstacles.isEmpty();
    }

    /**
     * Whether a point is inside one of them.
     *
     * <p>Half open at the top, like a block's collision box: a vehicle resting
     * ON a model is not inside it, and a closed test would leave it permanently
     * stuck in the thing it had just landed on.
     */
    boolean solidAt(double x, double y, double z) {
        for (Obstacle obstacle : obstacles) {
            if (obstacle.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest model top at this point between {@code floor} and
     * {@code ceiling}, or NaN for nothing in that band.
     *
     * <p>One method for what {@link BlockSurfaces} needs three of: resting on a
     * model, climbing onto one and reading a wheel are the same question asked
     * over three different bands.
     *
     * <p>Per COLUMN, which is the half of "shaped like the model" that people
     * feel rather than see — a table is a surface at its top over the table top
     * and nothing at all in the gap between its legs.
     */
    double topAt(double x, double z, double floor, double ceiling) {
        double best = Double.NaN;
        for (Obstacle obstacle : obstacles) {
            // The band goes DOWN into the model rather than being applied to
            // the answer coming back. A model is many boxes over one column —
            // a stair tread with a handrail over it — and filtering afterwards
            // keeps only the highest and then throws it away for being too
            // high, losing the climbable surface underneath. See
            // ModelShape.topAt, which is where that failure is written down.
            double top = obstacle.topAt(x, z, floor, ceiling);
            if (Double.isNaN(top)) {
                continue;
            }
            if (Double.isNaN(best) || top > best) {
                best = top;
            }
        }
        return best;
    }

    /**
     * One placed model, with its transform resolved and its boxes in hand.
     *
     * <p>The world AABB is kept beside them as a broad phase. A vehicle asks
     * about a handful of points many times a tick and nearly every one of them
     * misses every obstacle, so the common answer should cost six comparisons
     * rather than a walk over ninety boxes.
     */
    private static final class Obstacle {

        private final ModelShape shape;
        private final double x;
        private final double y;
        private final double z;
        private final double cos;
        private final double sin;
        private final double scale;

        private final double minX;
        private final double minY;
        private final double minZ;
        private final double maxX;
        private final double maxY;
        private final double maxZ;

        Obstacle(ModelShape shape, double x, double y, double z, double yaw, double scale) {
            this.shape = shape;
            this.x = x;
            this.y = y;
            this.z = z;
            this.scale = scale <= 0 ? 1 : scale;
            double radians = Math.toRadians(yaw);
            this.cos = Math.cos(radians);
            this.sin = Math.sin(radians);
            // However the model is turned, it cannot reach further from its
            // anchor than its widest corner does — so one circle bounds every
            // yaw and the broad phase never has to be rebuilt.
            double reach = shape.reach() * this.scale;
            this.minX = x - reach;
            this.maxX = x + reach;
            this.minZ = z - reach;
            this.maxZ = z + reach;
            this.minY = y;
            this.maxY = y + shape.height() * this.scale;
        }

        boolean contains(double px, double py, double pz) {
            if (px < minX || px > maxX || py < minY || py >= maxY || pz < minZ || pz > maxZ) {
                return false;
            }
            double dx = px - x;
            double dz = pz - z;
            // The INVERSE turn: the model's own +x runs along (cos, sin) and
            // its +z along (-sin, cos) — see VehiclePhysics.seatOffset, which
            // is where this file's yaw convention comes from and which nothing
            // here may disagree with.
            return shape.contains(
                    (dx * cos + dz * sin) / scale,
                    (py - y) / scale,
                    (-dx * sin + dz * cos) / scale);
        }

        double topAt(double px, double pz, double floor, double ceiling) {
            if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
                return Double.NaN;
            }
            double dx = px - x;
            double dz = pz - z;
            // The band into the model's own frame, the same conversion the
            // point gets. Asking in world units against local boxes would be a
            // band scaled wrong on every model not placed at 1x.
            double local = shape.topAt(
                    (dx * cos + dz * sin) / scale,
                    (-dx * sin + dz * cos) / scale,
                    (floor - y) / scale,
                    (ceiling - y) / scale);
            return Double.isNaN(local) ? Double.NaN : y + local * scale;
        }
    }

    /**
     * Finds the placed models around a vehicle.
     *
     * <p>Held by the runtime rather than made per call: it owns the persistent
     * data keys the placement listeners write, and reading them off a fresh
     * {@link NamespacedKey} every tick would be a string parse per entity per
     * vehicle.
     */
    static final class Sensor {

        /**
         * What a piece from a content folder is tagged with, holding its
         * content id.
         *
         * <p>Read FIRST, because an animated authored piece wears the studio
         * key as well — the animator finds both kinds of placement through it,
         * which is the whole reason a pushed model and a hand-authored one
         * animate through one code path.
         */
        private final NamespacedKey authoredKey;

        /** What a piece off a Studio push is tagged with, holding its slug. */
        private final NamespacedKey studioKey;

        /**
         * The placement's heading, written by both listeners.
         *
         * <p>On the HITBOX, which is new: the yaw has always been on the
         * displays, and the hitbox is the only entity this class looks at.
         * Absent on anything placed before that, which {@link #yawOf} resolves
         * the long way round and then writes here, so a world full of old
         * furniture heals itself one piece at a time instead of every piece
         * facing north.
         */
        private final NamespacedKey yawKey;

        /** The placement's size multiplier, on a Studio placement that has one. */
        private final NamespacedKey scaleKey;

        /** Where the displays are, for recovering an old placement's yaw. */
        private final NamespacedKey displaysKey;
        private final NamespacedKey displayKey;
        private final NamespacedKey legacyDisplayKey;

        /** The yaw a rig part carries when its own entity is not turned. */
        private final NamespacedKey rigYawKey;

        private volatile PlacedModels models = new PlacedModels() {

            @Override
            public boolean stops(String id) {
                return true;
            }

            @Override
            public ModelShape shapeOf(String id) {
                return ModelShape.NONE;
            }

            @Override
            public double scaleOf(String id) {
                return 1;
            }
        };

        Sensor(Plugin plugin) {
            this.authoredKey = new NamespacedKey(plugin, "model");
            this.studioKey = new NamespacedKey(plugin, "model-id");
            this.yawKey = new NamespacedKey(plugin, "model-yaw");
            this.scaleKey = new NamespacedKey(plugin, "rig-scale");
            this.displaysKey = new NamespacedKey(plugin, "display-uuids");
            this.displayKey = new NamespacedKey(plugin, "model-display");
            this.legacyDisplayKey = new NamespacedKey(plugin, "display-uuid");
            this.rigYawKey = new NamespacedKey(plugin, "rig-yaw");
        }

        /** Adopts the answers, as a reload or a push does. */
        void models(PlacedModels models) {
            if (models != null) {
                this.models = models;
            }
        }

        /**
         * Every collidable placed model whose shape could reach a vehicle at
         * {@code at}.
         *
         * @param reach  how far out to look horizontally, in blocks
         * @param height how far to look up and down
         */
        ModelObstacles around(World world, Location at, double reach, double height) {
            List<Obstacle> found = null;
            for (Entity nearby : world.getNearbyEntities(at, reach, height, reach)) {
                if (!(nearby instanceof Interaction)) {
                    continue;
                }
                Interaction hitbox = (Interaction) nearby;
                PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
                String id = pdc.get(authoredKey, PersistentDataType.STRING);
                if (id == null) {
                    id = pdc.get(studioKey, PersistentDataType.STRING);
                }
                // Not a placed model at all: a vehicle's own seat and body
                // boxes are Interactions too, and they carry the vehicle keys
                // rather than either of these.
                if (id == null || !models.stops(id)) {
                    continue;
                }
                // <strong>A zero-sized box is not an obstacle, and skipping it
                // is not tidiness.</strong> An ANIMATED VEHICLE hangs its rig
                // off a zero-sized Interaction wearing the studio key (see
                // RigCarrier's yaw host) — a handle, not a hitbox, sitting at
                // the vehicle's own position. Taken as an obstacle it is a
                // surface exactly under the vehicle's centre sample, which is
                // the vehicle holding itself up: permanently supported, never
                // falling, hovering wherever it was spawned.
                if (hitbox.getInteractionWidth() <= 0 || hitbox.getInteractionHeight() <= 0) {
                    continue;
                }

                double scale = models.scaleOf(id) * placementScale(pdc);
                ModelShape shape = models.shapeOf(id);
                if (shape.isEmpty()) {
                    // No geometry to be had. The hitbox is what this used
                    // before shapes existed and is still better than nothing:
                    // a square column, upright, the size of the placement.
                    shape = columnFor(hitbox, scale);
                    if (shape.isEmpty()) {
                        continue;
                    }
                }
                Location base = hitbox.getLocation();
                if (found == null) {
                    found = new ArrayList<>(4);
                }
                found.add(new Obstacle(shape, base.getX(), base.getY(), base.getZ(),
                        yawOf(hitbox, pdc), scale));
            }
            return found == null ? NONE : new ModelObstacles(found);
        }

        /**
         * The old behaviour as a shape: one box the size of the punchable
         * hitbox.
         *
         * <p>Divided back out by the scale because {@link Obstacle} multiplies
         * it in again — the hitbox is already the finished size, while a real
         * shape is the model at 1x.
         */
        private static ModelShape columnFor(Interaction hitbox, double scale) {
            double half = hitbox.getInteractionWidth() / 2.0 / scale * 16;
            double tall = hitbox.getInteractionHeight() / scale * 16;
            return ModelShape.ofModelUnits(List.of(new float[] {
                    (float) (8 - half), 0f, (float) (8 - half),
                    (float) (8 + half), (float) tall, (float) (8 + half)}));
        }

        /** A Studio placement's own size multiplier, or 1. */
        private double placementScale(PersistentDataContainer pdc) {
            Float stored = pdc.get(scaleKey, PersistentDataType.FLOAT);
            return stored == null || stored <= 0 ? 1 : stored;
        }

        /**
         * Which way the placement faces.
         *
         * <p>Cheap and exact for anything placed since the hitbox started
         * carrying it. For everything older the answer is recovered from the
         * first display — its own rotation for a still model, its
         * {@code rig-yaw} for an animated one whose turn is baked into the pose
         * — and then <strong>written back onto the hitbox</strong>, so the
         * entity lookup happens once in that placement's life rather than every
         * tick a vehicle drives past it.
         */
        private float yawOf(Interaction hitbox, PersistentDataContainer pdc) {
            Float stored = pdc.get(yawKey, PersistentDataType.FLOAT);
            if (stored != null) {
                return stored;
            }
            float recovered = fromDisplay(hitbox, pdc);
            pdc.set(yawKey, PersistentDataType.FLOAT, recovered);
            return recovered;
        }

        private float fromDisplay(Interaction hitbox, PersistentDataContainer pdc) {
            String joined = pdc.get(displaysKey, PersistentDataType.STRING);
            if (joined == null) {
                joined = pdc.get(displayKey, PersistentDataType.STRING);
            }
            if (joined == null) {
                joined = pdc.get(legacyDisplayKey, PersistentDataType.STRING);
            }
            if (joined == null || joined.isEmpty()) {
                return 0f;
            }
            int comma = joined.indexOf(',');
            String first = comma < 0 ? joined : joined.substring(0, comma);
            Entity display;
            try {
                display = hitbox.getServer().getEntity(UUID.fromString(first.trim()));
            } catch (IllegalArgumentException e) {
                return 0f;
            }
            if (display == null) {
                return 0f;
            }
            Float baked = display.getPersistentDataContainer()
                    .get(rigYawKey, PersistentDataType.FLOAT);
            return baked != null ? baked : display.getLocation().getYaw();
        }
    }
}
