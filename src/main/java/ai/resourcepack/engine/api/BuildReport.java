package ai.resourcepack.engine.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything a build produced: the packs that were written, and what went
 * wrong along the way.
 *
 * <p>Same shape and same promise as {@link LoadReport}. A build never
 * completes exceptionally, because a texture a definition names but nobody
 * shipped is a content mistake rather than a bug in whoever asked, and the
 * server owner needs a list of them rather than the first one as a stack
 * trace.
 */
public final class BuildReport {

    private final List<BuiltPack> packs;
    private final List<Diagnostic> diagnostics;

    private BuildReport(List<BuiltPack> packs, List<Diagnostic> diagnostics) {
        this.packs = packs;
        this.diagnostics = diagnostics;
    }

    /** Engine internal; a host reads one rather than making one. */
    public static BuildReport of(List<BuiltPack> packs, List<Diagnostic> diagnostics) {
        return new BuildReport(
                packs == null ? List.of() : List.copyOf(packs),
                diagnostics == null ? List.of() : List.copyOf(diagnostics));
    }

    /** The packs that were written, in bundle-name order. */
    public List<BuiltPack> packs() {
        return packs;
    }

    /** The built pack for one bundle, if it was built. */
    public Optional<BuiltPack> pack(String bundle) {
        if (bundle == null) {
            return Optional.empty();
        }
        for (BuiltPack pack : packs) {
            if (pack.bundle().equals(bundle)) {
                return Optional.of(pack);
            }
        }
        return Optional.empty();
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
        return List.copyOf(matching);
    }

    /** Whether anything was skipped. Not the same as the build failing. */
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
        return packs.size() + " packs, " + diagnostics.size() + " diagnostics";
    }
}
