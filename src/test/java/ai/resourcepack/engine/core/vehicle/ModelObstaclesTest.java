package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ModelShape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a placed model's art actually is, and therefore what a vehicle hits.
 *
 * <p>Server free, which is the point of splitting the boxes out of the entity
 * scan: everything checked here fails silently in game. A car through a fence
 * looks exactly like a car in front of a fence right up until it does not stop,
 * and a rotation with a sign the wrong way round is a bench you cannot drive
 * through from the side you can see.
 *
 * <p>The frame under test is the one the placement uses: a model is 16 units to
 * the block, x and z measured from the centre column (8), y from the block
 * floor. See {@link ModelShape}.
 */
class ModelObstaclesTest {

    /**
     * A bench: two blocks along the model's z, a quarter of a block across,
     * centred on the anchor.
     *
     * <p>Centred means -8 to 24 rather than -16 to 16, because a model's middle
     * is 8 and not 0 — which is the offset this whole frame turns on and the
     * first thing to check if these start failing.
     */
    private static ModelShape bench() {
        return ModelShape.ofModelUnits(List.of(new float[] {6, 0, -8, 10, 8, 24}));
    }

    /** A model filling exactly its own block. */
    private static ModelShape cube() {
        return ModelShape.ofModelUnits(List.of(new float[] {0, 0, 0, 16, 16, 16}));
    }

    /** A table: a top at 12–14 and four thin legs, so there is a gap under it. */
    private static ModelShape table() {
        return ModelShape.ofModelUnits(List.of(
                new float[] {0, 12, 0, 16, 14, 16},
                new float[] {1, 0, 1, 3, 12, 3},
                new float[] {13, 0, 1, 15, 12, 3},
                new float[] {1, 0, 13, 3, 12, 15},
                new float[] {13, 0, 13, 15, 12, 15}));
    }

    /** A step with a handrail standing over it, in one model. */
    private static ModelShape stepWithRail() {
        return ModelShape.ofModelUnits(List.of(
                new float[] {0, 0, 0, 16, 4, 16},
                new float[] {0, 20, 0, 16, 24, 16}));
    }

    /**
     * <strong>The band picks among a column's surfaces rather than filtering
     * the highest one afterwards.</strong> Filtering afterwards is how a
     * handrail hid the tread beneath it: the rail was the highest top, the
     * band rejected it as too high to climb, and the step-up was told there was
     * nothing there — so the vehicle stopped at a stair as though at a wall.
     *
     * <p>It also inverted with height, which is what made it odd to report:
     * the smaller the rise the narrower and lower the band, so the more
     * reliably something above it won.
     */
    @Test
    void aStepUnderARailIsStillSomethingToClimb() {
        ModelObstacles placed = ModelObstacles.of(stepWithRail(), 10.5, 64, 20.5, 0, 1);

        // A step-up's worth above the vehicle's base: the tread.
        assertEquals(64.25, placed.topAt(10.5, 20.5, 64 + 1e-6, 65 + 1e-6), 1e-9);
        // Reaching high enough for both, the rail wins — which is the same
        // rule the blocks follow, and not what was broken.
        assertEquals(65.5, placed.topAt(10.5, 20.5, 64 + 1e-6, 66), 1e-9);
    }

    /** The band is converted into the model's frame, so scale cannot skew it. */
    @Test
    void theBandFollowsTheModelsOwnScale() {
        ModelObstacles big = ModelObstacles.of(stepWithRail(), 10.5, 64, 20.5, 0, 2);

        // The tread is drawn twice as high, so a one-block band no longer
        // reaches it and a two-block one does.
        assertTrue(Double.isNaN(big.topAt(10.5, 20.5, 64 + 1e-6, 64.4)));
        assertEquals(64.5, big.topAt(10.5, 20.5, 64 + 1e-6, 65), 1e-9);
    }

    @Test
    void nothingIsInsideAnEmptySet() {
        assertTrue(ModelObstacles.NONE.isEmpty());
        assertFalse(ModelObstacles.NONE.solidAt(10.5, 64.5, 20.5));
        assertTrue(Double.isNaN(ModelObstacles.NONE.topAt(10.5, 20.5, 0, 256)));
    }

    /**
     * The anchor is the block FLOOR, not the block centre. A frame measured
     * from the middle would put the model half a block into the ground and
     * half a block short at the top — a fence you can drive over.
     */
    @Test
    void aCubeFillsExactlyTheBlockItWasPlacedIn() {
        ModelObstacles placed = ModelObstacles.of(cube(), 10.5, 64, 20.5, 0, 1);

        assertTrue(placed.solidAt(10.5, 64.01, 20.5));
        assertTrue(placed.solidAt(10.05, 64.5, 20.95));
        assertFalse(placed.solidAt(10.5, 63.9, 20.5));
        assertFalse(placed.solidAt(9.9, 64.5, 20.5));
    }

    /**
     * Half open at the top, exactly like a block's collision box. Closed, a
     * vehicle that landed on the piece would read as inside it — and a vehicle
     * inside something is allowed to keep moving (see
     * {@link VehiclePhysics#resolve}), so it would drive on through the thing
     * it had just parked on.
     */
    @Test
    void restingOnThePieceIsNotBeingInsideIt() {
        assertFalse(ModelObstacles.of(cube(), 10.5, 64, 20.5, 0, 1).solidAt(10.5, 65, 20.5));
    }

    /**
     * <strong>The whole point of the shape.</strong> A table is a top on four
     * legs, and a single box round it is a crate you cannot drive a go-kart
     * under. The gap between the legs has to be empty.
     */
    @Test
    void aTableIsHollowUnderneath() {
        ModelObstacles placed = ModelObstacles.of(table(), 10.5, 64, 20.5, 0, 1);

        assertTrue(placed.solidAt(10.5, 64.8, 20.5), "the top");
        assertTrue(placed.solidAt(10.15, 64.3, 20.15), "a leg");
        assertFalse(placed.solidAt(10.5, 64.3, 20.5), "the gap between the legs");
    }

    /** And it is a surface at the top over the top, not over the gap. */
    @Test
    void aTableIsSomethingToStandOnOnlyWhereItHasArt() {
        ModelObstacles placed = ModelObstacles.of(table(), 10.5, 64, 20.5, 0, 1);

        assertEquals(64.875, placed.topAt(10.5, 20.5, 60, 70), 1e-9);
        assertTrue(Double.isNaN(placed.topAt(13, 20.5, 60, 70)), "beside the piece");
    }

    /**
     * <strong>Yaw is applied to the QUERY, not to the boxes.</strong> Rotating
     * a box gives something that is not a box and has to be over-approximated;
     * rotating a point stays exact. This is the test that would catch the sign
     * being the wrong way round, which is invisible on anything square.
     *
     * <p>The bench runs along the model's z. Minecraft's yaw 0 faces +z, so
     * unturned it is long north-to-south; at yaw 90 it must be long east-to-west
     * (see {@code VehiclePhysics.seatOffset}, which owns this convention).
     */
    @Test
    void aBenchTurnsWithThePlacement() {
        ModelObstacles north = ModelObstacles.of(bench(), 10.5, 64, 20.5, 0, 1);
        assertTrue(north.solidAt(10.5, 64.2, 21.4), "along z, unturned");
        assertFalse(north.solidAt(11.4, 64.2, 20.5), "across x, unturned");

        ModelObstacles turned = ModelObstacles.of(bench(), 10.5, 64, 20.5, 90, 1);
        assertTrue(turned.solidAt(9.6, 64.2, 20.5), "along x once turned");
        assertFalse(turned.solidAt(10.5, 64.2, 21.4), "no longer along z");
    }

    /** A half turn is not a quarter turn: the long axis comes back. */
    @Test
    void aHalfTurnPutsTheLongAxisBack() {
        ModelObstacles turned = ModelObstacles.of(bench(), 10.5, 64, 20.5, 180, 1);

        assertTrue(turned.solidAt(10.5, 64.2, 21.4));
        assertFalse(turned.solidAt(11.4, 64.2, 20.5));
    }

    /**
     * Any angle, not just the four cardinals — an authored piece may be placed
     * {@code facing: free}. At 45 degrees the bench's long axis runs on the
     * diagonal, so a point off one corner is inside it and the same distance
     * along an axis is not.
     */
    @Test
    void anOddAngleIsExactRatherThanSnapped() {
        ModelObstacles turned = ModelObstacles.of(bench(), 10.5, 64, 20.5, 45, 1);

        assertTrue(turned.solidAt(10.5 - 0.6, 64.2, 20.5 + 0.6), "along the diagonal");
        assertFalse(turned.solidAt(10.5 + 0.6, 64.2, 20.5 + 0.6), "across it");
    }

    /**
     * A scaled model grows from its BASE, because that is what
     * {@code RigMath.scaledTransformation} does — it lifts by
     * {@code 0.5(s-1)} precisely so a big statue stands on the block instead of
     * sinking half of itself into it. Scaling about the centre here would put
     * the collision half a block below the art.
     */
    @Test
    void aScaledModelGrowsUpwardFromTheFloor() {
        ModelObstacles big = ModelObstacles.of(cube(), 10.5, 64, 20.5, 0, 3);

        assertEquals(67, big.topAt(10.5, 20.5, 60, 70), 1e-9);
        assertTrue(big.solidAt(10.5, 66.9, 20.5), "three blocks tall");
        assertTrue(big.solidAt(9.1, 64.5, 20.5), "three blocks wide");
        assertFalse(big.solidAt(10.5, 63.9, 20.5), "and none of it below the floor");
    }

    /**
     * The band is what separates the three questions asked of this: resting on
     * something below, climbing onto something above, and reading a wheel.
     * Without it a vehicle would be lifted onto whatever it drove into.
     */
    @Test
    void aTopOutsideTheBandIsNotThere() {
        ModelObstacles placed = ModelObstacles.of(cube(), 10.5, 64, 20.5, 0, 1);

        assertTrue(Double.isNaN(placed.topAt(10.5, 20.5, 60, 64)));
        assertTrue(Double.isNaN(placed.topAt(10.5, 20.5, 66, 70)));
        assertEquals(65, placed.topAt(10.5, 20.5, 60, 70), 1e-9);
    }

    /**
     * A model may legally run from -16 to 32, so it reaches well past its own
     * block. That is the case the first cut got wrong — the punch hitbox is one
     * block and the art was three — so it is worth its own test.
     */
    @Test
    void artOutsideTheAnchorBlockStillCollides() {
        ModelObstacles placed = ModelObstacles.of(bench(), 10.5, 64, 20.5, 0, 1);

        assertTrue(placed.solidAt(10.5, 64.2, 19.6), "a block behind the anchor");
        assertTrue(placed.solidAt(10.5, 64.2, 21.4), "and a block in front of it");
    }
}
