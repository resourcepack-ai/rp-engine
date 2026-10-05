package ai.resourcepack.engine.core.model;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * A length of time as a pack writes one, in ticks.
 *
 * <p>{@code 10t} is ten ticks, {@code 10s} ten seconds, {@code 2m} two minutes,
 * and a bare number is ticks — the unit every other plugin a server migrates
 * from counts in, so an imported {@code delay: 200} means what it meant there.
 * Seconds and minutes may have a fraction ({@code 1.5s}); the result is rounded
 * to the nearest tick, because that is the clock anything scheduled runs on.
 */
public final class Durations {

    /** A day of game time is 24000 ticks; a week of them is more than any pack means. */
    private static final long MAX_TICKS = 20L * 60 * 60 * 24 * 7;

    private Durations() {
    }

    /**
     * @return empty for anything that is not a duration, a negative one, or
     *         one longer than a week
     */
    public static OptionalLong ticks(String written) {
        if (written == null) {
            return OptionalLong.empty();
        }
        String text = written.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return OptionalLong.empty();
        }
        double multiplier = 1;
        char unit = text.charAt(text.length() - 1);
        if (unit == 't' || unit == 's' || unit == 'm') {
            multiplier = unit == 's' ? 20 : unit == 'm' ? 20 * 60 : 1;
            text = text.substring(0, text.length() - 1).trim();
        }
        double amount;
        try {
            amount = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        if (!Double.isFinite(amount) || amount < 0) {
            return OptionalLong.empty();
        }
        if (multiplier == 1 && amount != Math.rint(amount)) {
            // Half a tick is not a thing that can be scheduled, and a bare
            // 1.5 is more likely seconds somebody forgot to mark than ticks.
            return OptionalLong.empty();
        }
        long ticks = Math.round(amount * multiplier);
        return ticks > MAX_TICKS ? OptionalLong.empty() : OptionalLong.of(ticks);
    }
}
