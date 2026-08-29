package ai.resourcepack.engine.core.liquid;

import ai.resourcepack.engine.api.ContentId;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Where each pool of a custom liquid is.
 *
 * <p>A pool is a box in a world and the liquid it counts as. That is the whole
 * model: what makes a lake acid is being inside the box somebody drew around
 * it, not anything about the blocks in it, which are ordinary water.
 *
 * <p>Boxes rather than a record of every block, for a reason worth stating: a
 * lake is thousands of blocks, it changes shape as it flows, and a per-block
 * record would be wrong within a second of somebody breaking a bank. A box is
 * a rule about a place, and places do not move.
 *
 * <p>Saved as plain JSON beside the other stores, because a pool outlives a
 * restart and a server owner should be able to read and fix one by hand.
 */
public final class LiquidPools {

    /** One marked-out volume, and what it counts as. */
    public static final class Pool {

        String liquid;
        String world;
        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;

        /**
         * The biome that was here before this pool was painted, as a key.
         *
         * <p>One key for the whole box rather than one per cell: a box is
         * already a rule about a place rather than a record of its blocks, and
         * a pool that spanned two biomes would need thousands of entries in
         * this file to put back something nobody can see anyway. Clearing a
         * pool paints it all back to this.
         */
        String was;

        /** Which liquid this pool is. */
        public Optional<ContentId> liquid() {
            return ContentId.parse(liquid);
        }

        /** The world it is in. */
        public String world() {
            return world;
        }

        /** The corner nearest the origin: minimum x, y, z. */
        public int[] min() {
            return new int[] {minX, minY, minZ};
        }

        /** The far corner: maximum x, y, z. */
        public int[] max() {
            return new int[] {maxX, maxY, maxZ};
        }

        /** The biome key this place had before the pool was painted over it. */
        public Optional<String> was() {
            return was == null || was.isEmpty() ? Optional.empty() : Optional.of(was);
        }

        /** Records that, once, so clearing the pool can undo it. */
        public void remember(String biomeKey) {
            if (was == null) {
                was = biomeKey;
            }
        }

        /** Whether a block is inside, or touching a face of, this box. */
        boolean touches(String inWorld, int x, int y, int z) {
            return world != null && world.equals(inWorld)
                    && x >= minX - 1 && x <= maxX + 1
                    && y >= minY - 1 && y <= maxY + 1
                    && z >= minZ - 1 && z <= maxZ + 1;
        }

        /** Grows the box to hold a block. */
        void grow(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        /** Whether a point is inside. */
        public boolean contains(String inWorld, double x, double y, double z) {
            // The world is only null in a file somebody hand-edited, and this
            // is asked once a second for every player on the server: a throw
            // here would be a stack trace a second for ever.
            return world != null && world.equals(inWorld)
                    && x >= minX && x <= maxX + 1
                    && y >= minY && y <= maxY + 1
                    && z >= minZ && z <= maxZ + 1;
        }

        @Override
        public String toString() {
            return liquid + " in " + world + " ["
                    + minX + " " + minY + " " + minZ + " to " + maxX + " " + maxY + " " + maxZ + "]";
        }
    }

    /** What the file holds. A wrapper so a later field has somewhere to go. */
    private static final class Saved {
        List<Pool> pools;
    }

    private final Gson gson = new Gson();
    private final File file;
    private volatile List<Pool> pools = List.of();

    public LiquidPools(File dataFolder) {
        this.file = new File(dataFolder, "liquids.json");
    }

    /** Every pool, in the order they were made. */
    public List<Pool> pools() {
        return pools;
    }

    /**
     * The pool at a point, or empty for ordinary water.
     *
     * <p>First match wins, and pools keep the order they were made in, so a
     * small pool drawn inside a big one only counts if it was drawn first.
     * Said plainly rather than solved with a priority field nobody would set.
     */
    public Optional<Pool> at(String world, double x, double y, double z) {
        for (Pool pool : pools) {
            if (pool.contains(world, x, y, z)) {
                return Optional.of(pool);
            }
        }
        return Optional.empty();
    }

    /** Marks out a new pool. */
    public Pool add(ContentId liquid, String world, int[] from, int[] to) {
        Pool pool = new Pool();
        pool.liquid = liquid.toString();
        pool.world = world;
        pool.minX = Math.min(from[0], to[0]);
        pool.minY = Math.min(from[1], to[1]);
        pool.minZ = Math.min(from[2], to[2]);
        pool.maxX = Math.max(from[0], to[0]);
        pool.maxY = Math.max(from[1], to[1]);
        pool.maxZ = Math.max(from[2], to[2]);

        List<Pool> next = new ArrayList<>(pools);
        next.add(pool);
        pools = List.copyOf(next);
        return pool;
    }

    /**
     * The pool a bucket-placed block belongs to, growing one if it can.
     *
     * <p>A block next to a pool of the same liquid joins it rather than
     * starting a second, which is what makes a pond built bucket by bucket one
     * pool with one rule rather than fifty. A block on its own starts a pool
     * one block big.
     *
     * <p>The growing is why this is here rather than in the caller: the box
     * only ever gets bigger, so two people filling the same pond from opposite
     * ends end up with one pool between them.
     */
    public Pool addBlock(ContentId liquid, String world, int x, int y, int z) {
        String name = liquid.toString();
        for (Pool pool : pools) {
            if (name.equals(pool.liquid) && pool.touches(world, x, y, z)) {
                pool.grow(x, y, z);
                return pool;
            }
        }
        return add(liquid, world, new int[] {x, y, z}, new int[] {x, y, z});
    }

    /** Forgets the pool at a point, if there is one. */
    public Optional<Pool> removeAt(String world, double x, double y, double z) {
        Optional<Pool> found = at(world, x, y, z);
        found.ifPresent(pool -> {
            List<Pool> next = new ArrayList<>(pools);
            next.remove(pool);
            pools = List.copyOf(next);
        });
        return found;
    }

    /** Reads what was saved. A missing file is an empty world, not a problem. */
    public void load(Logger logger) {
        if (!file.isFile()) {
            return;
        }
        try {
            Saved saved = gson.fromJson(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), Saved.class);
            List<Pool> read = new ArrayList<>();
            for (Pool pool : saved == null || saved.pools == null ? List.<Pool>of() : saved.pools) {
                // A pool with no world belongs to no place. Dropped rather than
                // carried, so the file is the only thing that has to be fixed.
                if (pool != null && pool.world != null && pool.liquid != null) {
                    read.add(pool);
                } else {
                    logger.warning("liquids.json holds a pool with no world or no liquid. Skipped.");
                }
            }
            pools = List.copyOf(read);
        } catch (IOException | JsonSyntaxException e) {
            // Kept rather than overwritten: a file somebody hand-edited into
            // invalidity is one they can still fix, and rewriting it here would
            // throw away the pools they were trying to keep.
            logger.warning("liquids.json could not be read, so no pools are active: " + e.getMessage());
        }
    }

    /** Writes them out. */
    public void save(Logger logger) {
        Saved saved = new Saved();
        saved.pools = new ArrayList<>(pools);
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), gson.toJson(saved).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warning("Could not write liquids.json: " + e.getMessage());
        }
    }
}
