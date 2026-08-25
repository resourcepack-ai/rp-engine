package ai.resourcepack.engine.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Everything a load produced: what was registered, what was skipped, and why.
 *
 * <p>A load never completes exceptionally. A malformed content file is an
 * ordinary Tuesday rather than a bug in whoever asked, so it comes back as a
 * report that says so. The host decides what to print and in what colours.
 *
 * <p>{@link #definitions()} holds only what actually registered. Anything a
 * diagnostic mentions at {@link Diagnostic.Severity#ERROR} is absent from it.
 */
public final class LoadReport {

    private final List<PackMeta> packs;
    private final List<ContentDefinition> definitions;
    private final List<Diagnostic> diagnostics;

    private LoadReport(List<PackMeta> packs,
                       List<ContentDefinition> definitions,
                       List<Diagnostic> diagnostics) {
        this.packs = packs;
        this.definitions = definitions;
        this.diagnostics = diagnostics;
    }

    /** An empty report, for a content folder that does not exist yet. */
    public static LoadReport empty() {
        return new LoadReport(List.of(), List.of(), List.of());
    }

    /** Builds a report. Engine internal; the host reads one rather than making one. */
    public static LoadReport of(List<PackMeta> packs,
                                List<ContentDefinition> definitions,
                                List<Diagnostic> diagnostics) {
        return new LoadReport(
                packs == null ? List.of() : List.copyOf(packs),
                definitions == null ? List.of() : List.copyOf(definitions),
                diagnostics == null ? List.of() : List.copyOf(diagnostics));
    }

    /** The packs that loaded, in the order they were found. */
    public List<PackMeta> packs() {
        return packs;
    }

    /** The pack that claimed {@code namespace}, if one did. */
    public Optional<PackMeta> pack(String namespace) {
        if (namespace == null) {
            return Optional.empty();
        }
        for (PackMeta pack : packs) {
            if (pack.namespace().equals(namespace)) {
                return Optional.of(pack);
            }
        }
        return Optional.empty();
    }

    /** Everything that registered. */
    public List<ContentDefinition> definitions() {
        return definitions;
    }

    /** Everything that registered of one kind. */
    public List<ContentDefinition> definitions(ContentKind kind) {
        if (kind == null) {
            return List.of();
        }
        List<ContentDefinition> matching = new ArrayList<>();
        for (ContentDefinition definition : definitions) {
            if (definition.kind() == kind) {
                matching.add(definition);
            }
        }
        return Collections.unmodifiableList(matching);
    }

    /** Everything that went wrong, errors and warnings together. */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** Just the diagnostics of one severity. */
    public List<Diagnostic> diagnostics(Diagnostic.Severity severity) {
        if (severity == null) {
            return List.of();
        }
        List<Diagnostic> matching = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == severity) {
                matching.add(diagnostic);
            }
        }
        return Collections.unmodifiableList(matching);
    }

    /**
     * Whether anything was skipped.
     *
     * <p>Not the same as "the load failed": a pack with one bad item still
     * loaded everything else, and a server with nine working packs and one
     * broken one should start.
     */
    public boolean hasErrors() {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == Diagnostic.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return packs.size() + " packs, " + definitions.size() + " definitions, "
                + diagnostics.size() + " diagnostics";
    }
}
