package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.LoadReport;

/**
 * Something that writes generated files into a bundle being built.
 *
 * <p>The seam that keeps {@link PackBuilder} from knowing what an item is. The
 * builder routes files a human put on disk; a contributor writes the files
 * nobody should have to write by hand — an item's model JSON, a font provider,
 * a rig's geometry. One per kind, as each kind arrives.
 *
 * <p>Contributors run <strong>after</strong> the pack's own assets are copied,
 * which is what lets one ask whether a texture it is about to point at was
 * actually shipped.
 */
public interface PackContributor {

    /** Writes whatever this kind needs into {@code into}. */
    void contribute(Bundle bundle, LoadReport loaded, Contribution into);

    /** What a contributor is allowed to do to a bundle. */
    interface Contribution {

        /** Adds a generated file at {@code zipPath}. */
        void add(String zipPath, byte[] content);

        /** Whether something is already at {@code zipPath}. */
        boolean has(String zipPath);

        /** Reports a problem against a file in the content folder. */
        void warn(String origin, String where, String message);

        /** Reports something that was skipped. */
        void error(String origin, String where, String message);
    }
}
