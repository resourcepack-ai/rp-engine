package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The vanilla numbers an item can carry.
 *
 * <p>An item in this engine is a vanilla item wearing a different model, which
 * is what makes the id scheme work — but it left a custom sword doing exactly
 * as much damage as the stick underneath it. These are the components that
 * make it a sword: what it hits for, what it can be enchanted with, how long
 * it lasts, whether you can eat it.
 *
 * <p><strong>Every one of them is a vanilla item component</strong>, not a
 * behaviour of ours. The game applies them, other plugins read them, and an
 * item that leaves this server in somebody's inventory keeps them. Nothing
 * here needs the plugin present to work, which is the test for whether
 * something belongs in this class at all.
 */
public final class ItemStats {

    /** One attribute modifier, as an author writes it. */
    public static final class Modifier {

        private final String attribute;
        private final double amount;
        private final String operation;
        private final String slot;

        Modifier(String attribute, double amount, String operation, String slot) {
            this.attribute = attribute;
            this.amount = amount;
            this.operation = operation;
            this.slot = slot;
        }

        /**
         * @param attribute the vanilla name, e.g. {@code attack_damage}
         * @param operation {@code add}, {@code multiply_base} or {@code multiply}
         * @param slot      where it applies: {@code hand}, {@code head}, {@code any}…
         */
        public static Modifier of(String attribute, double amount, String operation, String slot) {
            return new Modifier(attribute, amount,
                    operation == null || operation.isBlank() ? "add" : operation.trim(),
                    slot == null || slot.isBlank() ? "any" : slot.trim());
        }

        public String attribute() {
            return attribute;
        }

        public double amount() {
            return amount;
        }

        public String operation() {
            return operation;
        }

        public String slot() {
            return slot;
        }

        @Override
        public String toString() {
            return attribute + " " + amount + " " + operation + " (" + slot + ")";
        }
    }

    /** What eating it does. */
    public static final class Food {

        private final int nutrition;
        private final float saturation;
        private final boolean alwaysEdible;

        Food(int nutrition, float saturation, boolean alwaysEdible) {
            this.nutrition = nutrition;
            this.saturation = saturation;
            this.alwaysEdible = alwaysEdible;
        }

        public static Food of(int nutrition, float saturation, boolean alwaysEdible) {
            return new Food(Math.max(0, nutrition), Math.max(0f, saturation), alwaysEdible);
        }

        /** Half-drumsticks restored. */
        public int nutrition() {
            return nutrition;
        }

        public float saturation() {
            return saturation;
        }

        /** Whether it can be eaten on a full stomach, the way a golden apple can. */
        public boolean alwaysEdible() {
            return alwaysEdible;
        }
    }

    private final Map<String, Integer> enchantments;
    private final List<Modifier> modifiers;
    private final Integer maxDamage;
    private final Food food;

    private ItemStats(Map<String, Integer> enchantments, List<Modifier> modifiers,
                      Integer maxDamage, Food food) {
        this.enchantments = enchantments;
        this.modifiers = modifiers;
        this.maxDamage = maxDamage;
        this.food = food;
    }

    /** An item that carries none of this, which is nearly all of them. */
    public static ItemStats none() {
        return new ItemStats(Map.of(), List.of(), null, null);
    }

    public static ItemStats of(Map<String, Integer> enchantments, List<Modifier> modifiers,
                               Integer maxDamage, Food food) {
        return new ItemStats(
                enchantments == null ? Map.of() : Map.copyOf(enchantments),
                modifiers == null ? List.of() : List.copyOf(modifiers),
                maxDamage, food);
    }

    /** Whether anything at all is set, so the common case costs nothing. */
    public boolean isEmpty() {
        return enchantments.isEmpty() && modifiers.isEmpty() && maxDamage == null && food == null;
    }

    /** Enchantment name to level, e.g. {@code sharpness: 3}. */
    public Map<String, Integer> enchantments() {
        return enchantments;
    }

    /** Attribute modifiers, in the order they were written. */
    public List<Modifier> modifiers() {
        return modifiers;
    }

    /** How much durability it has, replacing the vanilla item's own. */
    public Optional<Integer> maxDamage() {
        return Optional.ofNullable(maxDamage);
    }

    /** What eating it does, if it can be eaten. */
    public Optional<Food> food() {
        return Optional.ofNullable(food);
    }
}
