package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemAction;
import ai.resourcepack.engine.api.Items;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Custom blocks, in the world.
 *
 * <p>A custom cube is a vanilla block in a state nothing else uses (see
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
 *
 * <p><strong>A shaped block is the opposite case.</strong> It is a whole vanilla
 * block type - a stair, a door - and the game is meant to touch its state:
 * that is what makes a stair turn its corners and a door open. So for those
 * nothing is cancelled; this only gives back the right item, takes over
 * breaking, and keeps an axe from scraping the wax off what is underneath.
 */
public final class CustomBlocks implements Listener {

    private final Plugin plugin;
    private final Items items;
    private final BlockStates states;
    private final BlockBreaking breaking;
    private final Logger log;

    private volatile Map<ContentId, BlockInfo> blocks = Map.of();

    /** A block of ours standing somewhere, and which of its states it is in. */
    public record Placed(BlockInfo block, String state) {
    }

    /**
     * Every allocated state, by its identity in the world
     * ({@code note_block|note=3,powered=false}).
     *
     * <p>A map rather than a search because {@link #at} runs on every physics
     * update of every note block on the server, and a block with eight states
     * is eight entries.
     */
    private volatile Map<String, Placed> byIdentity = Map.of();

    /** Every vanilla block type taken over, and the block that took it. */
    private volatile Map<Material, BlockInfo> byMaterial = Map.of();

    public CustomBlocks(Plugin plugin, Items items, BlockStates states, Logger log) {
        this.plugin = plugin;
        this.items = items;
        this.states = states;
        this.breaking = new BlockBreaking(plugin);
        this.log = log;
    }

    /** What a block's own actions do when it is placed, clicked or mined. */
    private ai.resourcepack.engine.core.item.ActionRunner actions;

    public void actions(ai.resourcepack.engine.core.item.ActionRunner actions) {
        this.actions = actions;
    }

    /**
     * Runs the block's actions for {@code trigger}, with no stack: these are
     * about the block in the world, and a {@code take} must not eat whatever
     * the player happens to be holding.
     */
    private boolean act(Player player, BlockInfo block, ItemAction.Trigger trigger) {
        return actions != null && player != null && actions.run(player, block.id(), trigger, null);
    }

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, BlockInfo> loaded) {
        this.blocks = loaded == null ? Map.of() : Map.copyOf(loaded);
        reindex();
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
     * Allocates a state to every state of every cube, and a vanilla block to
     * every other shape, and says what did not fit.
     *
     * <p>At load rather than at first placement: an owner should find out that
     * their pack does not fit while they are looking at the console, not when
     * a player right-clicks.
     */
    public void allocate() {
        boolean allocated = false;
        for (BlockInfo block : blocks.values()) {
            if (block.shape() != BlockInfo.Shape.CUBE) {
                boolean had = states.existingShaped(block).isPresent();
                Optional<String> taken = states.shapedFor(block, CustomBlocks::exists);
                if (taken.isEmpty()) {
                    log.warning(shapedProblem(block));
                } else if (!had) {
                    allocated = true;
                }
                continue;
            }
            int missing = 0;
            for (String state : block.states()) {
                if (states.existing(block, state).isPresent()) {
                    continue;
                }
                if (states.numberFor(block, state).isEmpty()) {
                    missing++;
                } else {
                    allocated = true;
                }
            }
            if (missing > 0) {
                boolean none = states.existing(block).isEmpty();
                log.warning("No " + block.base().name().toLowerCase(Locale.ROOT) + " states left for "
                        + block.id() + (none ? ". It loads as an item but cannot be placed."
                        : ": " + missing + " of its " + block.states().size()
                        + " states have none, and it is placed in its first state instead."));
            }
        }
        if (allocated) {
            states.save(log);
        }
        reindex();
    }

    /** Why a shaped block has no vanilla block to be, said usefully. */
    private String shapedProblem(BlockInfo block) {
        String shape = block.shape().name().toLowerCase(Locale.ROOT);
        Optional<String> before = states.existingShaped(block);
        if (before.isPresent()) {
            return block.id() + " is a " + shape + " made of " + before.get()
                    + ", which this server version does not have. It loads as an item but cannot be placed.";
        }
        if (block.takes().isPresent()) {
            return block.id() + " asks for " + block.takes().get() + ", which is taken by another block or does "
                    + "not exist on this server. It loads as an item but cannot be placed.";
        }
        long available = BlockStates.pool(block.shape()).stream().filter(CustomBlocks::exists).count();
        if (available == 0) {
            return block.id() + " is a " + shape + ", and this server version has none of the blocks a "
                    + shape + " is made of (" + String.join(", ", BlockStates.pool(block.shape()))
                    + "; they arrived in 1.21). It loads as an item but cannot be placed.";
        }
        return "Every " + shape + " this server can make is taken (" + available + " of them), so " + block.id()
                + " loads as an item but cannot be placed. Name a vanilla " + shape + " to take with base:.";
    }

    /** Whether this server has a block called that. */
    static boolean exists(String name) {
        Material material = Material.matchMaterial(name);
        return material != null && material.isBlock();
    }

    /**
     * The material the item that places {@code block} is made of.
     *
     * <p>A cube's item is its base block, so vanilla decides where it may go;
     * a shaped block's item is the very block it took over, so vanilla places
     * it exactly as it places that block - turned, joined, doubled - and
     * there is nothing left to correct afterwards. One that has no block to be
     * is paper, so it cannot put down a block it is not.
     */
    public String itemMaterial(BlockInfo block) {
        if (block.shape() == BlockInfo.Shape.CUBE) {
            return block.base() == BlockInfo.Base.MUSHROOM_STEM ? "MUSHROOM_STEM" : "NOTE_BLOCK";
        }
        return states.existingShaped(block).filter(CustomBlocks::exists)
                .map(name -> name.toUpperCase(Locale.ROOT)).orElse("PAPER");
    }

    private void reindex() {
        Map<String, Placed> identities = new HashMap<>();
        Map<Material, BlockInfo> materials = new HashMap<>();
        for (BlockInfo block : blocks.values()) {
            if (block.shape() != BlockInfo.Shape.CUBE) {
                states.existingShaped(block).map(Material::matchMaterial)
                        .ifPresent(material -> materials.put(material, block));
                continue;
            }
            String base = baseName(block.base());
            for (String state : block.states()) {
                states.existing(block, state).ifPresent(number -> identities.put(
                        base + "|" + BlockStates.identityOf(block.base(), number), new Placed(block, state)));
            }
        }
        this.byIdentity = Map.copyOf(identities);
        this.byMaterial = Map.copyOf(materials);
    }

    /** Every vanilla block type taken over right now. */
    public java.util.Set<Material> takenMaterials() {
        return byMaterial.keySet();
    }

    // ---- placing ---------------------------------------------------------

    /**
     * Puts the right state down.
     *
     * <p>The item is a real block underneath, so vanilla has already decided
     * whether it may go there, played the sound and taken it off the stack.
     * All that is left is the state, which is why this is a listener rather
     * than a placement routine of our own - and for a shaped block not even
     * that, because the item IS the block it takes over.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack held = event.getItemInHand();
        Optional<BlockInfo> found = items.idOf(held).flatMap(this::info);
        if (found.isEmpty()) {
            return;
        }
        BlockInfo block = found.get();
        Block placed = event.getBlockPlaced();
        if (block.shape() == BlockInfo.Shape.CUBE) {
            String state = placementState(block, event);
            Optional<Integer> number = states.existing(block, state).or(() -> states.existing(block));
            if (number.isEmpty()) {
                return;
            }
            placed.setBlockData(dataFor(block, number.get()), false);
        }
        play(block, placed);
        act(event.getPlayer(), block, ItemAction.Trigger.PLACE);
    }

    /**
     * The state a cube is placed in: facing the player, along the face it was
     * put against, and every other property at its first value.
     */
    private static String placementState(BlockInfo block, BlockPlaceEvent event) {
        String state = block.defaultState();
        Player player = event.getPlayer();
        for (BlockInfo.Property property : block.properties()) {
            switch (property.kind()) {
                case FACING:
                    state = block.with(state, property.name(),
                            player.getFacing().getOppositeFace().name().toLowerCase(Locale.ROOT));
                    break;
                case FACING_ALL:
                    state = block.with(state, property.name(), facingAll(player));
                    break;
                case AXIS:
                    BlockFace against = event.getBlockPlaced().getFace(event.getBlockAgainst());
                    state = block.with(state, property.name(), axisOf(against));
                    break;
                default:
                    break;
            }
        }
        return state;
    }

    /** Where the player was looking from, as a dispenser decides it. */
    static String facingAll(Player player) {
        float pitch = player.getLocation().getPitch();
        if (pitch > 45f) {
            return "up";
        }
        if (pitch < -45f) {
            return "down";
        }
        return player.getFacing().getOppositeFace().name().toLowerCase(Locale.ROOT);
    }

    /** The axis a face lies across, as a log decides it. */
    static String axisOf(BlockFace face) {
        if (face == null) {
            return "y";
        }
        switch (face) {
            case EAST:
            case WEST:
                return "x";
            case NORTH:
            case SOUTH:
                return "z";
            default:
                return "y";
        }
    }

    /** The state a number means, as block data. */
    private BlockData dataFor(BlockInfo block, int number) {
        // The instrument is deliberately not stated: the game owns it, and
        // every value of it means the same block. See BlockStates.
        return plugin.getServer().createBlockData(
                "minecraft:" + baseName(block.base()) + "[" + BlockStates.identityOf(block.base(), number) + "]");
    }

    private static String baseName(BlockInfo.Base base) {
        return base == BlockInfo.Base.MUSHROOM_STEM ? "mushroom_stem" : "note_block";
    }

    /**
     * Puts a cube into another of its states, as a click or an action does.
     *
     * @return false when that state has no spare state of its own to be
     */
    public boolean setState(Block where, BlockInfo block, String state) {
        Optional<Integer> number = states.existing(block, state);
        if (number.isEmpty() || block.shape() != BlockInfo.Shape.CUBE) {
            return false;
        }
        where.setBlockData(dataFor(block, number.get()), false);
        return true;
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
     * The same goes for a shaped block, whose own drop would be the plain
     * copper stair underneath.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Optional<BlockInfo> found = at(event.getBlock());
        if (found.isEmpty()) {
            return;
        }
        BlockInfo block = found.get();
        event.setDropItems(false);
        breaking.stop(event.getPlayer());
        int count = dropCount(event.getBlock());
        // The other half of a door goes first, by us and without physics, so
        // the game never removes it on its own and drops the copper door
        // underneath.
        otherHalf(event.getBlock()).ifPresent(half -> half.setType(Material.AIR, false));
        play(block, event.getBlock());
        act(event.getPlayer(), block, ItemAction.Trigger.REMOVE);
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        // The wrong tool breaks it and gives nothing, which is what vanilla
        // does with stone and a shovel.
        if (!BlockBreaking.isCorrectTool(block,
                event.getPlayer().getInventory().getItemInMainHand())) {
            return;
        }
        drop(block, event.getBlock(), count);
    }

    private void drop(BlockInfo block, Block where, int count) {
        ContentId dropped = block.drop().orElse(block.id());
        items.create(dropped).ifPresent(stack -> {
            stack.setAmount(Math.max(1, count));
            where.getWorld().dropItemNaturally(where.getLocation().add(0.5, 0.5, 0.5), stack);
        });
    }

    /** Two for a double slab, which is two slabs; one for anything else. */
    private static int dropCount(Block block) {
        BlockData data = block.getBlockData();
        return data instanceof Slab && ((Slab) data).getType() == Slab.Type.DOUBLE ? 2 : 1;
    }

    /** The other half of a two-block-tall block of ours, if this is one. */
    private Optional<Block> otherHalf(Block block) {
        BlockData data = block.getBlockData();
        Optional<BlockInfo> found = at(block);
        if (!(data instanceof Bisected) || found.isEmpty() || found.get().shape() != BlockInfo.Shape.DOOR) {
            return Optional.empty();
        }
        Block other = ((Bisected) data).getHalf() == Bisected.Half.BOTTOM
                ? block.getRelative(BlockFace.UP) : block.getRelative(BlockFace.DOWN);
        return other.getType() == block.getType() ? Optional.of(other) : Optional.empty();
    }

    /**
     * An explosion takes ours like anything else, but gives back ours.
     *
     * <p>Left to the game, a creeper turns a ruby ore into a note block item and
     * a custom door into a copper one. Each of ours is taken out of the
     * explosion's list, removed here and dropped at the explosion's own odds.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explode(event.blockList(), event.getYield());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explode(event.blockList(), event.getYield());
    }

    private void explode(List<Block> blocks, float yield) {
        for (Iterator<Block> each = blocks.iterator(); each.hasNext(); ) {
            Block block = each.next();
            Optional<BlockInfo> found = at(block);
            if (found.isEmpty()) {
                continue;
            }
            each.remove();
            if (found.get().shape() == BlockInfo.Shape.DOOR
                    && block.getBlockData() instanceof Bisected
                    && ((Bisected) block.getBlockData()).getHalf() == Bisected.Half.TOP) {
                // The bottom half drops the door; the top only goes with it.
                otherHalf(block).ifPresent(half -> {
                    if (!blocks.contains(half)) {
                        half.setType(Material.AIR, false);
                    }
                });
                block.setType(Material.AIR, false);
                continue;
            }
            int count = dropCount(block);
            otherHalf(block).ifPresent(half -> half.setType(Material.AIR, false));
            block.setType(Material.AIR, false);
            if (ThreadLocalRandom.current().nextFloat() < yield) {
                drop(found.get(), block, count);
            }
        }
    }

    /**
     * A piston would break a door of ours and drop the copper one, so it does
     * not move one. Everything else of ours moves as the block underneath
     * does: a note block keeps its state when pushed, a stair is a stair.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesADoor(event.getBlocks(), event.getBlock().getRelative(event.getDirection()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesADoor(event.getBlocks(), null)) {
            event.setCancelled(true);
        }
    }

    private boolean movesADoor(List<Block> moved, Block head) {
        for (Block block : moved) {
            if (at(block).filter(found -> found.shape() == BlockInfo.Shape.DOOR).isPresent()) {
                return true;
            }
        }
        return head != null && at(head).filter(found -> found.shape() == BlockInfo.Shape.DOOR).isPresent();
    }

    /** Which of ours is standing here, if any. */
    public Optional<BlockInfo> at(Block block) {
        return placedAt(block).map(Placed::block);
    }

    /** Which of ours is standing here, and in which of its states. */
    public Optional<Placed> placedAt(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        Material type = block.getType();
        BlockInfo.Base base = baseOf(type);
        if (base == null) {
            BlockInfo shaped = byMaterial.get(type);
            return shaped == null ? Optional.empty() : Optional.of(new Placed(shaped, ""));
        }
        String identity = BlockStates.identityOfData(base, block.getBlockData().getAsString());
        return Optional.ofNullable(byIdentity.get(baseName(base) + "|" + identity));
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
     * not have got. Cancelled only for our cubes, so vanilla note blocks still
     * work on the same server - and never for a shaped block, whose updates
     * are how a stair finds its corners.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (baseOf(event.getBlock().getType()) != null && at(event.getBlock()).isPresent()) {
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
     * is — the single most visible way this feature breaks — and turns the
     * block's own {@code click:} property instead, if it has one.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Optional<Placed> found = placedAt(event.getClickedBlock());
        if (found.isEmpty()) {
            return;
        }
        BlockInfo block = found.get().block();
        boolean cube = block.shape() == BlockInfo.Shape.CUBE;
        if (cube) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        } else if (event.getItem() != null && event.getItem().getType().name().endsWith("_AXE")) {
            // An axe scrapes the wax off copper, which would turn a custom
            // stair back into a plain one in front of the player.
            event.setCancelled(true);
            return;
        }
        // Once per click, not once per hand, and not when sneaking with
        // something in hand: that is how vanilla says "place against it"
        // rather than "use it", and a chest keeps the same rule.
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND
                || (event.getPlayer().isSneaking() && event.getItem() != null)) {
            return;
        }
        boolean used = act(event.getPlayer(), block, ItemAction.Trigger.INTERACT);
        if (!used && cube && block.cycle().isPresent()) {
            String next = block.next(found.get().state(), block.cycle().get());
            used = setState(event.getClickedBlock(), block, next);
            if (used) {
                event.getPlayer().swingMainHand();
            }
        }
        if (used) {
            // A cancel stops the held item being used on it too, so a block
            // that is a button does not also get a block placed against it.
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }
}
