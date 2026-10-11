package ai.resourcepack.engine.api;

import org.bukkit.inventory.EquipmentSlot;

/**
 * The four pieces of a 3D armour set, and the slot each is worn in.
 *
 * <p>This enum only grows; a switch over it wants a default branch.
 */
public enum ArmorPiece {

    /** Drawn by the game on the head, from the item's own model. */
    HELMET(EquipmentSlot.HEAD),

    /** The body and both arms. */
    CHESTPLATE(EquipmentSlot.CHEST),

    /** The waist and both legs. */
    LEGGINGS(EquipmentSlot.LEGS),

    /** Both feet. */
    BOOTS(EquipmentSlot.FEET);

    private final EquipmentSlot slot;

    ArmorPiece(EquipmentSlot slot) {
        this.slot = slot;
    }

    /** The slot it is worn in. Anywhere else it is a piece of paper. */
    public EquipmentSlot slot() {
        return slot;
    }
}
