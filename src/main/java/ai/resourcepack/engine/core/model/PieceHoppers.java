package ai.resourcepack.engine.core.model;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hoppers beside a furnace or brewing stand that is not really one.
 *
 * <p>The game's hopper only knows containers it can find, and a placed model is
 * not one, so the machine does the moving itself at a hopper's own pace: a
 * hopper pointing into it puts one item in, and a hopper directly under it
 * takes one out, each through the slots the real block would offer that side.
 * A powered hopper does nothing, and each move is offered to other plugins as
 * the game's own move is.
 */
final class PieceHoppers {

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private PieceHoppers() {
    }

    static boolean isHopper(Block block) {
        return block.getType() == Material.HOPPER;
    }

    static List<Block> neighbours(Block block) {
        List<Block> out = new ArrayList<>(FACES.length);
        for (BlockFace face : FACES) {
            out.add(block.getRelative(face));
        }
        return out;
    }

    static boolean beside(Block block) {
        for (BlockFace face : FACES) {
            if (isHopper(block.getRelative(face))) {
                return true;
            }
        }
        return false;
    }

    /** One round of every hopper beside it. @return whether anything moved */
    static <S, L extends PieceMachines.Live<S>> boolean serve(L one, PieceMachines<S, L> machine) {
        Block anchor = one.anchor.block();
        boolean moved = false;
        for (BlockFace face : FACES) {
            Block block = anchor.getRelative(face);
            if (!isHopper(block) || !(block.getBlockData() instanceof org.bukkit.block.data.type.Hopper)) {
                continue;
            }
            org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) block.getBlockData();
            if (!data.isEnabled() || !(block.getState() instanceof Hopper)) {
                continue;
            }
            Inventory hopper = ((Hopper) block.getState()).getInventory();
            if (face == BlockFace.DOWN) {
                moved |= pull(one, machine, hopper);
            }
            if (block.getRelative(data.getFacing()).equals(anchor)) {
                moved |= push(one, machine, hopper, face == BlockFace.UP
                        ? PieceMachines.HopperSide.ABOVE : PieceMachines.HopperSide.SIDE);
            }
        }
        return moved;
    }

    private static <S, L extends PieceMachines.Live<S>> boolean push(L one, PieceMachines<S, L> machine,
                                                                     Inventory hopper, PieceMachines.HopperSide side) {
        for (int i = 0; i < hopper.getSize(); i++) {
            ItemStack stack = hopper.getItem(i);
            if (!PieceMachines.present(stack)) {
                continue;
            }
            for (int slot : machine.slotsFacing(side)) {
                if (!machine.canInsert(one, slot, stack) || !fits(one.inventory, slot, stack)) {
                    continue;
                }
                ItemStack moving = stack.clone();
                moving.setAmount(1);
                InventoryMoveItemEvent event = new InventoryMoveItemEvent(hopper, moving, one.inventory, true);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return false;
                }
                ItemStack there = one.inventory.getItem(slot);
                if (PieceMachines.present(there)) {
                    there.setAmount(there.getAmount() + 1);
                    one.inventory.setItem(slot, there);
                } else {
                    one.inventory.setItem(slot, event.getItem());
                }
                stack.setAmount(stack.getAmount() - 1);
                hopper.setItem(i, stack.getAmount() > 0 ? stack : null);
                return true;
            }
        }
        return false;
    }

    private static <S, L extends PieceMachines.Live<S>> boolean pull(L one, PieceMachines<S, L> machine,
                                                                     Inventory hopper) {
        for (int slot : machine.slotsFacing(PieceMachines.HopperSide.BELOW)) {
            ItemStack stack = one.inventory.getItem(slot);
            if (!PieceMachines.present(stack) || !machine.canExtract(one, slot, stack)) {
                continue;
            }
            ItemStack moving = stack.clone();
            moving.setAmount(1);
            InventoryMoveItemEvent event = new InventoryMoveItemEvent(one.inventory, moving, hopper, false);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return false;
            }
            Map<Integer, ItemStack> left = hopper.addItem(event.getItem());
            if (!left.isEmpty()) {
                continue;
            }
            stack.setAmount(stack.getAmount() - 1);
            one.inventory.setItem(slot, stack.getAmount() > 0 ? stack : null);
            return true;
        }
        return false;
    }

    private static boolean fits(Inventory inventory, int slot, ItemStack stack) {
        ItemStack there = inventory.getItem(slot);
        if (!PieceMachines.present(there)) {
            return true;
        }
        return there.isSimilar(stack) && there.getAmount() < Math.min(there.getMaxStackSize(), inventory.getMaxStackSize());
    }
}
