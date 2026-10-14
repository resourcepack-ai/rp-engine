package ai.resourcepack.engine.core.object;

/**
 * How one placed object is told what to show: 24 bits, carried as the item's
 * {@code custom_model_data} colour 0, which the pack's item model applies as
 * the tint of every face of the marker. The object's shader reads the tint
 * back as data.
 *
 * <ul>
 *   <li>bits 0-11: the game tick the state began, modulo {@link #PERIOD}. The
 *   client's clock is the game tick modulo 24000, which the period divides, so
 *   this is all the shader needs to know how long ago that was.
 *   {@link #SETTLED} means long ago: the state's one-shot is over.</li>
 *   <li>bits 12-15: the state. 16-19: the state before it. 20-23: the seed.</li>
 * </ul>
 *
 * <p>The tint the pack declares as the default, white, decodes to "not driven",
 * so an object nobody drives draws as it always did. Studio's twin of this is
 * {@code worldControlColor}; change one, change the other.
 */
public final class ObjectControls {

    /** The stamp's modulus, in ticks: 200 seconds, and a divisor of the client's 24000. */
    public static final int PERIOD = 4000;

    /** The stamp that means "long ago". */
    public static final int SETTLED = 4000;

    /**
     * How long a stamp is trusted. The shader reads anything more than
     * {@code PERIOD - 100} ticks old as just begun (a stamp a moment ahead of
     * the client's clock), so a state is settled well before its stamp could
     * come round again.
     */
    public static final int SETTLE_AFTER = 3600;

    private ObjectControls() {
    }

    /**
     * The tint for a state entered at {@code startTick} (any game tick, or a
     * negative number for long ago).
     */
    public static int color(int state, int previous, long startTick, int seed) {
        int start = startTick < 0 ? SETTLED : (int) Math.floorMod(startTick, (long) PERIOD);
        return (nibble(seed) << 20) | (nibble(previous) << 16) | (nibble(state) << 12) | start;
    }

    private static int nibble(int value) {
        return Math.max(0, Math.min(15, value));
    }
}
