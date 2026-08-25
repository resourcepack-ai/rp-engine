package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack said about itself in {@code pack.yml}.
 *
 * <p>Only one field here is load-bearing: {@link #bundles()}, which is how a
 * namespace says what it ships in. The rest is presentation for a listing
 * command, and none of it is trusted for anything.
 *
 * <p>The namespace is <strong>not</strong> read from this file. It is the
 * folder name, so that the thing a server owner renames to move a pack is the
 * thing they can see.
 */
public final class PackMeta {

    /** The bundle a namespace ships in when {@code pack.yml} names none. */
    public static final String DEFAULT_BUNDLE = "main";

    private final String namespace;
    private final ContentSource source;
    private final String name;
    private final String author;
    private final String version;
    private final List<String> bundles;

    private PackMeta(String namespace, ContentSource source, String name,
                     String author, String version, List<String> bundles) {
        this.namespace = namespace;
        this.source = source;
        this.name = name;
        this.author = author;
        this.version = version;
        this.bundles = bundles;
    }

    /**
     * @param bundles the bundles it ships in; empty means {@link #DEFAULT_BUNDLE}
     */
    public static PackMeta of(String namespace, ContentSource source, String name,
                              String author, String version, List<String> bundles) {
        List<String> declared = bundles == null || bundles.isEmpty()
                ? List.of(DEFAULT_BUNDLE)
                : List.copyOf(bundles);
        return new PackMeta(
                Objects.requireNonNull(namespace, "namespace"),
                Objects.requireNonNull(source, "source"),
                name == null ? "" : name,
                author == null ? "" : author,
                version == null ? "" : version,
                declared);
    }

    /** The namespace, which is the folder name. */
    public String namespace() {
        return namespace;
    }

    /** Which front door it arrived through. */
    public ContentSource source() {
        return source;
    }

    /** Its display name, or empty if it did not give one. */
    public Optional<String> name() {
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /** Its author, or empty if it did not give one. */
    public Optional<String> author() {
        return author.isEmpty() ? Optional.empty() : Optional.of(author);
    }

    /** Its version, or empty if it did not give one. Never parsed. */
    public Optional<String> version() {
        return version.isEmpty() ? Optional.empty() : Optional.of(version);
    }

    /** The bundles it ships in. Never empty. */
    public List<String> bundles() {
        return bundles;
    }

    @Override
    public String toString() {
        return namespace + " " + bundles;
    }
}
