package ai.resourcepack.engine.api;

import java.util.Objects;

/**
 * One registered piece of content: an id, what kind of thing it is, and where
 * it came from.
 *
 * <p>Deliberately thin. The registry is an index, not a store — it answers
 * what exists and who owns it, and the per-kind services hold the definitions
 * themselves. Widening this type to carry item definitions or model data
 * would make every consumer of the index pay for content it never asked
 * about, and would put the registry in the position of understanding formats
 * that arrive from three different sources.
 */
public final class ContentEntry {

    private final ContentId id;
    private final ContentKind kind;
    private final ContentSource source;

    private ContentEntry(ContentId id, ContentKind kind, ContentSource source) {
        this.id = id;
        this.kind = kind;
        this.source = source;
    }

    /**
     * Builds an entry.
     *
     * @throws NullPointerException if any argument is null — unlike the rest
     *         of the API this is a programming error rather than bad user
     *         input, because the caller is the loader, not a config file
     */
    public static ContentEntry of(ContentId id, ContentKind kind, ContentSource source) {
        return new ContentEntry(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(source, "source"));
    }

    /** The id this content is registered under. */
    public ContentId id() {
        return id;
    }

    /** What kind of thing it is. */
    public ContentKind kind() {
        return kind;
    }

    /** Which front door it arrived through. */
    public ContentSource source() {
        return source;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ContentEntry)) {
            return false;
        }
        ContentEntry that = (ContentEntry) other;
        return id.equals(that.id) && kind == that.kind && source == that.source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, source);
    }

    @Override
    public String toString() {
        return kind + " " + id + " (" + source + ')';
    }
}
