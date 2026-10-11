package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.McVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which {@code pack_format} number a game version wants in {@code pack.mcmeta}.
 *
 * <p>A resource pack states a format number, and a client that disagrees with
 * it shows the player a "made for a different version" warning before they
 * ever see the art. The number is not derivable from anything — it is a
 * counter Mojang increments whenever the pack layout changes, sometimes twice
 * in a release line and sometimes not for three — so it can only be a table,
 * and a table can only be wrong by being out of date.
 *
 * <p>Which is why this is looked up from the <b>running server's version</b>
 * rather than configured. It used to be a number in {@code config.yml} that a
 * server owner had to keep in step with their own game version by hand, and
 * the failure was silent and universal: every player saw the warning, and
 * nothing in the logs said why. The config key is still honoured as an
 * override for the case this table has not caught up with a release, and that
 * is the only reason it survives.
 *
 * <p><b>Ranges are explicit rather than derived from the next entry.</b> A
 * table of floors would silently absorb a version that does not exist yet
 * into the newest range it knows, which is precisely the case worth telling
 * somebody about instead.
 *
 * <p>This is the same table as Studio's Minecraft version list, kept by hand
 * in both. Add a release in one, add it in the other.
 */
public final class PackFormats {

    /**
     * One contiguous run of versions that share a format number.
     *
     * <p>{@code from} and {@code to} are both inclusive and both real
     * releases; {@code label} is how the range is written for a human, which
     * is not always derivable ("1.21 - 1.21.1" rather than "1.21.0 - 1.21.1").
     */
    public static final class Range {

        private final McVersion from;
        private final McVersion to;
        private final int format;
        private final String label;

        Range(McVersion from, McVersion to, int format, String label) {
            this.from = from;
            this.to = to;
            this.format = format;
            this.label = label;
        }

        public McVersion from() {
            return from;
        }

        public McVersion to() {
            return to;
        }

        public int format() {
            return format;
        }

        public String label() {
            return label;
        }

        /** Both ends inclusive. */
        public boolean contains(McVersion version) {
            return version != null && version.atLeast(from) && version.compareTo(to) <= 0;
        }
    }

    /**
     * Oldest first, so the list reads as history and a new release is appended
     * rather than inserted. Nothing depends on the order beyond that.
     */
    private static final List<Range> RANGES = build();

    private PackFormats() {
    }

    private static List<Range> build() {
        List<Range> ranges = new ArrayList<>();
        ranges.add(range(McVersion.of(1, 19, 4), McVersion.of(1, 19, 4), 13, "1.19.4"));
        ranges.add(range(McVersion.of(1, 20), McVersion.of(1, 20, 1), 15, "1.20 - 1.20.1"));
        ranges.add(range(McVersion.of(1, 20, 2), McVersion.of(1, 20, 2), 18, "1.20.2"));
        ranges.add(range(McVersion.of(1, 20, 3), McVersion.of(1, 20, 4), 22, "1.20.3 - 1.20.4"));
        ranges.add(range(McVersion.of(1, 20, 5), McVersion.of(1, 20, 6), 32, "1.20.5 - 1.20.6"));
        ranges.add(range(McVersion.of(1, 21), McVersion.of(1, 21, 1), 34, "1.21 - 1.21.1"));
        ranges.add(range(McVersion.of(1, 21, 2), McVersion.of(1, 21, 3), 42, "1.21.2 - 1.21.3"));
        ranges.add(range(McVersion.of(1, 21, 4), McVersion.of(1, 21, 4), 46, "1.21.4"));
        ranges.add(range(McVersion.of(1, 21, 5), McVersion.of(1, 21, 5), 55, "1.21.5"));
        ranges.add(range(McVersion.of(1, 21, 6), McVersion.of(1, 21, 6), 63, "1.21.6"));
        ranges.add(range(McVersion.of(1, 21, 7), McVersion.of(1, 21, 8), 64, "1.21.7 - 1.21.8"));
        ranges.add(range(McVersion.of(1, 21, 9), McVersion.of(1, 21, 10), 69, "1.21.9 - 1.21.10"));
        ranges.add(range(McVersion.of(1, 21, 11), McVersion.of(1, 21, 11), 75, "1.21.11"));
        ranges.add(range(McVersion.of(26, 1), McVersion.of(26, 1), 84, "26.1"));
        ranges.add(range(McVersion.of(26, 2), McVersion.of(26, 2), 88, "26.2"));
        return ranges;
    }

    private static Range range(McVersion from, McVersion to, int format, String label) {
        return new Range(from, to, format, label);
    }

    /** Every known range, oldest first. */
    public static List<Range> ranges() {
        return RANGES;
    }

    /**
     * The format for a version, or empty when the table does not cover it.
     *
     * <p>Empty means one of two things and the caller should say which: a
     * version older than the engine supports, or — far more likely, and the
     * case worth handling well — a release newer than this jar. A jar in the
     * wild outlives the table compiled into it, so "I do not know this
     * version" is a state the engine has to be able to be in without
     * pretending otherwise.
     */
    public static Optional<Integer> forVersion(McVersion version) {
        if (version == null) {
            return Optional.empty();
        }
        for (Range range : RANGES) {
            if (range.contains(version)) {
                return Optional.of(range.format());
            }
        }
        return Optional.empty();
    }

    /** The newest format the table knows, for the best guess at a newer release. */
    public static int newestKnown() {
        return RANGES.get(RANGES.size() - 1).format();
    }

    /** The newest version the table knows, for saying what the guess was based on. */
    public static McVersion newestKnownVersion() {
        return RANGES.get(RANGES.size() - 1).to();
    }
}
