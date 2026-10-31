package ai.resourcepack.engine.core.skill;

import java.util.Locale;
import java.util.Optional;

/**
 * A MythicMobs ranged value, as conditions and health modifiers write it:
 * {@code <5}, {@code >5}, {@code =5}, {@code 5}, {@code 2to10}, {@code 2-10},
 * each optionally a percentage ({@code <50%}, {@code 30%-50%}).
 *
 * <p>Free of Bukkit: it is arithmetic, and tested as such.
 */
final class Ranged {

    final double low;
    final double high;
    final boolean percent;
    /** Below or above, rather than a band: kept apart so a bare {@code <0.5} reads naturally. */
    final char op;

    private Ranged(double low, double high, boolean percent, char op) {
        this.low = low;
        this.high = high;
        this.percent = percent;
        this.op = op;
    }

    /** Whether {@code value} is in range. A percentage compares against {@code value} as 0 to 100. */
    boolean test(double value) {
        switch (op) {
            case '<':
                return value < high;
            case '>':
                return value > low;
            default:
                return value >= low && value <= high;
        }
    }

    static Optional<Ranged> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        boolean percent = text.indexOf('%') >= 0;
        text = text.replace("%", "");
        char first = text.charAt(0);
        try {
            if (first == '<' || first == '>') {
                String rest = text.substring(1);
                if (rest.startsWith("=")) {
                    rest = rest.substring(1);
                }
                double value = Double.parseDouble(rest);
                return Optional.of(first == '<' ? new Ranged(Double.NEGATIVE_INFINITY, value, percent, '<')
                        : new Ranged(value, Double.POSITIVE_INFINITY, percent, '>'));
            }
            if (first == '=') {
                text = text.substring(1);
            }
            int to = text.indexOf("to");
            int dash = text.indexOf('-', 1);
            if (to > 0 || dash > 0) {
                int cut = to > 0 ? to : dash;
                double a = Double.parseDouble(text.substring(0, cut));
                double b = Double.parseDouble(text.substring(cut + (to > 0 ? 2 : 1)));
                return Optional.of(new Ranged(Math.min(a, b), Math.max(a, b), percent, '='));
            }
            double value = Double.parseDouble(text);
            return Optional.of(new Ranged(value, value, percent, first == '=' ? '=' : '~'));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Whether it was written as an exact value with no operator, e.g. a bare {@code 50%}. */
    boolean bare() {
        return op == '~';
    }

    /**
     * A number argument, which may be a range to pick from: {@code 2to5} is
     * any of 2..5. Empty for something that is not a number.
     */
    static Optional<Double> pick(String raw, java.util.Random random) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim();
        int to = text.toLowerCase(Locale.ROOT).indexOf("to");
        try {
            if (to > 0) {
                double a = Double.parseDouble(text.substring(0, to).trim());
                double b = Double.parseDouble(text.substring(to + 2).trim());
                double lo = Math.min(a, b);
                double hi = Math.max(a, b);
                boolean whole = a == Math.rint(a) && b == Math.rint(b);
                if (whole) {
                    return Optional.of((double) (long) lo + random.nextInt((int) (hi - lo) + 1));
                }
                return Optional.of(lo + random.nextDouble() * (hi - lo));
            }
            return Optional.of(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
