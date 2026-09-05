package ai.resourcepack.engine.core.vehicle;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/**
 * What the blocks under a vehicle are actually SHAPED like.
 *
 * <p><strong>A block is not a cube, and asking whether it is solid throws away
 * the only thing a vehicle needs to know: how tall it is.</strong> This is the
 * whole reason the class exists. A slab is half a block, a snow layer is an
 * eighth per layer, a path and a farmland are a sixteenth short, a carpet is a
 * sixteenth tall — and a vehicle that answers "solid" with a yes drives at
 * whole-block heights over every one of them, floating half a block above a
 * slab road and sinking through a snowfield. Both were reported as vehicles
 * ignoring the ground they were on, which is exactly what they were doing.
 *
 * <p>So every question here is about a REAL collision box: how high is the
 * surface at this point, and is this point inside something. The answers come
 * from {@code Block#getCollisionShape()}, whose boxes are the ones the client
 * draws and the ones a walking player stands on — so a vehicle and a player
 * end up at the same height on the same block, which is the property that
 * makes a slab road look right.
 *
 * <p><strong>Collision, not outline.</strong> The two differ, and the
 * difference is deliberate on vanilla's part: a torch, a flower and a
 * one-layer snow have an outline and no collision, so a player walks through
 * (or over) them. A vehicle should too, and reading the outline instead would
 * stop a car dead at a dandelion.
 *
 * <p>Heights are absolute world Y, and <strong>{@link Double#NaN} means there
 * is nothing there</strong> rather than zero — zero is a real height, at the
 * bottom of a world that now starts below it.
 */
final class BlockSurfaces {

    /**
     * A shade of slack, in blocks, for comparisons against a surface a vehicle
     * is already standing on.
     *
     * <p>Floating point puts a vehicle resting on a slab at 64.499999999 as
     * often as at 64.5, and without this it lands, is asked whether it is
     * supported, hears no, and falls a hair — every tick, for ever.
     */
    static final double EPSILON = 1e-6;

    private BlockSurfaces() {
    }

    /**
     * The top of one block's collision shape at a point, or NaN if the point
     * misses it.
     *
     * <p>The x and z are world coordinates and are taken against the block's
     * own corner, so a stair — two boxes, one of them half the block — answers
     * with the height of the half the vehicle is actually over.
     */
    static double top(Block block, double x, double z) {
        // Air is the overwhelmingly common answer and the only one worth a
        // shortcut: everything else has to be asked, because "is this material
        // solid" is the question this class exists to stop anybody asking.
        if (block.getType().isAir()) {
            return Double.NaN;
        }
        double localX = x - block.getX();
        double localZ = z - block.getZ();
        double best = Double.NaN;
        for (BoundingBox shape : block.getCollisionShape().getBoundingBoxes()) {
            if (localX < shape.getMinX() || localX > shape.getMaxX()) {
                continue;
            }
            if (localZ < shape.getMinZ() || localZ > shape.getMaxZ()) {
                continue;
            }
            double top = block.getY() + shape.getMaxY();
            if (Double.isNaN(best) || top > best) {
                best = top;
            }
        }
        return best;
    }

    /** Whether a point is inside something a vehicle cannot be inside. */
    static boolean solidAt(World world, double x, double y, double z) {
        Block block = world.getBlockAt(new Location(world, x, y, z));
        if (block.getType().isAir()) {
            return false;
        }
        double localX = x - block.getX();
        double localY = y - block.getY();
        double localZ = z - block.getZ();
        for (BoundingBox shape : block.getCollisionShape().getBoundingBoxes()) {
            if (shape.contains(localX, localY, localZ)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The height of whatever a vehicle at {@code y} is resting on at this
     * point, or NaN for thin air.
     *
     * <p>Two blocks are read at most, and the second only when the first
     * answers nothing: the one the base is in or just below, and then the one
     * under that — a fence, a wall and a fence gate all stand taller than
     * their own cube, so the surface a vehicle lands on can belong to the
     * block below the one its feet are in.
     *
     * @param floor   surfaces below this are too far down to be resting on
     * @param ceiling surfaces above this are ignored, which is what stops a
     *                landing from becoming a lift: a vehicle may settle onto
     *                something under it and must never be pushed up onto
     *                something it drove into
     */
    static double resting(World world, double x, double y, double z, double floor, double ceiling) {
        int start = (int) Math.floor(y - 0.05);
        for (int level = start; level >= start - 1; level--) {
            double top = top(world.getBlockAt(new Location(world, x, level, z)), x, z);
            if (Double.isNaN(top) || top < floor - EPSILON || top > ceiling + EPSILON) {
                continue;
            }
            return top;
        }
        return Double.NaN;
    }

    /**
     * The height of the thing in the way at this point, or NaN if nothing is
     * within reach above the vehicle's own base.
     *
     * <p>This is what a kerb, a slab, a snow layer and a step all are, and
     * measuring it rather than assuming a block is what makes driving onto
     * each of them a rise of its own height instead of a hop and a drop.
     *
     * @param feet  the vehicle's base, so only what stands ABOVE it counts
     * @param reach how far up a vehicle can climb in one step
     */
    static double obstruction(World world, double x, double z, double feet, double reach) {
        double best = Double.NaN;
        int highest = (int) Math.floor(feet + reach);
        for (int level = (int) Math.floor(feet); level <= highest; level++) {
            double top = top(world.getBlockAt(new Location(world, x, level, z)), x, z);
            if (Double.isNaN(top) || top <= feet + EPSILON || top > feet + reach + EPSILON) {
                continue;
            }
            if (Double.isNaN(best) || top > best) {
                best = top;
            }
        }
        return best;
    }
}
