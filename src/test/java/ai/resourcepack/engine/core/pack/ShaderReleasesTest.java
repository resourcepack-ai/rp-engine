package ai.resourcepack.engine.core.pack;

import ai.resourcepack.common.shader.MergedShaders;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shader merge plans version by version from the shared library's table
 * of releases, and RP Engine builds packs from its own. A release one of them
 * knows and the other does not is a version merged for nobody, or a version
 * nobody merged for.
 */
class ShaderReleasesTest {

    @Test
    void theMergesReleasesAreRpEnginesReleases() {
        List<String> ours = PackFormats.ranges().stream().map(r -> r.format() + " " + r.label()).toList();
        List<String> merge = MergedShaders.RELEASES.stream().map(r -> r.format() + " " + r.label()).toList();
        assertEquals(ours, merge);
    }
}
