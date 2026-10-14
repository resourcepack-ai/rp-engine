package ai.resourcepack.engine.core.object;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ShaderObjectInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Every 3D shader object the server knows: the pushes' and the content
 * folder's, with the sequences the content folder wrote for either.
 *
 * <p>One map, so the command, the API and the runtime never disagree about
 * what exists. Both sides are immutable maps swapped whole, so the merge is
 * cached on their identity, the way the 3D armour catalogue's is.
 */
public final class ObjectCatalogue {

    private static final String STUDIO_PREFIX = "studio:";

    private final Supplier<Map<ContentId, ShaderObjectInfo>> pushed;
    private volatile Map<ContentId, ShaderObjectInfo> authored = Map.of();
    private volatile Map<ContentId, Map<String, ObjectSequence>> sequences = Map.of();

    private Map<ContentId, ShaderObjectInfo> seenPushed;
    private Map<ContentId, ShaderObjectInfo> seenAuthored;
    private Map<ContentId, Map<String, ObjectSequence>> seenSequences;
    private Map<ContentId, ShaderObjectInfo> merged = Map.of();

    public ObjectCatalogue(Supplier<Map<ContentId, ShaderObjectInfo>> pushed) {
        this.pushed = pushed;
    }

    /** Replaces the content folder's objects and sequences, on every load. */
    public void replaceAuthored(ObjectDefinitions.Result result) {
        authored = result == null ? Map.of() : result.objects();
        sequences = result == null ? Map.of() : result.sequences();
    }

    /** Every object, authored first, each carrying the names of its sequences. */
    public synchronized Map<ContentId, ShaderObjectInfo> all() {
        Map<ContentId, ShaderObjectInfo> push = pushed.get();
        Map<ContentId, ShaderObjectInfo> own = authored;
        Map<ContentId, Map<String, ObjectSequence>> seqs = sequences;
        if (push != seenPushed || own != seenAuthored || seqs != seenSequences) {
            Map<ContentId, ShaderObjectInfo> both = new LinkedHashMap<>(own);
            if (push != null) {
                push.forEach(both::putIfAbsent);
            }
            both.replaceAll((id, info) -> {
                Map<String, ObjectSequence> mine = seqs.get(id);
                return mine == null ? info : info.withSequences(mine.keySet());
            });
            merged = Collections.unmodifiableMap(both);
            seenPushed = push;
            seenAuthored = own;
            seenSequences = seqs;
        }
        return merged;
    }

    public Optional<ShaderObjectInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(all().get(id));
    }

    /** The object somebody typed: an id as written, or a pushed one without its {@code studio:}. */
    public Optional<ShaderObjectInfo> find(String typed) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        Optional<ShaderObjectInfo> exact = ContentId.parse(typed).flatMap(this::info);
        if (exact.isPresent() || typed.contains(":")) {
            return exact;
        }
        return ContentId.parse(STUDIO_PREFIX + typed).flatMap(this::info);
    }

    public Optional<ObjectSequence> sequence(ContentId id, String name) {
        Map<String, ObjectSequence> mine = id == null || name == null ? null : sequences.get(id);
        return mine == null ? Optional.empty()
                : Optional.ofNullable(mine.get(name.toLowerCase(java.util.Locale.ROOT)));
    }
}
