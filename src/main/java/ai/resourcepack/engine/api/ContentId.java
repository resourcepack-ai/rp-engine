package ai.resourcepack.engine.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@code namespace:path} identifier for one piece of content.
 *
 * <p>This is deliberately the same shape and the same character rules as a
 * Minecraft resource location, because it <em>is</em> one: the id of an item
 * is written verbatim into the {@code minecraft:item_model} component of the
 * stack it produces, and the id of a model is the path its assets live at
 * inside the built pack. An id that is not a legal resource location would be
 * an id the client cannot be told about, so this type refuses to hold one.
 *
 * <p>Both halves accept {@code [a-z0-9_.-]}, and the path additionally accepts
 * {@code /}. Neither may be empty. Nothing here is case-folded for you: an
 * uppercase letter is a rejection rather than a silent lowercase, because the
 * two ids would otherwise be equal here and distinct to the client.
 */
public final class ContentId implements Comparable<ContentId> {

    private final String namespace;
    private final String path;

    private ContentId(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    /**
     * Parses {@code namespace:path}.
     *
     * @return the id, or empty if it is null, has no colon, has more than one,
     *         or either half breaks the character rules
     */
    public static Optional<ContentId> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        int colon = id.indexOf(':');
        if (colon < 0 || id.indexOf(':', colon + 1) >= 0) {
            return Optional.empty();
        }
        return of(id.substring(0, colon), id.substring(colon + 1));
    }

    /**
     * Builds an id from its two halves.
     *
     * @return the id, or empty if either half is null, empty, or contains a
     *         character the rules above do not allow
     */
    public static Optional<ContentId> of(String namespace, String path) {
        if (!isValidNamespace(namespace) || !isValidPath(path)) {
            return Optional.empty();
        }
        return Optional.of(new ContentId(namespace, path));
    }

    /** Whether {@code namespace} is usable as the left half of an id. */
    public static boolean isValidNamespace(String namespace) {
        return isValid(namespace, false);
    }

    /** Whether {@code path} is usable as the right half of an id. */
    public static boolean isValidPath(String path) {
        return isValid(path, true);
    }

    private static boolean isValid(String value, boolean allowSlash) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-'
                    || (allowSlash && c == '/');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** The left half: which content pack this came from. */
    public String namespace() {
        return namespace;
    }

    /** The right half: what it is called within that pack. */
    public String path() {
        return path;
    }

    /**
     * A copy of this id with the same path under a different namespace, or
     * empty if that namespace is not legal.
     *
     * <p>This is what re-homing a pack looks like, and it is the only
     * sanctioned way to do it: the two halves are never edited in place
     * because an id already written into a placed block's persistent data
     * cannot be edited at all.
     */
    public Optional<ContentId> inNamespace(String other) {
        return of(other, path);
    }

    @Override
    public int compareTo(ContentId other) {
        int byNamespace = namespace.compareTo(other.namespace);
        return byNamespace != 0 ? byNamespace : path.compareTo(other.path);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ContentId)) {
            return false;
        }
        ContentId that = (ContentId) other;
        return namespace.equals(that.namespace) && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, path);
    }

    /** {@code namespace:path}, which is exactly what the client is told. */
    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}
