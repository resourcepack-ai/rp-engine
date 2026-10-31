package ai.resourcepack.engine.api;

import java.util.Objects;

/**
 * One line of a custom mob's loot: an item, how many, and how often.
 *
 * <p>The item is a string rather than a {@link ContentId} because it may be a
 * vanilla item ({@code minecraft:leather}) as easily as one of a pack's own,
 * and both are written the same way. It is resolved when the mob dies, so a
 * drop naming an item a later reload removes drops nothing rather than
 * failing a load.
 */
public final class EntityDrop {

    private final String item;
    private final int min;
    private final int max;
    private final double chance;

    private EntityDrop(String item, int min, int max, double chance) {
        this.item = item;
        this.min = min;
        this.max = max;
        this.chance = chance;
    }

    /**
     * Clamped rather than refused: {@code max} below {@code min} is {@code min},
     * counts are 0 to 64 and the chance 0 to 1.
     */
    public static EntityDrop of(String item, int min, int max, double chance) {
        int low = Math.max(0, Math.min(64, min));
        int high = Math.max(low, Math.min(64, max));
        double p = Double.isFinite(chance) ? Math.max(0, Math.min(1, chance)) : 1;
        return new EntityDrop(Objects.requireNonNull(item, "item").trim(), low, high, p);
    }

    /** {@code minecraft:leather}, {@code mypack:fang}, {@code studio:fang}. */
    public String item() {
        return item;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    /** 0 to 1: how often this line drops at all. */
    public double chance() {
        return chance;
    }

    @Override
    public String toString() {
        return item + " " + min + "-" + max + " @" + chance;
    }
}
