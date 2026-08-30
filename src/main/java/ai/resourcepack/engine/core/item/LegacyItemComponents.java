package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.ItemStats;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Below 1.20.5: four options say why they did nothing, and modifiers take the
 * old road.
 *
 * <p>The four are reported rather than dropped in silence, once per item, and
 * that is the entire reason this class is not simply four empty methods.
 */
final class LegacyItemComponents implements ItemComponents {

    @Override
    public void maxStackSize(ItemMeta meta, int size, Warner warner) {
        warner.unsupported("max-stack", Feature.ITEM_COMPONENTS);
    }

    @Override
    public void glint(ItemMeta meta, Warner warner) {
        // Deliberately not faked with a hidden enchantment. It is close, and
        // it is not the same thing: the enchantment is really on the item, so
        // it survives into an anvil, shows in a grindstone, and changes what
        // the item is worth. An option that quietly makes an item enchanted is
        // worse than one that says it did nothing.
        warner.unsupported("glow", Feature.ITEM_COMPONENTS);
    }

    @Override
    public void maxDamage(ItemMeta meta, int max, Warner warner) {
        warner.unsupported("durability", Feature.ITEM_COMPONENTS);
    }

    @Override
    public void food(ItemMeta meta, ItemStats.Food food, Warner warner) {
        warner.unsupported("food", Feature.ITEM_COMPONENTS);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean modifier(ItemMeta meta, Attribute attribute, NamespacedKey id,
                            double amount, AttributeModifier.Operation operation, String slot) {
        String written = slot.toLowerCase(Locale.ROOT);
        EquipmentSlot single = slotOf(written);
        if (single == null && !isEverywhere(written)) {
            return false;
        }
        // The old constructor is keyed by UUID rather than by name, and the
        // UUID has to be stable: two modifiers on one item must not collide,
        // and the same item given twice must not stack its own bonus. Derived
        // from the key the modern arm uses, so both arms agree on identity
        // without either needing to know about the other.
        UUID uuid = UUID.nameUUIDFromBytes(id.toString().getBytes(StandardCharsets.UTF_8));
        String name = id.getKey();
        meta.addAttributeModifier(attribute, single == null
                ? new AttributeModifier(uuid, name, amount, operation)
                : new AttributeModifier(uuid, name, amount, operation, single));
        return true;
    }

    /**
     * The slot groups that mean "wherever it is", which the old API expresses
     * by leaving the slot off entirely.
     *
     * <p>{@code armor} is in here and it is not exact — it means the four
     * armour slots, and this applies the modifier in the hand as well. The
     * alternative is refusing an option the pack legitimately wrote, and a
     * bonus that also applies while carrying the helmet is a smaller surprise
     * than a helmet with no bonus at all.
     */
    private static boolean isEverywhere(String slot) {
        return slot.equals("any") || slot.equals("armor") || slot.equals("armour");
    }

    private static EquipmentSlot slotOf(String slot) {
        switch (slot) {
            case "mainhand":
            case "hand":
                return EquipmentSlot.HAND;
            case "offhand":
                return EquipmentSlot.OFF_HAND;
            case "head":
                return EquipmentSlot.HEAD;
            case "chest":
                return EquipmentSlot.CHEST;
            case "legs":
                return EquipmentSlot.LEGS;
            case "feet":
                return EquipmentSlot.FEET;
            default:
                return null;
        }
    }
}
