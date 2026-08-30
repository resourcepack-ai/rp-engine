package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ItemStats;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;

import java.util.Locale;

/**
 * 1.20.5 and up: everything an author can write actually happens.
 *
 * <p>Nothing here reports anything, because there is nothing to report.
 */
final class ModernItemComponents implements ItemComponents {

    @Override
    public void maxStackSize(ItemMeta meta, int size, Warner warner) {
        meta.setMaxStackSize(size);
    }

    @Override
    public void glint(ItemMeta meta, Warner warner) {
        meta.setEnchantmentGlintOverride(Boolean.TRUE);
    }

    @Override
    public void maxDamage(ItemMeta meta, int max, Warner warner) {
        ((Damageable) meta).setMaxDamage(max);
    }

    @Override
    public void food(ItemMeta meta, ItemStats.Food food, Warner warner) {
        FoodComponent component = meta.getFood();
        component.setNutrition(food.nutrition());
        component.setSaturation(food.saturation());
        component.setCanAlwaysEat(food.alwaysEdible());
        meta.setFood(component);
    }

    @Override
    public boolean modifier(ItemMeta meta, Attribute attribute, NamespacedKey id,
                            double amount, AttributeModifier.Operation operation, String slot) {
        EquipmentSlotGroup group = EquipmentSlotGroup.getByName(slot.toLowerCase(Locale.ROOT));
        if (group == null) {
            return false;
        }
        meta.addAttributeModifier(attribute,
                new AttributeModifier(id, amount, operation, group));
        return true;
    }
}
