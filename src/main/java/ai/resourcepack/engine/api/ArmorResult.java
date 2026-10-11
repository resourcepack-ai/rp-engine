package ai.resourcepack.engine.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * What happened when a set was put on somebody.
 *
 * <p>A reason rather than a sentence, as {@link EmoteResult} is: the caller
 * writes the words, in its own palette and language.
 */
public final class ArmorResult {

    /**
     * Why it was or was not put on.
     *
     * <p>This enum only ever grows, so a switch over it wants a default branch.
     */
    public enum Reason {

        /** It is on. */
        WORN,

        /** No set by that id. {@link Armor#ids()} lists what there is. */
        UNKNOWN_SET,

        /**
         * This server cannot draw 3D armour: it needs Minecraft 1.21.4 or
         * newer, where art is named by string {@code custom_model_data}.
         * {@link Armor#available()} answers the same thing up front.
         */
        UNSUPPORTED
    }

    private final Reason reason;
    private final List<ItemStack> displaced;

    private ArmorResult(Reason reason, List<ItemStack> displaced) {
        this.reason = reason;
        this.displaced = displaced;
    }

    /** For the engine. */
    public static ArmorResult of(Reason reason, List<ItemStack> displaced) {
        return new ArmorResult(reason, displaced == null ? List.of() : List.copyOf(displaced));
    }

    public Reason reason() {
        return reason;
    }

    /** Whether the set is on. */
    public boolean worn() {
        return reason == Reason.WORN;
    }

    /**
     * Copies of what was in the slots before and has been handed back to the
     * player: into their inventory, or onto the ground at their feet when it
     * was full. Empty when nothing was displaced, and when the set was not
     * put on. 3D armour that was already on is replaced, not handed back, and
     * is not listed.
     */
    public List<ItemStack> displaced() {
        return displaced;
    }

    @Override
    public String toString() {
        return "ArmorResult[" + reason + ", displaced " + displaced.size() + "]";
    }
}
