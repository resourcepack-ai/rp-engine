package ai.resourcepack.engine.core.music;

import java.util.List;
import java.util.Set;

/**
 * Where a piece of music plays by itself: any of some worlds, some WorldGuard
 * regions and some boxes. Free of Bukkit, so where a player is can be asked
 * and tested as plain numbers.
 */
record MusicPlace(Set<String> worlds, Set<String> regions, List<Box> areas) {

    /** Nowhere: the music plays only when something starts it. */
    static final MusicPlace NOWHERE = new MusicPlace(Set.of(), Set.of(), List.of());

    boolean nowhere() {
        return worlds.isEmpty() && regions.isEmpty() && areas.isEmpty();
    }

    /** Whether a player in {@code world} at x, y, z, inside the regions {@code here}, is in it. */
    boolean contains(String world, double x, double y, double z, Set<String> here) {
        if (worlds.contains(world)) {
            return true;
        }
        for (String region : here) {
            if (regions.contains(region)) {
                return true;
            }
        }
        for (Box box : areas) {
            if (box.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** A box of whole blocks, both corners inside it, in one world. */
    record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        static Box of(String world, int[] a, int[] b) {
            return new Box(world, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                    Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
        }

        boolean contains(String in, double x, double y, double z) {
            return world.equals(in)
                    && x >= minX && x < maxX + 1
                    && y >= minY && y < maxY + 1
                    && z >= minZ && z < maxZ + 1;
        }
    }
}
