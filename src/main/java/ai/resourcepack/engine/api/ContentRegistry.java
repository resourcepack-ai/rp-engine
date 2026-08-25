package ai.resourcepack.engine.api;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Everything this server knows about, from every source, under one id space.
 *
 * <p>Every read here is safe from any thread. This is the one part of the
 * engine a plugin may ask on an async task without ceremony: it answers what
 * the server <em>holds</em>, which never involves a player, an entity or a
 * world. The per-kind services are where the main-thread rule starts.
 *
 * <p>Nothing here returns null and nothing throws on a null argument. A
 * content id read out of somebody's config should answer empty, not a stack
 * trace on their console.
 */
public interface ContentRegistry {

    /**
     * Every namespace currently loaded, in no particular order.
     *
     * <p>A namespace is the unit of ownership: it is claimed by exactly one
     * source, and a reload replaces a namespace whole or not at all.
     */
    Set<String> namespaces();

    /** Which source owns {@code namespace}, or empty if nothing claims it. */
    Optional<ContentSource> sourceOf(String namespace);

    /** Every registered id, sorted, across all kinds and namespaces. */
    Collection<ContentId> ids();

    /** Every registered id of one kind, sorted. */
    Collection<ContentId> ids(ContentKind kind);

    /** Every registered id in one namespace, sorted. */
    Collection<ContentId> idsIn(String namespace);

    /** The entry registered under {@code id}, or empty if nothing is. */
    Optional<ContentEntry> entry(ContentId id);

    /**
     * The entry registered under {@code id}, parsed from its text form.
     *
     * <p>The convenience that matters: ids arrive from config files and
     * command arguments far more often than they arrive as objects, and
     * making every caller parse first produced the same three lines
     * everywhere.
     */
    Optional<ContentEntry> entry(String id);

    /** Whether anything is registered under {@code id}. */
    boolean contains(ContentId id);

    /** Whether anything of exactly {@code kind} is registered under {@code id}. */
    boolean contains(ContentId id, ContentKind kind);
}
