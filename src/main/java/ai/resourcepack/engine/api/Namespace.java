package ai.resourcepack.engine.api;

import java.util.Optional;

/**
 * A claimed namespace, and the only way to put anything into the registry.
 *
 * <p>Registration is handle-based rather than a {@code register(entry)} method
 * on the registry for one reason: a namespace has exactly one owner, and
 * holding the handle is what proves ownership. A loader that has claimed
 * {@code mypack} cannot define {@code otherpack:thing} by accident or on
 * purpose, so two content sources loading at the same time cannot corrupt
 * each other's half of the id space.
 *
 * <p>A handle is single-use in the sense that matters: once {@link #release()}
 * is called every subsequent {@code define} answers empty, and the namespace
 * is free for a reload to claim again.
 */
public interface Namespace {

    /** The claimed namespace. */
    String name();

    /** The source that claimed it. */
    ContentSource source();

    /**
     * Registers one piece of content.
     *
     * @return the entry, or empty if {@code path} is not a legal id path, if
     *         something is already registered under that id, or if this
     *         handle has been released
     */
    Optional<ContentEntry> define(ContentKind kind, String path);

    /**
     * Drops this namespace and everything defined in it.
     *
     * <p>Whole-namespace replacement is the only reload granularity there is.
     * Diffing two versions of a content pack and touching only what changed
     * sounds better and is how you get a registry that disagrees with the
     * built resource pack.
     */
    void release();

    /** Whether this handle still owns its namespace. */
    boolean active();
}
