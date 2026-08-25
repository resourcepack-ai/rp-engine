package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Objects;

/**
 * A named set of namespaces that ship together as one resource pack.
 *
 * <p>The second unit in the engine, and it answers a different question from a
 * namespace. A namespace says who owns an id. A bundle says what is sent to a
 * client and when, so it is the thing a player is actually holding.
 *
 * <p>A namespace may be in several bundles. That is the point: a shared base
 * pack in every bundle, plus whatever each one adds.
 */
public final class Bundle {

    private final String name;
    private final List<String> namespaces;

    private Bundle(String name, List<String> namespaces) {
        this.name = name;
        this.namespaces = namespaces;
    }

    /**
     * @param namespaces the namespaces it ships, which the builder expects
     *                   already sorted so that the zip it produces is the same
     *                   on every machine
     */
    public static Bundle of(String name, List<String> namespaces) {
        return new Bundle(
                Objects.requireNonNull(name, "name"),
                namespaces == null ? List.of() : List.copyOf(namespaces));
    }

    /** The bundle name, which follows the same rules as a namespace. */
    public String name() {
        return name;
    }

    /** The namespaces it ships, sorted. */
    public List<String> namespaces() {
        return namespaces;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Bundle)) {
            return false;
        }
        Bundle that = (Bundle) other;
        return name.equals(that.name) && namespaces.equals(that.namespaces);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespaces);
    }

    @Override
    public String toString() {
        return name + namespaces;
    }
}
