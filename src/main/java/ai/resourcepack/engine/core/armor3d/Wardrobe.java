package ai.resourcepack.engine.core.armor3d;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Putting a set on somebody and taking it off: the one code path
 * {@code /rp armor wear} and the API's {@code Armor} both run, so a set put on
 * by a plugin is put on exactly as one put on by command.
 *
 * <p>Main thread only, like everything that touches an inventory.
 */
public final class Wardrobe {

    private final Armor3dItems items;

    public Wardrobe(Armor3dItems items) {
        this.items = items;
    }

    /**
     * Puts every piece of {@code set} in its slot.
     *
     * <p>What was in a slot goes back into their inventory, or onto the ground
     * at their feet when it is full, rather than into the void: a diamond
     * chestplate swapped for a costume is still theirs. A 3D armour piece that
     * was there is simply replaced, since it can be made again at any time. A
     * slot the set has no piece for is left as it was.
     *
     * @return what was taken off and handed back, in slot order
     */
    public List<ItemStack> wear(Player player, Armor3dSet set) {
        List<ItemStack> displaced = new ArrayList<>();
        EntityEquipment equipment = player.getEquipment();
        for (Armor3dSet.Worn piece : set.pieces().values()) {
            ItemStack stack = items.piece(set, piece);
            if (equipment == null) {
                give(player, stack);
                continue;
            }
            ItemStack was = equipment.getItem(piece.piece().slot());
            if (was != null && was.getType() != Material.AIR && items.read(was).isEmpty()) {
                displaced.add(was.clone());
                give(player, was);
            }
            equipment.setItem(piece.piece().slot(), stack);
        }
        return displaced;
    }

    /** Hands every piece of {@code set} over, into the inventory. */
    public void give(Player player, Armor3dSet set) {
        for (Armor3dSet.Worn piece : set.pieces().values()) {
            give(player, items.piece(set, piece));
        }
    }

    /**
     * Takes every 3D armour piece off, leaving anything else they wear.
     *
     * @return the pieces taken off, which are no longer anywhere
     */
    public List<ItemStack> strip(Player player) {
        List<ItemStack> taken = new ArrayList<>();
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return taken;
        }
        for (Armor3dSet.Piece piece : Armor3dSet.Piece.values()) {
            ItemStack was = equipment.getItem(piece.slot());
            if (was != null && was.getType() != Material.AIR && items.read(was).isPresent()) {
                taken.add(was.clone());
                equipment.setItem(piece.slot(), null);
            }
        }
        return taken;
    }

    /** Which set each slot's 3D armour piece belongs to, for the slots that hold one in its own slot. */
    public Map<Armor3dSet.Piece, String> wearing(Player player) {
        Map<Armor3dSet.Piece, String> out = new EnumMap<>(Armor3dSet.Piece.class);
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return out;
        }
        for (Armor3dSet.Piece piece : Armor3dSet.Piece.values()) {
            ItemStack stack = equipment.getItem(piece.slot());
            items.read(stack)
                    .filter(worn -> worn.piece() == piece)
                    .ifPresent(worn -> out.put(piece, worn.setId()));
        }
        return out;
    }

    static void give(Player player, ItemStack stack) {
        for (ItemStack left : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }
}
