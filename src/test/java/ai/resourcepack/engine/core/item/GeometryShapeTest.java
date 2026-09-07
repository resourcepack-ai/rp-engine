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
 *
 * <p>Asserted through {@code contains} and {@code topAt} rather than by reading
 * the boxes back, because what matters is where the art is and not how it is
 * stored — and the first version of this passed a test on the numbers while
 * putting an invisible wall beside every handrail.
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

        assertEquals(2, shape.size());
        assertEquals(1.0, shape.height(), 1e-6);
    }

    /** Model units to the block, x and z off the centre column, y off the floor. */
    @Test
    void theFrameIsTheOneThePlacementUses() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[{"from":[0,0,0],"to":[16,16,16]}]}
                """);

        assertTrue(shape.contains(-0.49, 0.01, -0.49), "the low corner of the block");
        assertTrue(shape.contains(0.49, 0.99, 0.49), "the high corner");
        assertFalse(shape.contains(0, -0.01, 0), "nothing below the floor");
        assertFalse(shape.contains(0.51, 0.5, 0), "nothing outside the block");
        assertEquals(1.0, shape.topAt(0, 0), 1e-6);
    }

    /**
     * <strong>A turned element is not its enclosing box.</strong> The format
     * stores an unrotated cube plus an angle, and folding the two together —
     * which is what this used to do — gives a volume several times the art for
     * anything long and thin on the diagonal. A handrail then reads as an
     * invisible wall a foot away from itself, which is exactly what got
     * reported.
     *
     * <p>A 16x2 slab turned 45 degrees about the block centre: the middle is
     * solid, the far corners of the box round it are not.
     */
    @Test
    void aTurnedElementCollidesWhereTheArtIsAndNotWhereItIsNot() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[{"from":[0,7,7],"to":[16,9,9],
                  "rotation":{"origin":[8,8,8],"axis":"z","angle":45}}]}
                """);

        assertTrue(shape.contains(0, 0.5, 0), "the middle of the slab");
        assertTrue(shape.contains(0.2, 0.7, 0), "along the diagonal it now runs on");
        assertFalse(shape.contains(0.2, 0.3, 0), "across it — inside the enclosing box, not the art");
        assertFalse(shape.contains(-0.2, 0.7, 0), "and the other corner");
    }

    /**
     * The same element read as a SURFACE. A turned slab is a ramp, so its top
     * over a column rises with the column — which is what lets a vehicle ride
     * up one instead of being stopped by a box.
     */
    @Test
    void aTurnedElementIsARampRatherThanAStep() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[{"from":[0,7,7],"to":[16,9,9],
                  "rotation":{"origin":[8,8,8],"axis":"z","angle":45}}]}
                """);

        double low = shape.topAt(-0.25, 0);
        double middle = shape.topAt(0, 0);
        double high = shape.topAt(0.25, 0);

        assertTrue(low < middle, "it climbs across the slab");
        assertTrue(middle < high, "and keeps climbing");
    }

    /** The axis it turns about is the one that does not move. */
    @Test
    void theTurnMovesTheOtherTwoAxes() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[{"from":[0,7,7],"to":[16,9,9],
                  "rotation":{"origin":[8,8,8],"axis":"z","angle":45}}]}
                """);

        // z is untouched: 7..9 model units is -1/16..1/16 of a block.
        assertTrue(shape.contains(0, 0.5, 0.05));
        assertFalse(shape.contains(0, 0.5, 0.2));
    }

    @Test
    void anUnturnedElementIsLeftExactlyAsWritten() {
        ModelShape shape = shapeOf("""
                {"textures":{},"elements":[{"from":[0,7,7],"to":[16,9,9],
                  "rotation":{"origin":[8,8,8],"axis":"z","angle":0}}]}
                """);

        assertEquals(9f / 16f, shape.topAt(0, 0), 1e-6);
        assertFalse(shape.contains(0, 0.7, 0), "nothing above a flat slab");
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

    /**
     * <strong>A column holds more than one surface, and the band has to pick
     * among them rather than after them.</strong> This is the staircase with a
     * handrail: the rail stands over the tread, so a reader that takes the
     * single highest top gets the rail — and a caller that then rejects the
     * rail for being too high to climb is left with nothing, and stops the
     * vehicle at a step it could have driven up.
     */
    @Test
    void aColumnReportsTheHighestSurfaceInsideTheBand() {
        ModelShape stepWithRail = shapeOf("""
                {"textures":{},"elements":[
                  {"from":[0,0,0],"to":[16,4,16]},
                  {"from":[0,20,0],"to":[16,24,16]}]}
                """);

        // Everything: the rail wins, as it should.
        assertEquals(24f / 16f, stepWithRail.topAt(0, 0), 1e-6);
        // A step's worth above the floor: the TREAD, not nothing.
        assertEquals(4f / 16f, stepWithRail.topAt(0, 0, 1e-6, 1.0), 1e-6);
        // And nothing at all where the model genuinely has none.
        assertTrue(Double.isNaN(stepWithRail.topAt(0, 0, 1.6, 2.0)));
    }

    /**
     * A staircase, which is the shape this was reopened for. Each tread is a
     * surface at its own height, and none of them is a wall.
     */
    @Test
    void aStaircaseIsASurfacePerTread() {
        ModelShape stairs = shapeOf("""
                {"textures":{},"elements":[
                  {"from":[0,0,0],"to":[16,4,4]},
                  {"from":[0,0,4],"to":[16,8,8]},
                  {"from":[0,0,8],"to":[16,12,12]},
                  {"from":[0,0,12],"to":[16,16,16]}]}
                """);

        assertEquals(4f / 16f, stairs.topAt(0, -0.375), 1e-6);
        assertEquals(8f / 16f, stairs.topAt(0, -0.125), 1e-6);
        assertEquals(12f / 16f, stairs.topAt(0, 0.125), 1e-6);
        assertEquals(1.0, stairs.topAt(0, 0.375), 1e-6);
    }
}
