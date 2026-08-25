package ai.resourcepack.engine.api;

import java.util.Objects;

/**
 * A registry entry together with the body it was defined by.
 *
 * <p>The registry is an index and stays one: it answers what exists and who
 * owns it. This is the other half, and it lives in the load report rather than
 * in the registry so that a consumer asking what the server holds does not
 * also pay for content it never asked about.
 *
 * <p>The body is untouched. The loader validates the id and the kind and
 * nothing else, because it does not know what an item or a rig needs, and a
 * loader that thinks it does is a loader that has to change every time a layer
 * gains a field.
 */
public final class ContentDefinition {

    private final ContentEntry entry;
    private final DefinitionNode body;
    private final String origin;

    private ContentDefinition(ContentEntry entry, DefinitionNode body, String origin) {
        this.entry = entry;
        this.body = body;
        this.origin = origin;
    }

    /**
     * @param origin the file it was written in, relative to the content root,
     *               so an error raised three layers later can still name it
     */
    public static ContentDefinition of(ContentEntry entry, DefinitionNode body, String origin) {
        return new ContentDefinition(
                Objects.requireNonNull(entry, "entry"),
                body == null ? DefinitionNode.empty() : body,
                origin == null ? "" : origin);
    }

    /** What was registered. */
    public ContentEntry entry() {
        return entry;
    }

    /** Shorthand for {@code entry().id()}. */
    public ContentId id() {
        return entry.id();
    }

    /** Shorthand for {@code entry().kind()}. */
    public ContentKind kind() {
        return entry.kind();
    }

    /** The definition body, for the layer that understands this kind. */
    public DefinitionNode body() {
        return body;
    }

    /** The file this came from, relative to the content root. */
    public String origin() {
        return origin;
    }

    @Override
    public String toString() {
        return entry + " from " + origin;
    }
}
