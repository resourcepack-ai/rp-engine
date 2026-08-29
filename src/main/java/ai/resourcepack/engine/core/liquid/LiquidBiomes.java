package ai.resourcepack.engine.core.liquid;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LiquidInfo;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Logger;

/**
 * What makes a liquid a colour.
 *
 * <p>Minecraft draws water from one texture and tints it by BIOME. There is no
 * per-block water colour and no way to add one from a plugin, so a liquid that
 * wants to be green has to stand in a biome whose water is green — which means
 * this class does two things a long way apart in time:
 *
 * <ol>
 *   <li><strong>Writes a datapack</strong> holding one biome per tinted
 *       liquid. Worldgen registries are frozen when the server starts, so a
 *       colour that appeared during this run does not exist until the next
 *       one. That is why {@link #write} reports whether it changed anything:
 *       the honest thing to tell an owner is "restart", not to fail quietly.</li>
 *   <li><strong>Paints</strong> that biome over a pool or a placed block, at
 *       runtime, through the ordinary Bukkit API.</li>
 * </ol>
 *
 * <h2>Two limits that are the game's, not ours</h2>
 *
 * <p>Biomes are stored per 4x4x4 cell, so painting one block paints its
 * neighbours; and the client blends biome colours across several blocks, so
 * the edge of a pool fades into the water around it rather than stopping. Both
 * are visible, neither is fixable here, and a pool smaller than about 8 blocks
 * across will not reach its full colour at all. Say so in the docs rather than
 * pretending otherwise.
 */
public final class LiquidBiomes {

    /** Where the generated biomes live. Ours alone; rewritten on every load. */
    private static final String PACK = "rpengine_liquids";

    /** The namespace the generated biomes are registered under. */
    private static final String NAMESPACE = "rpengine";

    /** How big a biome cell is, in blocks, in each direction. */
    private static final int CELL = 4;

    private final Logger log;

    /** Whether the datapack on disk names a colour this run does not have. */
    private volatile boolean restartWanted;

    public LiquidBiomes(Logger log) {
        this.log = log;
    }

    /** The biome a liquid's tint is registered as. */
    public static NamespacedKey keyOf(ContentId liquid) {
        return new NamespacedKey(NAMESPACE,
                (liquid.namespace() + "_" + liquid.path()).toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a restart is needed before the colours written last load can be
     * seen.
     */
    public boolean restartWanted() {
        return restartWanted;
    }

    /**
     * Writes one biome per tinted liquid into the world's datapack folder.
     *
     * <p>Into the main world's folder because that is where the server reads
     * datapacks from, whatever else is loaded — a datapack is per-level and
     * the level is the first world.
     *
     * <p>Rewritten wholesale rather than patched: this directory is ours, its
     * contents are derived from the content folder, and a colour somebody
     * deleted from their pack should stop existing.
     */
    public void write(Collection<LiquidInfo> liquids) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return;
        }
        Path root = new File(worlds.get(0).getWorldFolder(), "datapacks/" + PACK).toPath();
        Path biomes = root.resolve("data/" + NAMESPACE + "/worldgen/biome");
        Set<String> wanted = new LinkedHashSet<>();
        boolean changed = false;
        try {
            Files.createDirectories(biomes);
            changed = writeIfDifferent(root.resolve("pack.mcmeta"), mcmeta());
            for (LiquidInfo liquid : liquids) {
                OptionalInt color = liquid.color();
                if (color.isEmpty()) {
                    continue;
                }
                String file = keyOf(liquid.id()).getKey() + ".json";
                wanted.add(file);
                changed |= writeIfDifferent(biomes.resolve(file), biome(color.getAsInt()));
            }
            changed |= removeOthers(biomes, wanted);
        } catch (IOException e) {
            log.warning("Could not write the liquid colour datapack: " + e.getMessage());
            return;
        }

        // Only ever set, never cleared: once this run is missing a biome it
        // stays missing until the server restarts, and a later reload that
        // happens to write nothing new does not change that.
        if (changed) {
            restartWanted = true;
        }
        if (restartWanted && !wanted.isEmpty()) {
            log.info("Liquid colours were written to " + root
                    + ". Restart the server for them to take effect:"
                    + " biomes are registered at startup and a reload cannot add one.");
        }
    }

    /**
     * Paints a box.
     *
     * <p>Every biome cell the box touches, which is a slightly bigger box: a
     * cell belongs to whichever liquid painted it, so the alternative is a
     * pool with an unpainted rim.
     *
     * @return the biome that was there before, for {@link #restore}
     */
    public Optional<Biome> paint(World world, int minX, int minY, int minZ,
                                 int maxX, int maxY, int maxZ, ContentId liquid) {
        Optional<Biome> tint = biomeOf(liquid);
        if (world == null || tint.isEmpty()) {
            return Optional.empty();
        }
        Biome before = world.getBiome(minX, minY, minZ);
        forEachCell(world, minX, minY, minZ, maxX, maxY, maxZ, tint.get());
        refresh(world, minX, minZ, maxX, maxZ);
        return Optional.of(before);
    }

    /** Puts a box back to the biome it had, as far as one biome can. */
    public void restore(World world, int minX, int minY, int minZ,
                        int maxX, int maxY, int maxZ, Biome before) {
        if (world == null || before == null) {
            return;
        }
        forEachCell(world, minX, minY, minZ, maxX, maxY, maxZ, before);
        refresh(world, minX, minZ, maxX, maxZ);
    }

    /**
     * The biome a liquid is tinted with, or empty when there is no tint or the
     * datapack has not been loaded yet.
     *
     * <p>The second case is the interesting one and is not an error: it is a
     * server that has been told to restart and has not yet.
     */
    public Optional<Biome> biomeOf(ContentId liquid) {
        if (liquid == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Registry.BIOME.get(keyOf(liquid)));
        } catch (RuntimeException | NoClassDefFoundError e) {
            return Optional.empty();
        }
    }

    private static void forEachCell(World world, int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ, Biome biome) {
        for (int x : steps(minX, maxX)) {
            for (int z : steps(minZ, maxZ)) {
                for (int y : steps(Math.max(minY, world.getMinHeight()),
                        Math.min(maxY, world.getMaxHeight() - 1))) {
                    world.setBiome(x, y, z, biome);
                }
            }
        }
    }

    /**
     * One coordinate per biome cell between two bounds, the far edge included.
     *
     * <p>The far edge matters: a box 5 blocks wide covers two cells, and
     * stepping by four from the near edge alone would miss the second.
     */
    private static int[] steps(int from, int to) {
        if (to < from) {
            return new int[0];
        }
        int count = (to - from) / CELL + 1;
        boolean edge = (to - from) % CELL != 0;
        int[] at = new int[edge ? count + 1 : count];
        for (int i = 0; i < count; i++) {
            at[i] = from + i * CELL;
        }
        if (edge) {
            at[count] = to;
        }
        return at;
    }

    /**
     * Sends the changed chunks again.
     *
     * <p>A biome lives in the chunk packet, not the block packet, so a client
     * that is not re-sent the chunk keeps the old colour until it walks away
     * and back. Deprecated in Bukkit and still the only thing in the API that
     * does this; the alternative is NMS, which this plugin does not use.
     */
    @SuppressWarnings("deprecation")
    private static void refresh(World world, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (world.isChunkLoaded(x, z)) {
                    world.refreshChunk(x, z);
                }
            }
        }
    }

    /** True when the file was not already exactly this. */
    private static boolean writeIfDifferent(Path path, String content) throws IOException {
        if (Files.isRegularFile(path)
                && new String(Files.readAllBytes(path), StandardCharsets.UTF_8).equals(content)) {
            return false;
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /** Deletes biomes for liquids that are no longer tinted, or no longer exist. */
    private static boolean removeOthers(Path biomes, Set<String> keep) throws IOException {
        List<Path> gone = new ArrayList<>();
        try (java.util.stream.Stream<Path> found = Files.list(biomes)) {
            found.filter(path -> !keep.contains(path.getFileName().toString())).forEach(gone::add);
        }
        for (Path path : gone) {
            Files.deleteIfExists(path);
        }
        return !gone.isEmpty();
    }

    /**
     * A range rather than one number, so a server on a later Minecraft does
     * not get told its own generated pack is out of date.
     */
    private static String mcmeta() {
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"description\": \"RP Engine liquid colours. Generated — edits are overwritten.\",\n"
                + "    \"pack_format\": 61,\n"
                + "    \"supported_formats\": { \"min_inclusive\": 61, \"max_inclusive\": 9999 }\n"
                + "  }\n"
                + "}\n";
    }

    /**
     * A biome that exists only to be a colour.
     *
     * <p>Nothing spawns in it, nothing generates in it and it never rains
     * there: it is never a biome the world generator places, only one painted
     * over water that is already there, and every field it has beyond the
     * colour is a way for it to surprise somebody.
     *
     * <p>Which is why grass and foliage are left out. A biome cell is 4x4x4
     * and a pool has a bank, so painting one over a lake paints the grass
     * beside it too — naming a colour for that would repaint somebody's shore
     * to go with water they only wanted greener.
     */
    private static String biome(int rgb) {
        return "{\n"
                + "  \"temperature\": 0.5,\n"
                + "  \"downfall\": 0.5,\n"
                + "  \"has_precipitation\": false,\n"
                + "  \"effects\": {\n"
                + "    \"sky_color\": 7907327,\n"
                + "    \"fog_color\": 12638463,\n"
                + "    \"water_color\": " + rgb + ",\n"
                + "    \"water_fog_color\": " + rgb + ",\n"
                + "    \"mood_sound\": {\n"
                + "      \"sound\": \"minecraft:ambient.cave\",\n"
                + "      \"tick_delay\": 6000,\n"
                + "      \"block_search_extent\": 8,\n"
                + "      \"offset\": 2.0\n"
                + "    }\n"
                + "  },\n"
                + "  \"spawners\": {},\n"
                + "  \"spawn_costs\": {},\n"
                + "  \"carvers\": [],\n"
                + "  \"features\": []\n"
                + "}\n";
    }
}
