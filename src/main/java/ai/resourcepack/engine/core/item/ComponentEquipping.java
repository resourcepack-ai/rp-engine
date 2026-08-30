package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Feature;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;

/**
 * 1.21.2 and up: any item can be worn.
 *
 * <p>Whether it is worn wearing the pack's own art is a second question one
 * release later, and it is the constructor argument. Between the two the item
 * is genuinely equippable and genuinely draws as vanilla, which is worth
 * saying out loud rather than treating as half-broken.
 */
final class ComponentEquipping implements Equipping {

    private final boolean withArt;

    ComponentEquipping(boolean withArt) {
        this.withArt = withArt;
    }

    @Override
    public boolean wearable(ItemMeta meta, ContentId id, String slot,
                            ItemComponents.Warner warner) {
        EquippableComponent equippable = meta.getEquippable();
        equippable.setSlot(slotOf(slot));
        if (withArt) {
            // Names the equipment asset the build wrote; see
            // ItemAssets.writeEquipment. Only set where the pack contains one,
            // because a component pointing at a file that is not in the zip is
            // an invisible body, which is worse than a vanilla-looking one.
            equippable.setModel(new NamespacedKey(id.namespace(), id.path()));
        } else {
            warner.unsupported("armor", Feature.ARMOUR_ART);
        }
        meta.setEquippable(equippable);
        return true;
    }

    private static EquipmentSlot slotOf(String slot) {
        switch (slot) {
            case "chest":
                return EquipmentSlot.CHEST;
            case "legs":
                return EquipmentSlot.LEGS;
            case "feet":
                return EquipmentSlot.FEET;
            default:
                return EquipmentSlot.HEAD;
        }
    }
}
