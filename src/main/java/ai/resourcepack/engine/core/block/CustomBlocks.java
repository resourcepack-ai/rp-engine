package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Custom blocks, in the world.
 *
 * <p>A custom block is a vanilla block in a state nothing else uses (see
 * {@link BlockInfo}), which means three jobs: put the right state down when
 * somebody places one, work out which block a state is when somebody breaks
 * one, and <strong>stop the game touching the state in between</strong>.
 *
 * <p>That last one is the whole difficulty, and it is not solved by cancelling
 * events. A note block recomputes its instrument from the block beneath it, and
 * <strong>cancelling {@code BlockPhysicsEvent} does not stop it</strong> on
 * 1.21.8: the recompute happens inside the block's own shape update, which no
 * event can refuse. The integration harness proved that by putting hay under
 * one and watching a harp become a banjo.
 *
 * <p>So the identity does not include the instrument — see {@link BlockStates}.
 * The events are still cancelled, for the things they DO stop: the note
 * playing, and a right-click cycling the note, which would change what the
 * block is.
 */
public final class CustomBlocks implements Listener {

    private final Plugin plugin;
    private final Items items;
    private final BlockStates states;
    private final BlockBreaking breaking;
    private final Logger log;

    private volatile Map<ContentId, BlockInfo> blocks = Map.of();

    public CustomBlocks(Plugin plugin, Items items, BlockStates states, Logger log) {
        this.plugin = plugin;
        this.items = items;
        this.states = states;
        this.breaking = new BlockBreaking(plugin);
        this.log = log;
    }

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, BlockInfo> loaded) {
        this.blocks = loaded == null ? Map.of() : Map.copyOf(loaded);
    }

    /** Every block id, in the order the pack declared them. */
    public Collection<ContentId> ids() {
        return blocks.keySet();
    }

    /** What the pack said a block is. */
    public Optional<BlockInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(blocks.get(id));
    }

    /**
     * Allocates a state to every block, and says how many are left.
     *
     * <p>At load rather than at first placement: an owner should find out that
     * their pack does not fit while they are looking at the console, not when
     * a player right-clicks.
     */
    public void allocate() {
        boolean allocated = false;
        for (BlockInfo block : blocks.values()) {
            if (states.existing(block).isEmpty()) {
                if (states.numberFor(block).isEmpty()) {
                    log.warning("No " + block.base().name().toLowerCase(java.util.Locale.ROOT)
                            + " states left for " + block.id()
                            + ". It loads as an item but cannot be placed.");
                } else {
                    allocated = true;
                }
            }
        }
        if (allocated) {
            states.save(log);
        }
    }

    // ---- placing ---------------------------------------------------------

    /**
     * Puts the right state down.
     *
     * <p>The item is a real block underneath, so vanilla has already decided
     * whether it may go there, played the sound and taken it off the stack.
     * All that is left is the state, which is why this is a listener rather
     * than a placement routine of our own.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack held = event.getItemInHand();
        Optional<BlockInfo> block = items.idOf(held).flatMap(this::info);
        if (block.isEmpty()) {
            return;
        }
        states.existing(block.get()).ifPresent(number -> {
            Block placed = event.getBlockPlaced();
            placed.setBlockData(dataFor(block.get(), number), false);
            play(block.get(), placed);
        });
    }

    /** The state a number means, as block data. */
    private BlockData dataFor(BlockInfo block, int number) {
        String base = block.base() == BlockInfo.Base.MUSHROOM_STEM ? "mushroom_stem" : "note_block";
        // The instrument is deliberately not stated: the game owns it, and
        // every value of it means the same block. See BlockStates.
        return plugin.getServer().createBlockData(
                "minecraft:" + base + "[" + BlockStates.identityOf(block.base(), number) + "]");
    }

    /**
     * The pack's own sound, over the base block's.
     *
     * <p>Over, not instead of: a block's sound group is a property of its type
     * and the client plays it. See {@link BlockInfo#sound()}.
     */
    private void play(BlockInfo block, Block where) {
        block.sound().ifPresent(sound -> where.getWorld().playSound(
                where.getLocation().add(0.5, 0.5, 0.5), sound, 1f, 1f));
    }

    // ---- breaking --------------------------------------------------------

    /**
     * Takes over breaking, so the pack's hardness and tool mean something.
     *
     * <p>Vanilla would break a note block in well under a second whatever the
     * pack said, because hardness belongs to a block's type. See
     * {@link BlockBreaking}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(org.bukkit.event.block.BlockDamageEvent event) {
        Optional<BlockInfo> block = at(event.getBlock());
        if (block.isEmpty()) {
            return;
        }
        event.setInstaBreak(false);
        event.setCancelled(true);
        breaking.start(event.getPlayer(), event.getBlock(), block.get());
    }

    /** They let go, or looked away. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageAbort(org.bukkit.event.block.BlockDamageAbortEvent event) {
        breaking.stop(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        breaking.stop(event.getPlayer());
    }

    /**
     * Gives back the custom block rather than a note block.
     *
     * <p>The drop is handled here rather than through a loot table because
     * there is no loot table for "a note block in state 412": to the game this
     * is an ordinary note block, and its own drop is what has to be replaced.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Optional<BlockInfo> block = at(event.getBlock());
        if (block.isEmpty()) {
            return;
        }
        event.setDropItems(false);
        breaking.stop(event.getPlayer());
        play(block.get(), event.getBlock());
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        // The wrong tool breaks it and gives nothing, which is what vanilla
        // does with stone and a shovel.
        if (!BlockBreaking.isCorrectTool(block.get(),
                event.getPlayer().getInventory().getItemInMainHand())) {
            return;
        }
        ContentId dropped = block.get().drop().orElse(block.get().id());
        items.create(dropped).ifPresent(stack ->
                event.getBlock().getWorld().dropItemNaturally(
                        event.getBlock().getLocation().add(0.5, 0.5, 0.5), stack));
    }

    /** Which of ours is standing here, if any. */
    public Optional<BlockInfo> at(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        BlockInfo.Base base = baseOf(block.getType());
        if (base == null) {
            return Optional.empty();
        }
        String identity = BlockStates.identityOfData(base, block.getBlockData().getAsString());
        for (BlockInfo candidate : blocks.values()) {
            if (candidate.base() != base) {
                continue;
            }
            Optional<Integer> number = states.existing(candidate);
            if (number.isPresent() && BlockStates.identityOf(base, number.get()).equals(identity)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static BlockInfo.Base baseOf(Material material) {
        if (material == Material.NOTE_BLOCK) {
            return BlockInfo.Base.NOTE_BLOCK;
        }
        if (material == Material.MUSHROOM_STEM) {
            return BlockInfo.Base.MUSHROOM_STEM;
        }
        return null;
    }

    // ---- keeping the game off it ----------------------------------------

    /**
     * Keeps the game's own updates off ours where it can.
     *
     * <p>This does NOT stop the instrument being recomputed — nothing does,
     * which is why the instrument is not part of a block's identity. It stops
     * the rest: the powered flag flicking with redstone, which IS part of the
     * identity, and a note block being pushed around by an update it should
     * not have got. Cancelled only for ours, so vanilla note blocks still work
     * on the same server.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (at(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    /** Ours are not instruments. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNote(NotePlayEvent event) {
        if (at(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    /**
     * Stops a right-click cycling the note, which would change which block it
     * is — the single most visible way this feature breaks.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && at(event.getClickedBlock()).isPresent()) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
    }
}
