package ai.resourcepack.engine.core.armor3d;

import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;

/**
 * 1.21.2 and up: a piece that can be put on by hand.
 *
 * <p>A piece is paper, and paper is not armour. Below 1.21.2 the command puts
 * it in the slot and a player can take it off but not put it back; here it
 * carries an {@code equippable} naming its slot and NO equipment asset — which
 * is the whole trick. With no asset the armour layer draws nothing, so the
 * body pieces are left to the displays, and the helmet falls through to the
 * layer that draws any head item as its model, which is what the pack built it
 * for. See {@link Armor3dSet}.
 */
final class PieceEquipping {

    private PieceEquipping() {
    }

    static void wearable(ItemMeta meta, EquipmentSlot slot) {
        EquippableComponent equippable = meta.getEquippable();
        equippable.setSlot(slot);
        meta.setEquippable(equippable);
    }
}
