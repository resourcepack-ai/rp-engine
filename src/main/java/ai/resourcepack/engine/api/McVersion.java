package ai.resourcepack.engine.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A Minecraft version, parsed into something comparable.
 *
 * <p>This exists because the engine spans a range of game versions rather than
 * targeting one, and almost every question about what it can do reduces to
 * "is the server at least X". Comparing version <em>strings</em> is the bug
 * that question invites: {@code "1.21.10"} sorts before {@code "1.21.9"}, and
 * a floor written as a string is a floor that is wrong exactly once, on the
 * release nobody tested against. So a version is a value here, never text.
 *
 * <p><b>Numeric, component-wise, and blind to what the components mean.</b>
 * That is deliberate. Mojang moved to a date-shaped scheme with 26.1, and a
 * parser that knew {@code 1.x} was the only real shape would have had to be
 * taught otherwise. This one already sorts 26.1 above 1.21.11 for the same
 * reason it sorts 1.21.11 above 1.21.9: it compares the first component
 * first, and 26 is greater than 1.
 *
 * <p>A missing component reads as zero, so {@code 1.21} and {@code 1.21.0}
 * are equal and both sort below {@code 1.21.1}. That matches how Mojang names
 * releases — the first drop of a line has no third component — and it means a
 * floor can be written at the granularity it actually has.
 */
public final class McVersion implements Comparable<McVersion> {

    /**
     * The oldest version the engine runs on.
     *
     * <p>1.19.4 is where display entities arrived, and a placed model is an
     * {@code ItemDisplay}. Below this there is no version of the feature to
     * degrade to — the entity does not exist — so this is a refusal rather
     * than a reduced experience. Every other floor in {@link Feature} is the
     * second kind.
     */
    public static final McVersion OLDEST_SUPPORTED = new McVersion(1, 19, 4);

    private final int major;
    private final int minor;
    private final int patch;

    private McVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /**
     * A version from its three components, for the constants that state a
     * floor. Callers with a string from the server want {@link #parse}.
     */
    public static McVersion of(int major, int minor, int patch) {
        return new McVersion(major, minor, patch);
    }

    /** A version with no patch component, e.g. {@code 1.21} or {@code 26.1}. */
    public static McVersion of(int major, int minor) {
        return new McVersion(major, minor, 0);
    }

    /**
     * Parses a version out of whatever the server hands us.
     *
     * <p>Tolerant on purpose about what follows the numbers.
     * {@code getBukkitVersion()} returns {@code "1.21.8-R0.1-SNAPSHOT"}, a
     * snapshot build reports things like {@code "1.21.9-pre2"}, and forks
     * append their own decoration. All of those are the same release as far
     * as any decision here goes, so parsing stops at the first character that
     * is not a digit or a dot and keeps what it has.
     *
     * <p>It is <em>not</em> tolerant about there being no leading number:
     * a string that starts with anything else is not a version, and guessing
     * one would put the engine into a mode nobody chose.
     *
     * @return the version, or empty if no leading numeric component was found
     */
    public static Optional<McVersion> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int end = 0;
        while (end < raw.length() && (Character.isDigit(raw.charAt(end)) || raw.charAt(end) == '.')) {
            end++;
        }
        String[] parts = raw.substring(0, end).split("[.]");
        int[] numbers = new int[3];
        int found = 0;
        for (String part : parts) {
            if (part.isEmpty() || found == 3) {
                continue;
            }
            try {
                numbers[found++] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                // Unreachable given the scan above, which admits only digits
                // and dots. Caught rather than trusted because a component
                // long enough to overflow an int would land here, and a
                // version that fails to parse is a better outcome than one
                // that parses to a number nobody wrote.
                return Optional.empty();
            }
        }
        if (found == 0) {
            return Optional.empty();
        }
        return Optional.of(new McVersion(numbers[0], numbers[1], numbers[2]));
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    /** Whether this version is {@code other} or anything released after it. */
    public boolean atLeast(McVersion other) {
        return compareTo(other) >= 0;
    }

    /** Whether this version is older than {@code other}. */
    public boolean below(McVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(McVersion other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof McVersion)) {
            return false;
        }
        McVersion that = (McVersion) other;
        return major == that.major && minor == that.minor && patch == that.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    /**
     * The version as Mojang writes it: a trailing zero patch is dropped,
     * because the release is called 1.21 and not 1.21.0, and this string ends
     * up in log lines and command output a server owner reads.
     */
    @Override
    public String toString() {
        return patch == 0 ? major + "." + minor : major + "." + minor + "." + patch;
    }
}
