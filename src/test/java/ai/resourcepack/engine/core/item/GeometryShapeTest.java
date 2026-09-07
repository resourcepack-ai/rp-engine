package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ModelShape;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a model's real boxes out of its file.
 *
 * <p>Beside {@link GeometryTest}, which is about the pack build, because this
 * is about a different consumer: what a vehicle drives into. Both read the same
 * file and only one of them cares where the elements ARE.
 */
class GeometryShapeTest {

    private static ModelShape shapeOf(String json) {
        return Geometry.read(json.getBytes(StandardCharsets.UTF_8), "mypack")
                .orElseThrow()
                .bounds()
                .shape();
    }

    @Test
    void everyElementBecomesABox() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[
                  {"from":[0,0,0],"to":[16,2,16]},
                  {"from":[7,2,7],"to":[9,16,9]}]}
                """);

        assertEquals(2, shape.boxes().length);
        assertEquals(1.0, shape.height(), 1e-6);
    }

    /** Model units to the block, x and z off the centre column, y off the floor. */
    @Test
    void theFrameIsTheOneThePlacementUses() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[{"from":[0,0,0],"to":[16,16,16]}]}
                """);

        float[] box = shape.boxes()[0];
        assertEquals(-0.5f, box[0], 1e-6);
        assertEquals(0f, box[1], 1e-6);
        assertEquals(-0.5f, box[2], 1e-6);
        assertEquals(0.5f, box[3], 1e-6);
        assertEquals(1f, box[4], 1e-6);
        assertEquals(0.5f, box[5], 1e-6);
    }

    /**
     * <strong>A rotated element is bigger than its own corners say.</strong>
     * The format stores an unrotated cube plus an angle, so reading
     * {@code from}/{@code to} straight gives a box the art hangs out of — and
     * the builder's octagonal wheels are made entirely of rotated slabs. Under
     * covering art is exactly the failure this feature was reopened for.
     *
     * <p>A 16x2 slab turned 45 degrees about the block centre spans
     * {@code 18/√2 ≈ 12.7} units on both axes, so the enclosing box is about
     * 6.36 either side of the middle rather than 8 and 1.
     */
    @Test
    void aTurnedElementIsMeasuredTurned() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[{"from":[0,7,7],"to":[16,9,9],
                  "rotation":{"origin":[8,8,8],"axis":"z","angle":45}}]}
                """);

        float[] box = shape.boxes()[0];
        // Without the fold this would be 1/16 of a block tall; with it, both
        // axes in the turning plane come out the same size.
        assertEquals(12.728f / 16f, box[3] - box[0], 1e-3);
        assertEquals(12.728f / 16f, box[4] - box[1], 1e-3);
        // The axis it turns about is untouched.
        assertEquals(2f / 16f, box[5] - box[2], 1e-3);
    }

    @Test
    void anUnturnedElementIsLeftExactlyAsWritten() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[{"from":[0,7,7],"to":[16,9,9],
                  "rotation":{"origin":[8,8,8],"axis":"z","angle":0}}]}
                """);

        assertEquals(2f / 16f, shape.boxes()[0][4] - shape.boxes()[0][1], 1e-6);
    }

    /**
     * A flat panel is a real and common element, and a box with no thickness
     * cannot be hit at all — so it gets the thinnest one that can be, rather
     * than being dropped and leaving a hole in the model.
     */
    @Test
    void aFlatPanelStillHasSomethingToHit() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[{"from":[0,0,8],"to":[16,16,8]}]}
                """);

        assertTrue(shape.boxes()[0][5] > shape.boxes()[0][2]);
        assertTrue(shape.contains(0, 0.5, 0));
    }

    @Test
    void aModelWithNoElementsHasNoShape() {
        assertTrue(shapeOf("{\"textures\":{},\"parent\":\"block/block\"}").isEmpty());
    }

    /** A column is only where the art is — the whole reason for boxes over a box. */
    @Test
    void topAtAnswersPerColumn() {
        ModelShape table = shapeOf("""
                {"textures":{},"elements":[
                  {"from":[0,12,0],"to":[16,14,16]},
                  {"from":[1,0,1],"to":[3,12,3]}]}
                """);

        assertEquals(14f / 16f, table.topAt(0, 0), 1e-6);
        assertFalse(table.contains(0, 0.5, 0), "the gap under the top");
    }
}
