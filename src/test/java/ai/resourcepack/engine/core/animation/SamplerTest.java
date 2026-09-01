package ai.resourcepack.engine.core.animation;

import ai.resourcepack.engine.api.Keyframe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interpolation rules, and the only copy of them in the plugin.
 *
 * <p>These have to match the editor's {@code sampleChannel} and the old
 * plugin's sampler exactly. A difference shows up as animation that looks
 * subtly wrong in game and correct in the preview, which is close to
 * undebuggable — so the rules are pinned here rather than trusted.
 */
class SamplerTest {

    private static final float[] REST = {9f, 9f, 9f};

    private static Keyframe key(double time, float value, String interpolation) {
        return new Keyframe(time, new float[]{value, value, value}, interpolation);
    }

    private static float at(List<Keyframe> track, double time) {
        return Sampler.sample(track, time, REST)[0];
    }

    @Test
    void holdsBeforeTheFirstAndAfterTheLast() {
        List<Keyframe> track = List.of(key(1, 10f, null), key(2, 20f, null));

        // An animation does not extrapolate off the ends of what somebody drew.
        assertEquals(10f, at(track, 0));
        assertEquals(10f, at(track, 1));
        assertEquals(20f, at(track, 2));
        assertEquals(20f, at(track, 99));
    }

    @Test
    void linearIsTheDefault() {
        List<Keyframe> track = List.of(key(0, 0f, null), key(1, 10f, null));

        assertEquals(5f, at(track, 0.5));
        assertEquals(2.5f, at(track, 0.25));
    }

    @Test
    void stepHoldsTheLeftValueForTheWholeSegment() {
        List<Keyframe> track = List.of(key(0, 0f, "step"), key(1, 10f, null));

        assertEquals(0f, at(track, 0.5));
        assertEquals(0f, at(track, 0.99));
        assertEquals(10f, at(track, 1));
    }

    @Test
    void smoothAtEitherEndMakesTheSegmentSmooth() {
        List<Keyframe> left = List.of(key(0, 0f, null), key(1, 0f, "smooth"),
                key(2, 10f, null), key(3, 10f, null));
        List<Keyframe> right = List.of(key(0, 0f, null), key(1, 0f, null),
                key(2, 10f, "smooth"), key(3, 10f, null));

        // Sampled off the midpoint on purpose: with symmetric neighbours the
        // Catmull-Rom midpoint IS the linear one, so 1.5 proves nothing.
        assertTrue(at(left, 1.25) != 2.5f, "a smooth segment does not follow the straight line");
        assertEquals(at(left, 1.25), at(right, 1.25),
                "either end, not both: that is what the editor draws, so it is what the author saw");
    }

    @Test
    void aSmoothSegmentAtTheEdgeUsesItsOwnEndsAsNeighbours() {
        List<Keyframe> track = List.of(key(0, 0f, "smooth"), key(1, 10f, null));

        // No p0 and no p3 to reach for, so both double up. The curve degrades
        // to something sane rather than reading off the end of the list.
        assertEquals(5f, at(track, 0.5));
    }

    @Test
    void twoKeysAtOneInstantAreAJumpRatherThanADivisionByZero() {
        List<Keyframe> track = List.of(key(0, 0f, null), key(1, 10f, null), key(1, 20f, null),
                key(2, 30f, null));

        assertTrue(Float.isFinite(at(track, 1)));
    }

    @Test
    void anUnknownInterpolationReadsAsLinear() {
        // A word a newer studio invented must not fail the parse of a pack, so
        // it arrives as a string and anything unrecognised is the default.
        List<Keyframe> track = List.of(key(0, 0f, "bouncy"), key(1, 10f, null));

        assertEquals(5f, at(track, 0.5));
    }

    @Test
    void anEmptyChannelReadsAsItsRestPose() {
        // Zero would be wrong for scale, which collapses the model to nothing.
        assertArrayEquals(REST, Sampler.sample(List.of(), 0, REST));
        assertArrayEquals(REST, Sampler.sample((List<Keyframe>) null, 0, REST));
    }

    @Test
    void aMalformedKeyframeReadsAsItsRestPose() {
        assertArrayEquals(REST, Sampler.sample(List.of(new Keyframe(0, null, null)), 0, REST));
        assertArrayEquals(REST, Sampler.sample(
                List.of(key(0, 0f, null), new Keyframe(1, null, null)), 0.5, REST));
    }

    @Test
    void aChannelIsLookedUpByName() {
        Map<String, List<Keyframe>> animator = Map.of("rotation", List.of(key(0, 4f, null)));

        assertEquals(4f, Sampler.sample(animator, "rotation", 0, REST)[0]);
        assertArrayEquals(REST, Sampler.sample(animator, "position", 0, REST));
        assertArrayEquals(REST, Sampler.sample((Map<String, List<Keyframe>>) null, "rotation", 0, REST));
    }
}
