package ai.resourcepack.engine.api;

/**
 * How the item from {@link Models#itemFor} should be built.
 *
 * <p>Immutable and built by chaining; {@link #defaults()} is a fine argument
 * on its own.
 *
 * <pre>{@code
 * ItemStack reward = rpai.models()
 *     .itemFor("wizard_statue", ItemOptions.defaults().name("Wizard").amount(2))
 *     .orElseThrow();
 * }</pre>
 */
public final class ItemOptions {

    private static final ItemOptions DEFAULTS = new ItemOptions(null, 1f, 1, null);

    private final String animation;
    private final float scale;
    private final int amount;
    private final String name;

    private ItemOptions(String animation, float scale, int amount, String name) {
        this.animation = animation;
        this.scale = scale;
        this.amount = amount;
        this.name = name;
    }

    /** One item, natural size, no animation choice, named after the model. */
    public static ItemOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Which animation the placement made from this item should play. Carried
     * on the stack, so breaking the placement puts the choice back on the item
     * that drops.
     */
    public ItemOptions animation(String animation) {
        return new ItemOptions(animation, scale, amount, name);
    }

    /** Size multiplier for the placement made from this item (0.125 - 8). */
    public ItemOptions scale(float scale) {
        return new ItemOptions(animation, scale, amount, name);
    }

    /** How many. Clamped to at least 1. */
    public ItemOptions amount(int amount) {
        return new ItemOptions(animation, scale, amount, name);
    }

    /** The item's display name. The model id is used when this isn't set. */
    public ItemOptions name(String name) {
        return new ItemOptions(animation, scale, amount, name);
    }

    /** The chosen animation, or null. */
    public String animation() {
        return animation;
    }

    /** The size multiplier. 1 unless set. */
    public float scale() {
        return scale;
    }

    /** How many. 1 unless set. */
    public int amount() {
        return amount;
    }

    /** The display name, or null for the model id. */
    public String name() {
        return name;
    }
}
