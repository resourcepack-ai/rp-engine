package ai.resourcepack.engine.core.registry;

import ai.resourcepack.engine.api.ClaimResult;
import ai.resourcepack.engine.api.ContentEntry;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistration;
import ai.resourcepack.engine.api.ContentRegistry;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Namespace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The registry. Internal; see {@link ContentRegistry} for the supported view.
 *
 * <p>Concurrent throughout because reads are promised safe from any thread and
 * loading happens off the main thread: a content pack is read from disk, and a
 * studio push arrives on a socket, neither of which is going to wait for a
 * tick. Writes go through a namespace handle, so the only contended structure
 * is the namespace table itself.
 */
public final class ContentRegistryImpl implements ContentRegistry, ContentRegistration {

    /**
     * Namespaces the client already means something by. Defining content in
     * one of these would write an {@code item_model} that resolves to vanilla
     * assets, which fails as a missing texture rather than as an error
     * anybody can trace back to here.
     */
    private static final Set<String> RESERVED = Set.of("minecraft", "realms");

    private final ConcurrentMap<String, Handle> namespaces = new ConcurrentHashMap<>();
    private final ConcurrentMap<ContentId, ContentEntry> entries = new ConcurrentHashMap<>();

    @Override
    public ClaimResult claim(String namespace, ContentSource source) {
        if (source == null || !ContentId.isValidNamespace(namespace)) {
            return ClaimResult.refused(ClaimResult.Reason.INVALID);
        }
        if (RESERVED.contains(namespace)) {
            return ClaimResult.refused(ClaimResult.Reason.RESERVED);
        }
        Handle fresh = new Handle(namespace, source);
        Handle existing = namespaces.putIfAbsent(namespace, fresh);
        if (existing != null) {
            return ClaimResult.alreadyClaimed(existing.source());
        }
        return ClaimResult.claimed(fresh);
    }

    /**
     * Drops everything, as a reload does before reading the folder again.
     *
     * <p>Releasing each namespace through its handle would be the same thing
     * with more ways to go wrong, and the host holding every handle purely so
     * it could release them is bookkeeping nobody would keep correct. Every
     * outstanding handle goes inactive, so a loader still mid-flight cannot
     * define into the registry it was reloaded out of.
     */
    public void clear() {
        for (Handle handle : namespaces.values()) {
            handle.active = false;
        }
        namespaces.clear();
        entries.clear();
    }

    @Override
    public Set<String> namespaces() {
        return Set.copyOf(namespaces.keySet());
    }

    @Override
    public Optional<ContentSource> sourceOf(String namespace) {
        if (namespace == null) {
            return Optional.empty();
        }
        Handle handle = namespaces.get(namespace);
        return handle == null ? Optional.empty() : Optional.of(handle.source());
    }

    @Override
    public Collection<ContentId> ids() {
        return sorted(entries.keySet());
    }

    @Override
    public Collection<ContentId> ids(ContentKind kind) {
        if (kind == null) {
            return List.of();
        }
        List<ContentId> matching = new ArrayList<>();
        for (ContentEntry entry : entries.values()) {
            if (entry.kind() == kind) {
                matching.add(entry.id());
            }
        }
        return sorted(matching);
    }

    @Override
    public Collection<ContentId> idsIn(String namespace) {
        if (namespace == null) {
            return List.of();
        }
        List<ContentId> matching = new ArrayList<>();
        for (ContentId id : entries.keySet()) {
            if (id.namespace().equals(namespace)) {
                matching.add(id);
            }
        }
        return sorted(matching);
    }

    @Override
    public Optional<ContentEntry> entry(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(id));
    }

    @Override
    public Optional<ContentEntry> entry(String id) {
        return ContentId.parse(id).flatMap(this::entry);
    }

    @Override
    public boolean contains(ContentId id) {
        return id != null && entries.containsKey(id);
    }

    @Override
    public boolean contains(ContentId id, ContentKind kind) {
        return entry(id).filter(entry -> entry.kind() == kind).isPresent();
    }

    private static Collection<ContentId> sorted(Collection<ContentId> ids) {
        List<ContentId> copy = new ArrayList<>(ids);
        Collections.sort(copy);
        return Collections.unmodifiableList(copy);
    }

    private final class Handle implements Namespace {

        private final String name;
        private final ContentSource source;
        private volatile boolean active = true;

        private Handle(String name, ContentSource source) {
            this.name = name;
            this.source = source;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ContentSource source() {
            return source;
        }

        @Override
        public Optional<ContentEntry> define(ContentKind kind, String path) {
            if (!active || kind == null) {
                return Optional.empty();
            }
            Optional<ContentId> id = ContentId.of(name, path);
            if (id.isEmpty()) {
                return Optional.empty();
            }
            ContentEntry entry = ContentEntry.of(id.get(), kind, source);
            // putIfAbsent, not put: a content pack that names the same id twice
            // keeps its first definition rather than silently taking whichever
            // file the directory walk happened to reach last.
            return entries.putIfAbsent(entry.id(), entry) == null
                    ? Optional.of(entry)
                    : Optional.empty();
        }

        @Override
        public void release() {
            active = false;
            namespaces.remove(name, this);
            entries.keySet().removeIf(id -> id.namespace().equals(name));
        }

        @Override
        public boolean active() {
            return active;
        }
    }
}
