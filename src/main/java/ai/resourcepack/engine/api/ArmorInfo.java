package ai.resourcepack.engine.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What a 3D armour set is: its name and which pieces it has.
 *
 * <p>Read-only, and a snapshot: a reload or a push can replace the set, so
 * keep the {@link ContentId} and ask again rather than keeping this.
 */
public final class ArmorInfo {

    private final ContentId id;
    private final String name;
    private final Set<ArmorPiece> pieces;
    private final boolean pushed;

    private ArmorInfo(ContentId id, String name, Set<ArmorPiece> pieces, boolean pushed) {
        this.id = id;
        this.name = name;
        this.pieces = pieces;
        this.pushed = pushed;
    }

    /** For the engine; a plugin reads these rather than making them. */
    public static ArmorInfo of(ContentId id, String name, Set<ArmorPiece> pieces, boolean pushed) {
        Objects.requireNonNull(id, "id");
        Set<ArmorPiece> copy = pieces == null || pieces.isEmpty()
                ? EnumSet.noneOf(ArmorPiece.class) : EnumSet.copyOf(pieces);
        return new ArmorInfo(id, name == null || name.isEmpty() ? id.toString() : name,
                Collections.unmodifiableSet(copy), pushed);
    }

    /**
     * The set's id: {@code namespace:path} for one from a content folder,
     * {@code studio:<id>} for one a Studio push brought.
     */
    public ContentId id() {
        return id;
    }

    /** What players are told it is called: "Santa Suit". The id when nobody named it. */
    public String name() {
        return name;
    }

    /** The pieces it has. A set may leave any of the four out. */
    public Set<ArmorPiece> pieces() {
        return pieces;
    }

    /**
     * Whether a Studio push brought it, rather than a content folder. A pushed
     * set is only drawn for players wearing that push's pack.
     */
    public boolean pushed() {
        return pushed;
    }

    @Override
    public String toString() {
        return "ArmorInfo[" + id + ", " + name + ", " + pieces + "]";
    }
}
