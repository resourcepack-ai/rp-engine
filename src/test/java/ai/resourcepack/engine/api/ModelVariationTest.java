package ai.resourcepack.engine.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roll itself, with the random number handed in so every edge is a fixed
 * input rather than a hope.
 */
class ModelVariationTest {

    @Test
    void nothingToVaryIsNone() {
        assertSame(ModelInfo.Variation.NONE, ModelInfo.Variation.of(0f, 90f, 1f, 1f));
        assertEquals(90f, ModelInfo.Variation.NONE.yaw(90f, 0.7));
        assertEquals(1f, ModelInfo.Variation.NONE.scale(0.7));
    }

    @Test
    void aFreeTurnSpansBothSides() {
        ModelInfo.Variation variation = ModelInfo.Variation.of(30f, 0f, 1f, 1f);

        assertEquals(60f, variation.yaw(90f, 0d), 1e-4);
        assertEquals(90f, variation.yaw(90f, 0.5), 1e-4);
        assertTrue(variation.yaw(90f, 0.999999) < 120f);
    }

    @Test
    void aSteppedTurnPicksWholeStepsEvenly() {
        ModelInfo.Variation variation = ModelInfo.Variation.of(30f, 15f, 1f, 1f);
        Map<Float, Integer> seen = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            seen.merge(variation.yaw(0f, i / 500d), 1, Integer::sum);
        }

        assertEquals(Map.of(-30f, 100, -15f, 100, 0f, 100, 15f, 100, 30f, 100), seen);
    }

    /**
     * Counted round the circle, so a half turn is one of four and not two of
     * five: -180 and 180 are the same way.
     */
    @Test
    void aFullCircleInQuartersIsFourEqualWays() {
        ModelInfo.Variation variation = ModelInfo.Variation.of(180f, 90f, 1f, 1f);
        Map<Float, Integer> seen = new HashMap<>();
        for (int i = 0; i < 400; i++) {
            seen.merge(variation.yaw(0f, i / 400d), 1, Integer::sum);
        }

        assertEquals(Map.of(0f, 100, 90f, 100, 180f, 100, 270f, 100), seen);
    }

    @Test
    void aStepWiderThanTheTurnNeverTurns() {
        ModelInfo.Variation variation = ModelInfo.Variation.of(45f, 90f, 1f, 1f);

        assertEquals(0f, variation.yaw(0f, 0d));
        assertEquals(0f, variation.yaw(0f, 0.999));
    }

    @Test
    void aSizeIsPickedBetweenTheTwoAndTheyAreSwappedIfBackwards() {
        ModelInfo.Variation variation = ModelInfo.Variation.of(0f, 0f, 1.5f, 0.5f);

        assertEquals(0.5f, variation.scaleMin());
        assertEquals(1.5f, variation.scaleMax());
        assertEquals(0.5f, variation.scale(0d), 1e-6);
        assertEquals(1f, variation.scale(0.5), 1e-6);
    }

    @Test
    void outOfRangeValuesAreClamped() {
        ModelInfo.Variation variation = ModelInfo.Variation.of(720f, -5f, 0f, 100f);

        assertEquals(180f, variation.rotation());
        assertEquals(0f, variation.rotationStep());
        assertEquals(ModelInfo.Variation.MIN_SCALE, variation.scaleMin());
        assertEquals(ModelInfo.Variation.MAX_SCALE, variation.scaleMax());
    }
}
