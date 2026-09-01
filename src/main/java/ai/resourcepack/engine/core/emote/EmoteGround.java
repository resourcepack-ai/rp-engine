package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteResult.Reason;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * Whether a spot in the world will hold a body, and where the nearest one that
 * will is.
 *
 * <p>Split out of {@link EmoteDirector} because none of it knows what an emote
 * is. Every method here asks the world a question about collision — is there a
 * floor under these feet, does a player fit in this box, is this player in a
 * state where they are standing anywhere at all — and the director uses the
 * answers to decide where a rig goes and whether an emote may start.
 *
 * <p>They were instance methods that read no field, which is the shape a
 * subject usually takes just before it turns out to be one.
 *
 * <p>Unloaded chunks are consistently treated as NOT ground. The alternative
 * is loading a chunk to answer a cosmetic question, and a player standing over
 * one is not a case that has to work.
 */
final class EmoteGround {

    private EmoteGround() {
    }

    /** How far below the feet counts as "there is ground there". */
    private static final double GROUND_PROBE = 0.08;

    /**
     * How far down a performer's spot may be dropped to find ground.
     *
     * A step and a bit. The offsets an emote authors are horizontal, so the
     * ground under a partner is usually the ground under the lead — but a
     * slab, a stair or a shallow slope puts it a little lower, and refusing
     * those would make the feature unusable anywhere but a flat floor.
     */
    private static final int GROUND_SEARCH_DOWN = 3;

    /** Half the player's width, for probing the corners they stand on. */
    private static final double HALF_WIDTH = 0.3;

    /**
     * Whether somebody may be put here: loaded, in a world, and not inside a
     * block. An unloaded chunk answers no rather than being loaded to find out
     * - generating terrain to place a cosmetic is not a trade worth making.
     */
    /**
     * The nearest place at or just below this spot that somebody can stand on.
     *
     * <p><b>Ground is now a requirement, and under spectator it was not.</b> A
     * spectator hovers wherever they are put; an invisible player falls. So a
     * performer placed over a one-block drop used to stand there and now drops
     * out of their own emote — and because a participant moving ends it for
     * everybody, one badly-placed partner cancelled the whole thing before it
     * played a frame. That is the bug this exists to stop.
     *
     * <p>Searching DOWN a few blocks rather than demanding an exact match is
     * what makes the feature usable off a flat floor: an emote's offsets are
     * horizontal, so a slab, a stair or a shallow slope puts the partner's
     * ground slightly below the lead's, and refusing those would refuse most
     * real builds. It never searches UP — lifting somebody onto a block they
     * were not placed on is a different spot from the one the emote authored.
     *
     * @return a standable location, or null if there is none.
     */
    /**
     * The end of a path, put down on the floor it finished on.
     *
     * <p>An authored landing dips the body into the ground on purpose - that
     * is what a flip's last frames look like - and which frame the last step
     * of the emote happened to sample is a coin toss, because steps land on a
     * two-tick grid and an emote's length does not divide by it. Half the
     * flips therefore ended a few centimetres inside the block, and vanilla
     * resolves a collision it is already inside by leaving it there: "the
     * right spot, legs through the floor" is where the player stays.
     *
     * <p>So the ending is not merely checked for somewhere the body FITS -
     * {@link #safeDestination} shrinks its box by a margin and would happily
     * accept a spot a hair under the surface, which is the 50/50 above. The
     * feet are snapped onto the real collision top under them, so a slab, a
     * stair or a path block lands as exactly as a full cube does.
     *
     * <p>Only ever upward, and never by more than a block. Somebody who
     * finishes in the air is left there for gravity to deal with, the way any
     * jump ends; a spot with no floor within a block is not a landing to tidy
     * up but a path that ended somewhere it should not have, and the caller
     * puts them back on the origin instead.
     *
     * @return where to land them, or null if there is nowhere to.
     */
    static Location grounded(Location end) {
        if (end == null || end.getWorld() == null) return null;
        Double floor = floorUnder(end);
        if (floor != null && floor > end.getY()) {
            Location landed = end.clone();
            landed.setY(floor);
            if (safeDestination(landed)) return landed;
        }
        // Nothing to snap to, or snapping put them somewhere they don't fit
        // (a landing under a slab). The old climb still covers those: any spot
        // within a block that holds a body beats leaving them in the floor.
        for (int step = 0; step <= 16; step++) {
            Location candidate = end.clone().add(0, step / 16.0, 0);
            if (safeDestination(candidate)) return candidate;
        }
        return null;
    }

    /**
     * The height of the surface under this spot, or null if there isn't one.
     *
     * <p>Collision tops, not block tops: the answer for a slab is y.5 and for
     * a stair it is whichever half the foot is over, which is the whole point
     * of asking. The five probes are the ones {@link #standingOnSomething}
     * uses - the centre and the four corners of the player's footprint - and
     * the highest of them wins, because that is the one holding the body up.
     *
     * <p>Searches a block either side of the spot. Anything higher than that
     * is a wall the player is standing against rather than a floor they are
     * standing on, and lifting them onto it would be a different place from
     * the one the emote authored.
     */
    static Double floorUnder(Location spot) {
        org.bukkit.World world = spot.getWorld();
        if (world == null) return null;
        double[][] offsets = {
            {0, 0}, {HALF_WIDTH, HALF_WIDTH}, {HALF_WIDTH, -HALF_WIDTH},
            {-HALF_WIDTH, HALF_WIDTH}, {-HALF_WIDTH, -HALF_WIDTH},
        };
        Double best = null;
        for (double[] offset : offsets) {
            double x = spot.getX() + offset[0];
            double z = spot.getZ() + offset[1];
            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) continue;
            for (int y = (int) Math.floor(spot.getY() + 1.0); y >= (int) Math.floor(spot.getY() - 1.0); y--) {
                org.bukkit.block.Block block = world.getBlockAt(blockX, y, blockZ);
                if (!block.getType().isSolid()) continue;
                for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                    BoundingBox placed = box.clone().shift(blockX, y, blockZ);
                    // Only the shapes this foot is actually over: a stair's
                    // upper step is not what holds up somebody standing on its
                    // lower half.
                    if (x < placed.getMinX() || x > placed.getMaxX()) continue;
                    if (z < placed.getMinZ() || z > placed.getMaxZ()) continue;
                    double top = placed.getMaxY();
                    if (top > spot.getY() + 1.0) continue;
                    if (best == null || top > best) best = top;
                }
            }
        }
        return best;
    }

    static Location standingSpot(Location target) {
        if (target == null || target.getWorld() == null) return null;
        for (int drop = 0; drop <= GROUND_SEARCH_DOWN; drop++) {
            Location candidate = target.clone().subtract(0, drop, 0);
            if (safeDestination(candidate) && standingOnSomething(candidate)) return candidate;
        }
        return null;
    }

    static boolean safeDestination(Location target) {
        if (target.getWorld() == null) return false;
        if (!target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) return false;
        // Whether a PLAYER FITS here, asked of the blocks' real collision
        // shapes — not whether the block at floor(y) is solid. That older test
        // refused the spot the player was already standing on whenever they
        // stood on anything shorter than a full cube: on a slab your feet are
        // at y.5, so "the block at the target" IS the slab under you, and root
        // motion died on its first step everywhere the lobby is paved with
        // them. The box is the player's (0.6 x 1.8), shrunk by a margin so
        // standing exactly on a surface, or a teleport acknowledged a hair
        // low, doesn't read as being inside the floor.
        final double margin = 0.05;
        BoundingBox player = new BoundingBox(
            target.getX() - 0.3 + margin, target.getY() + margin, target.getZ() - 0.3 + margin,
            target.getX() + 0.3 - margin, target.getY() + 1.8 - margin, target.getZ() + 0.3 - margin);
        for (int x = (int) Math.floor(player.getMinX()); x <= (int) Math.floor(player.getMaxX()); x++) {
            for (int y = (int) Math.floor(player.getMinY()); y <= (int) Math.floor(player.getMaxY()); y++) {
                for (int z = (int) Math.floor(player.getMinZ()); z <= (int) Math.floor(player.getMaxZ()); z++) {
                    org.bukkit.block.Block block = target.getWorld().getBlockAt(x, y, z);
                    if (!block.getType().isSolid()) continue;
                    for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                        // Block shapes are block-local; move this one into the world.
                        if (box.clone().shift(x, y, z).overlaps(player)) return false;
                    }
                }
            }
        }
        return true;
    }

    /** Why this player is not somewhere an emote can happen, or null. */
    static Reason groundRefusal(Player player) {
        if (player.getVehicle() != null) {
            return Reason.RIDING;
        }
        if (player.isGliding()) {
            return Reason.GLIDING;
        }
        if (player.isFlying()) {
            return Reason.FLYING;
        }
        if (player.isSwimming() || player.isInWater()) {
            return Reason.IN_WATER;
        }
        Location feet = player.getLocation();
        Material at = feet.getBlock().getType();
        if (at == Material.LAVA || at == Material.WATER) {
            return Reason.IN_BLOCK;
        }
        if (!standingOnSomething(feet)) {
            return Reason.NOT_ON_GROUND;
        }
        return null;
    }

    /**
     * Whether anything solid holds up the box whose centre is this location.
     *
     * Reads the world, so it cannot be lied to by a client. Unloaded chunks are
     * treated as NOT ground: the alternative is loading a chunk to answer a
     * cosmetic question, and a player standing over one is not a case that has
     * to work.
     */
    static boolean standingOnSomething(Location feet) {
        double[][] offsets = {
            {0, 0}, {HALF_WIDTH, HALF_WIDTH}, {HALF_WIDTH, -HALF_WIDTH},
            {-HALF_WIDTH, HALF_WIDTH}, {-HALF_WIDTH, -HALF_WIDTH},
        };
        for (double[] offset : offsets) {
            Location probe = feet.clone().add(offset[0], -GROUND_PROBE, offset[1]);
            if (!probe.getWorld().isChunkLoaded(probe.getBlockX() >> 4, probe.getBlockZ() >> 4)) continue;
            Material material = probe.getBlock().getType();
            if (material.isSolid()) return true;
        }
        return false;
    }
}
