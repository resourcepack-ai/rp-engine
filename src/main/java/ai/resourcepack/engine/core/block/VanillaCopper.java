package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.Items;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps waxed copper working for the people who build with it, once a kind of
 * it has been taken over by a shaped custom block.
 *
 * <p>A custom stair is a waxed weathered cut copper stair underneath (see
 * {@link ai.resourcepack.engine.api.BlockInfo.Shape}), and the resource pack
 * repaints that whole kind of stair. Left there, a builder who waxes their
 * weathered stairs would find them turning into somebody's custom ones. So the
 * real thing is quietly stood in for by its twin: the <strong>same copper
 * without its wax</strong>, which looks identical, marked as waxed in the
 * chunk's own persistent data so it never ages, gives back the waxed item when
 * broken, and loses its wax to an axe exactly as waxed copper does.
 *
 * <p>The same trick Oraxen uses for the same pool, which matters: a server
 * that moved from Oraxen has its builders' copper in this state already.
 *
 * <p>Three ways a waxed block arrives, and each is caught: a player placing
 * one, honeycomb on its twin, and a new chunk generating one (trial chambers
 * are full of waxed copper). What is not caught is waxed copper already
 * standing in a world from before the kind was taken, which takes the custom
 * look - FORMAT.md says so, because nothing here can tell it apart from a
 * custom block.
 *
 * <p>The petrified oak slab's twin is the oak slab, which is what it is in
 * every way but fire; it needs no mark at all.
 */
public final class VanillaCopper implements Listener {

    private final Plugin plugin;
    private final Items items;
    private final CustomBlocks blocks;
    private final NamespacedKey key;

    public VanillaCopper(Plugin plugin, Items items, CustomBlocks blocks) {
        this.plugin = plugin;
        this.items = items;
        this.blocks = blocks;
        this.key = new NamespacedKey(plugin, "waxed");
    }

    /** The taken-over kind a twin stands in for, or null if {@code twin} is not one. */
    private Material waxedOf(Material twin) {
        String name = twin.name().toLowerCase(Locale.ROOT);
        for (Material taken : blocks.takenMaterials()) {
            String twinName = BlockStates.vanillaTwin(taken.name().toLowerCase(Locale.ROOT));
            if (name.equals(twinName)) {
                return taken;
            }
        }
        return null;
    }

    /** The twin that stands in for a taken kind, or null. */
    private static Material twinOf(Material taken) {
        String twin = BlockStates.vanillaTwin(taken.name().toLowerCase(Locale.ROOT));
        return twin == null ? null : Material.matchMaterial(twin);
    }

    // ---- arriving --------------------------------------------------------

    /**
     * A builder places real waxed copper of a taken kind: it goes down as its
     * twin, marked waxed.
     *
     * <p>A tick later, so a door has both its halves by then. And a vanilla
     * slab is never let double up a custom one, which would make the pair
     * neither.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material placed = event.getBlockPlaced().getType();
        if (!blocks.takenMaterials().contains(placed) || items.idOf(event.getItemInHand()).isPresent()) {
            return;
        }
        if (event.getBlockReplacedState().getType() == placed) {
            event.setCancelled(true);
            return;
        }
        Material twin = twinOf(placed);
        if (twin == null) {
            return;
        }
        Block block = event.getBlockPlaced();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            swap(block, placed, twin);
            if (block.getBlockData() instanceof Bisected) {
                swap(block.getRelative(BlockFace.UP), placed, twin);
            }
        });
    }

    /** Turns one block of a taken kind into its twin, marked waxed, keeping every property. */
    private void swap(Block block, Material taken, Material twin) {
        if (block.getType() != taken) {
            return;
        }
        String data = block.getBlockData().getAsString();
        String takenName = "minecraft:" + taken.name().toLowerCase(Locale.ROOT);
        String twinName = "minecraft:" + twin.name().toLowerCase(Locale.ROOT);
        BlockData swapped = plugin.getServer().createBlockData(twinName + data.substring(takenName.length()));
        block.setBlockData(swapped, false);
        if (twin != Material.OAK_SLAB) {
            mark(block, true);
        }
    }

    /** Honeycomb on a twin marks it waxed instead of making it the taken kind. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWax(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        ItemStack held = event.getItem();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || held == null) {
            return;
        }
        if (held.getType() == Material.HONEYCOMB && waxedOf(block.getType()) != null) {
            event.setCancelled(true);
            if (marked(block)) {
                return;
            }
            markWhole(block, true);
            if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
                held.setAmount(held.getAmount() - 1);
            }
            effect(block, Sound.ITEM_HONEYCOMB_WAX_ON, "WAX_ON");
            swing(event);
        } else if (held.getType().name().endsWith("_AXE") && marked(block)) {
            // Waxed copper loses its wax to an axe before anything else, so
            // the twin loses its mark and starts ageing from here.
            event.setCancelled(true);
            markWhole(block, false);
            effect(block, Sound.ITEM_AXE_WAX_OFF, "WAX_OFF");
            swing(event);
        }
    }

    private static void swing(PlayerInteractEvent event) {
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) {
            event.getPlayer().swingOffHand();
        } else {
            event.getPlayer().swingMainHand();
        }
    }

    private static void effect(Block block, Sound sound, String particle) {
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), sound, 1f, 1f);
        try {
            block.getWorld().spawnParticle(Particle.valueOf(particle),
                    block.getLocation().add(0.5, 0.5, 0.5), 8, 0.4, 0.4, 0.4);
        } catch (IllegalArgumentException e) {
            // A server whose particle names moved; the sound is enough.
        }
    }

    /**
     * A new chunk's waxed copper of a taken kind becomes twins, before anybody
     * sees it. The scan runs off a snapshot, off the main thread, and only
     * the blocks it found are touched on it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunk(ChunkLoadEvent event) {
        if (!event.isNewChunk()) {
            return;
        }
        Set<Material> taken = blocks.takenMaterials();
        if (taken.stream().noneMatch(material -> twinOf(material) != null)) {
            return;
        }
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<int[]> found = new ArrayList<>();
            for (int section = 0; section < (maxY - minY) / 16; section++) {
                if (snapshot.isSectionEmpty(section)) {
                    continue;
                }
                int base = minY + section * 16;
                for (int y = base; y < base + 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (taken.contains(snapshot.getBlockType(x, y, z))) {
                                found.add(new int[] {x, y, z});
                            }
                        }
                    }
                }
            }
            if (found.isEmpty()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!chunk.isLoaded()) {
                    return;
                }
                for (int[] at : found) {
                    Block block = chunk.getBlock(at[0], at[1], at[2]);
                    Material twin = twinOf(block.getType());
                    if (twin != null) {
                        swap(block, block.getType(), twin);
                    }
                }
            });
        });
    }

    // ---- staying ---------------------------------------------------------

    /** A waxed twin does not age. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAge(BlockFormEvent event) {
        if (marked(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** A piston carries the mark with the block. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent event) {
        carry(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent event) {
        carry(event.getBlocks(), event.getDirection());
    }

    private void carry(List<Block> moved, BlockFace direction) {
        List<Block> marks = new ArrayList<>();
        for (Block block : moved) {
            if (marked(block)) {
                marks.add(block);
            }
        }
        marks.forEach(block -> mark(block, false));
        marks.forEach(block -> mark(block.getRelative(direction), true));
    }

    // ---- leaving ---------------------------------------------------------

    /** Broken, it gives back the waxed copper it stood in for. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        Block block = event.getBlock();
        if (!marked(block)) {
            return;
        }
        Material waxed = waxedOf(event.getBlockState().getType());
        if (waxed == null) {
            return;
        }
        Material twin = event.getBlockState().getType();
        Material waxedItem = Material.matchMaterial(waxed.name());
        for (Item dropped : event.getItems()) {
            ItemStack stack = dropped.getItemStack();
            if (stack.getType() == twin && waxedItem != null && waxedItem.isItem()) {
                stack.setType(waxedItem);
                dropped.setItemStack(stack);
            }
        }
    }

    /** The mark goes when the block does, a tick later so the drop has read it first. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        forgetSoon(List.of(event.getBlock(), event.getBlock().getRelative(BlockFace.UP),
                event.getBlock().getRelative(BlockFace.DOWN)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        forgetSoon(new ArrayList<>(event.blockList()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        forgetSoon(new ArrayList<>(event.blockList()));
    }

    private void forgetSoon(List<Block> candidates) {
        List<Block> marks = new ArrayList<>();
        for (Block block : candidates) {
            if (marked(block)) {
                marks.add(block);
            }
        }
        if (marks.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Block block : marks) {
                if (waxedOf(block.getType()) == null) {
                    mark(block, false);
                }
            }
        });
    }

    // ---- the marks -------------------------------------------------------

    /** A door's two halves are one door: both marked, or neither. */
    private void markWhole(Block block, boolean waxed) {
        mark(block, waxed);
        if (block.getBlockData() instanceof Bisected) {
            Bisected.Half half = ((Bisected) block.getBlockData()).getHalf();
            Block other = block.getRelative(half == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN);
            if (other.getType() == block.getType()) {
                mark(other, waxed);
            }
        }
    }

    /** Whether a block is marked waxed. */
    public boolean marked(Block block) {
        int[] marks = block.getChunk().getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        if (marks == null) {
            return false;
        }
        int packed = pack(block);
        for (int mark : marks) {
            if (mark == packed) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marks or unmarks one block, in its chunk's own persistent data, so the
     * mark is saved with the world and there is no file of ours to lose.
     */
    private void mark(Block block, boolean waxed) {
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        int[] marks = data.get(key, PersistentDataType.INTEGER_ARRAY);
        int packed = pack(block);
        Map<Integer, Boolean> set = new HashMap<>();
        if (marks != null) {
            for (int mark : marks) {
                set.put(mark, Boolean.TRUE);
            }
        }
        if (waxed) {
            set.put(packed, Boolean.TRUE);
        } else {
            set.remove(packed);
        }
        if (set.isEmpty()) {
            data.remove(key);
            return;
        }
        int[] written = set.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        data.set(key, PersistentDataType.INTEGER_ARRAY, written);
    }

    /** x and z within the chunk, and y above the world's floor, in one int. */
    private static int pack(Block block) {
        return (block.getX() & 15) | (block.getZ() & 15) << 4
                | (block.getY() - block.getWorld().getMinHeight()) << 8;
    }
}
