package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.ItemStats;
import ai.resourcepack.engine.core.version.Compatibility;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The per-item settings that only exist as data components, and what happens
 * to them on a server without them.
 *
 * <p>Four of these are options an author writes in YAML — a stack size, a
 * durability, food values, a glint with no enchantment — and all four arrived
 * together in 1.20.5. On an older server they are not merely unavailable, they
 * are <em>silently</em> unavailable: the option parses, the item is created,
 * and nothing about it is different. That is the failure this whole layer
 * exists to convert into a sentence somebody reads.
 *
 * <p>The fifth, an attribute modifier, is the odd one. It is not absent below
 * 1.20.5, it is <em>different</em>: the modern constructor takes a
 * {@link NamespacedKey} and an {@code EquipmentSlotGroup}, the old one takes a
 * {@link java.util.UUID} and a plain {@code EquipmentSlot}, and both do the
 * job. So both arms implement it and neither reports anything.
 *
 * <p>Each arm is its own class because the modern one names types that do not
 * exist on an old server, and a class naming an absent type is a class not
 * worth loading there. See {@link ItemModelWiring} for the longer version of
 * that argument.
 */
public interface ItemComponents {

    /** Told what could not be done, once per item, in the author's terms. */
    interface Warner {
        void unsupported(String option, Feature needs);
    }

    /** How many of this item stack in one slot. */
    void maxStackSize(ItemMeta meta, int size, Warner warner);

    /** The enchanted shimmer, without an enchantment. */
    void glint(ItemMeta meta, Warner warner);

    /**
     * A durability that is not the base material's.
     *
     * <p>Whether the material can be damaged at all is the caller's check, not
     * this one's: that is a mistake in the pack rather than a version
     * difference, and it is worth a different sentence.
     */
    void maxDamage(ItemMeta meta, int max, Warner warner);

    /** Nutrition, saturation and whether it can always be eaten. */
    void food(ItemMeta meta, ItemStats.Food food, Warner warner);

    /**
     * One attribute modifier, by whichever route this server has.
     *
     * @param slot the slot name as the pack wrote it, e.g. {@code any},
     *             {@code mainhand}, {@code head}
     * @return whether the slot name was understood; a false is the pack's
     *         mistake, not the version's
     */
    boolean modifier(ItemMeta meta, Attribute attribute, NamespacedKey id,
                     double amount, AttributeModifier.Operation operation, String slot);

    /** The arm for this server. */
    static ItemComponents forServer(Compatibility compatibility) {
        return compatibility.has(Feature.ITEM_COMPONENTS)
                ? new ModernItemComponents()
                : new LegacyItemComponents();
    }
}
