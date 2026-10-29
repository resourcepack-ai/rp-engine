package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.core.Regions;
import ai.resourcepack.common.scheduler.Tasks;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes custom blocks grow: a crop, a sapling, anything with a {@code grow:}.
 *
 * <p>Vanilla grows a crop on a random tick, and a note block, a mushroom stem
 * and a tripwire get none worth having - so the engine keeps its own list.
 * <strong>The list lives in the world</strong>: each chunk's persistent data
 * holds the blocks in it that can still grow, so nothing is lost on a restart
 * and there is no file of ours to keep in step with the map. In memory it is
 * only the chunks that are loaded, which is also the only place anything
 * grows - a farm nobody is near does not, as in vanilla.
 *
 * <p>About once a second each remembered block gets its chance:
 * {@code 1 / every} for a step, so a block with {@code every: 60s} takes about a
 * minute a stage, unevenly, the way a wheat field does. A block that is no
 * longer ours, or has finished, is forgotten as it is found.
 */
public final class BlockGrowth implements Listener {

    private final Plugin plugin;
    private final CustomBlocks blocks;
    private final NamespacedKey key;

    /** A chunk, by value: a Chunk object is a handle, not something to key a map by. */
    private record Where(java.util.UUID world, int x, int z) {
        static Where of(Chunk chunk) {
            return new Where(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    /** Loaded chunks' growing blocks, by chunk. */
    /**
     * Concurrent: on Folia each chunk's growing is read, ticked and written on
     * its own region's thread, and only ever there, so a chunk's set needs no
     * lock of its own; the map holding them all is shared.
     */
    private final Map<Where, Set<Integer>> growing = new ConcurrentHashMap<>();
    private final Regions.Fanout<Where> chunks;

    private Tasks.Handle task;

    public BlockGrowth(Plugin plugin, CustomBlocks blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
        this.key = new NamespacedKey(plugin, "growing");
        this.chunks = new Regions.Fanout<>(plugin);
    }

    /** Starts checking, and picks up every chunk already loaded. */
    public void start() {
        Regions.loadedChunks(plugin, this::read);
        task = Tasks.globalTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        growing.clear();
        chunks.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) {
        read(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(ChunkUnloadEvent event) {
        growing.remove(Where.of(event.getChunk()));
    }

    private void read(Chunk chunk) {
        int[] saved = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        if (saved == null || saved.length == 0) {
            return;
        }
        Set<Integer> set = new LinkedHashSet<>();
        for (int packed : saved) {
            set.add(packed);
        }
        growing.put(Where.of(chunk), set);
    }

    private void write(Chunk chunk, Set<Integer> set) {
        if (set.isEmpty()) {
            chunk.getPersistentDataContainer().remove(key);
            growing.remove(Where.of(chunk));
            return;
        }
        chunk.getPersistentDataContainer().set(key, PersistentDataType.INTEGER_ARRAY,
                set.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * A block that can grow is remembered once it is down - a tick later, so
     * the state the placement chose is the one read.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Tasks.at(plugin, block.getLocation(), () -> remember(block));
    }

    /** Remembers {@code block} if it is one of ours with growing left to do. */
    public void remember(Block block) {
        Optional<CustomBlocks.Placed> placed = blocks.placedAt(block);
        if (placed.isEmpty() || !placed.get().block().canGrow(placed.get().state())) {
            return;
        }
        Chunk chunk = block.getChunk();
        Set<Integer> set = growing.computeIfAbsent(Where.of(chunk), ignored -> new LinkedHashSet<>());
        if (set.add(pack(block))) {
            write(chunk, set);
        }
    }

    private void tick() {
        for (Where where : new ArrayList<>(growing.keySet())) {
            World world = plugin.getServer().getWorld(where.world());
            if (world == null || !world.isChunkLoaded(where.x(), where.z())) {
                growing.remove(where);
                continue;
            }
            chunks.at(where, new Location(world, where.x() << 4, 0, where.z() << 4), this::grow);
        }
    }

    /** One chunk's pass, on the thread that owns it. */
    private void grow(Where where) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Set<Integer> set = growing.get(where);
        World world = plugin.getServer().getWorld(where.world());
        if (set == null || world == null || !world.isChunkLoaded(where.x(), where.z())) {
            return;
        }
        Chunk chunk = world.getChunkAt(where.x(), where.z());
        boolean changed = false;
        for (Iterator<Integer> each = set.iterator(); each.hasNext(); ) {
            Block block = unpack(chunk, each.next());
            Optional<CustomBlocks.Placed> placed = blocks.placedAt(block);
            if (placed.isEmpty() || !placed.get().block().canGrow(placed.get().state())) {
                each.remove();
                changed = true;
                continue;
            }
            BlockInfo.Growth growth = placed.get().block().growth().orElseThrow();
            if (random.nextInt(growth.seconds()) != 0 || block.getLightLevel() < growth.light()) {
                continue;
            }
            if (step(block, placed.get()) && !placed.get().block().canGrow(
                    placed.get().block().grown(placed.get().state()))) {
                each.remove();
                changed = true;
            }
        }
        if (changed) {
            write(chunk, set);
        }
    }

    private boolean step(Block block, CustomBlocks.Placed placed) {
        return blocks.setState(block, placed.block(), placed.block().grown(placed.state()));
    }

    /** Bone meal on a growing block of ours moves it a stage, as it does a crop. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoneMeal(PlayerInteractEvent event) {
        ItemStack held = event.getItem();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || held == null || held.getType() != Material.BONE_MEAL) {
            return;
        }
        Optional<CustomBlocks.Placed> placed = blocks.placedAt(event.getClickedBlock());
        if (placed.isEmpty() || placed.get().block().growth().isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (!placed.get().block().growth().get().boneMeal() || !placed.get().block().canGrow(placed.get().state())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!step(block, placed.get())) {
            return;
        }
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        for (String name : List.of("HAPPY_VILLAGER", "VILLAGER_HAPPY")) {
            try {
                block.getWorld().spawnParticle(Particle.valueOf(name), block.getLocation().add(0.5, 0.5, 0.5),
                        12, 0.35, 0.35, 0.35);
                break;
            } catch (IllegalArgumentException e) {
                // The particle's other name, on the other side of 1.20.5.
            }
        }
        remember(block);
    }

    private static int pack(Block block) {
        return (block.getX() & 15) | (block.getZ() & 15) << 4
                | (block.getY() - block.getWorld().getMinHeight()) << 8;
    }

    private static Block unpack(Chunk chunk, int packed) {
        return chunk.getBlock(packed & 15, (packed >> 8) + chunk.getWorld().getMinHeight(), packed >> 4 & 15);
    }
}
