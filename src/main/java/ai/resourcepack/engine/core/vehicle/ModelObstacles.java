package ai.resourcepack.engine.core.vehicle;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The placed models a vehicle cannot drive through.
 *
 * <p><strong>A placed model is two entities and neither of them collides with
 * anything.</strong> What you see is an {@link org.bukkit.entity.ItemDisplay},
 * which has no collision at all; what you can punch is an {@link Interaction},
 * which has a box and still does not stop a moving thing. So a car went through
 * a fence, a statue and a parked chair as if none of them were there, and there
 * was nothing in the world for {@link BlockSurfaces} to find — its questions are
 * all about BLOCKS, and a model is not one.
 *
 * <p>The fix could have been a block: put barriers under the piece and every
 * existing question answers itself. That is what {@code place: solid: true}
 * already does for a walking player, and it is exactly why it is not the answer
 * here — it writes to the world (one cube, at the anchor, whatever shape the
 * model is), it has to be swept up when the piece breaks, and it stops people
 * as well as vehicles. Collision for a vehicle should cost the world nothing
 * and should follow the piece's real hitbox, so this asks the entities instead.
 *
 * <p><strong>The box is the piece's own hitbox, not a second opinion about how
 * big it is.</strong> An {@link Interaction} is anchored at its feet and carries
 * the width and height the placement gave it — measured geometry for a piece out
 * of a content folder, the placement's size for one off a Studio push. Deriving
 * a different box here would be a model you can drive through the part of and
 * punch the rest of, which is the sort of disagreement nobody would ever think
 * to look for.
 *
 * <p><strong>Which means a pushed model is as coarse as its hitbox is.</strong>
 * A Studio placement is sized by the placement's own scale rather than by the
 * art, so a flat piece at 1x is a one-block cube here exactly as it is to a
 * fist — and a vehicle climbs onto it like a kerb instead of ignoring it. That
 * is what the pack's own {@code vehicle-collision} switch is for, and it is why
 * the fix, when there is one, belongs in what the placement measures rather
 * than in a second measurement taken here.
 *
 * <p>Gathered once per tick and then asked several times, because the answers
 * are pure arithmetic and the gathering is a chunk scan: {@link Ride} puts four
 * or five questions to it on a tick where a vehicle hits something, and doing
 * the scan per question would be four scans for one wall.
 *
 * <p>Heights are absolute world Y and {@link Double#NaN} means nothing is
 * there, matching {@link BlockSurfaces} exactly — the two are read side by side
 * and a second convention would be a bug waiting for the first vehicle to park
 * on a model at y=0.
 */
final class ModelObstacles {

    /** Nothing in the way. The answer for most vehicles on most ticks. */
    static final ModelObstacles NONE = new ModelObstacles(List.of());

    /** One box per obstacle: minX, minY, minZ, maxX, maxY, maxZ. */
    private final List<double[]> boxes;

    private ModelObstacles(List<double[]> boxes) {
        this.boxes = boxes;
    }

    /**
     * The box an {@link Interaction} of this size at this place stands in.
     *
     * <p>Its own method because it is the whole conversion — an Interaction is
     * anchored at its FEET and is as deep as it is wide — and because it is the
     * seam the arithmetic below is tested through without a server.
     */
    private static double[] box(double x, double y, double z, double width, double height) {
        double half = width / 2;
        return new double[] {x - half, y, z - half, x + half, y + height, z + half};
    }

    /** Engine internal; one obstacle, for tests that have no world to scan. */
    static ModelObstacles of(double x, double y, double z, double width, double height) {
        return new ModelObstacles(List.of(box(x, y, z, width, height)));
    }

    /** Whether there is anything at all, so a caller can skip the arithmetic. */
    boolean isEmpty() {
        return boxes.isEmpty();
    }

    /**
     * Whether a point is inside one of them.
     *
     * <p>Half open at the top, like a block's collision box: a vehicle resting
     * ON a model is not inside it, and a closed test would leave it permanently
     * stuck in the thing it had just landed on.
     */
    boolean solidAt(double x, double y, double z) {
        for (double[] box : boxes) {
            if (over(box, x, z) && y >= box[1] && y < box[4]) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest model top at this point between {@code floor} and
     * {@code ceiling}, or NaN for nothing in that band.
     *
     * <p>One method for what {@link BlockSurfaces} needs three of, because a
     * box has no shape to discover: resting on a model, climbing onto one and
     * reading a wheel's height are the same question asked over three
     * different bands.
     */
    double topAt(double x, double z, double floor, double ceiling) {
        double best = Double.NaN;
        for (double[] box : boxes) {
            if (!over(box, x, z)) {
                continue;
            }
            double top = box[4];
            if (top < floor || top > ceiling) {
                continue;
            }
            if (Double.isNaN(best) || top > best) {
                best = top;
            }
        }
        return best;
    }

    private static boolean over(double[] box, double x, double z) {
        return x >= box[0] && x <= box[3] && z >= box[2] && z <= box[5];
    }

    /**
     * Finds the placed models around a vehicle.
     *
     * <p>Held by the runtime rather than made per call: the two keys are the
     * two placement listeners' own, and reading them off a fresh
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
         * Whether a model of this id stops a vehicle. Supplied by the plugin,
         * which is the only place that can see both the content folder's
         * definitions and the pushed pack's; this package deliberately learns
         * nothing about either.
         */
        private volatile Predicate<String> stops = id -> true;

        Sensor(Plugin plugin) {
            this.authoredKey = new NamespacedKey(plugin, "model");
            this.studioKey = new NamespacedKey(plugin, "model-id");
        }

        /** Adopts the answer, as a reload or a push does. */
        void stops(Predicate<String> stops) {
            this.stops = stops == null ? id -> true : stops;
        }

        /**
         * Every collidable placed model whose box could reach a vehicle at
         * {@code at}.
         *
         * @param reach  how far out to look horizontally, in blocks
         * @param height how far to look up and down
         */
        ModelObstacles around(World world, Location at, double reach, double height) {
            List<double[]> found = null;
            for (Entity nearby : world.getNearbyEntities(at, reach, height, reach)) {
                if (!(nearby instanceof Interaction)) {
                    continue;
                }
                Interaction hitbox = (Interaction) nearby;
                // <strong>A zero-sized box is not an obstacle, and skipping it
                // is not tidiness.</strong> An ANIMATED VEHICLE hangs its rig
                // off a zero-sized Interaction wearing this very key (see
                // RigCarrier's yaw host) — it is a handle, not a hitbox, and it
                // sits at the vehicle's own position. Read as a box it is a
                // surface exactly under the vehicle's centre sample, which is
                // the vehicle holding itself up: permanently supported,
                // never falling, hovering wherever it was spawned.
                if (hitbox.getInteractionWidth() <= 0 || hitbox.getInteractionHeight() <= 0) {
                    continue;
                }
                String id = hitbox.getPersistentDataContainer()
                        .get(authoredKey, PersistentDataType.STRING);
                if (id == null) {
                    id = hitbox.getPersistentDataContainer()
                            .get(studioKey, PersistentDataType.STRING);
                }
                // Not a placed model at all: a vehicle's own seat and body
                // boxes are Interactions too, and they carry the vehicle keys
                // rather than either of these.
                if (id == null || !stops.test(id)) {
                    continue;
                }
                Location base = hitbox.getLocation();
                if (found == null) {
                    found = new ArrayList<>(4);
                }
                found.add(box(base.getX(), base.getY(), base.getZ(),
                        hitbox.getInteractionWidth(), hitbox.getInteractionHeight()));
            }
            return found == null ? NONE : new ModelObstacles(found);
        }
    }
}
