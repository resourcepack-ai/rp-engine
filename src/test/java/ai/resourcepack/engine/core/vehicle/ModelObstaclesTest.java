package ai.resourcepack.engine.core.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic that decides whether a vehicle is inside a placed model, and
 * what it is standing on.
 *
 * <p>Server free, which is the point of splitting the box out of the entity
 * scan: what fails here fails silently in game — a car through a fence looks
 * exactly like a car in front of a fence right up until it doesn't stop.
 *
 * <p>A one-block piece placed in a block space is the case every one of these
 * uses: an Interaction is anchored at its FEET, so a 1x1 model in the block at
 * y=64 stands from 64 to 65 centred on the block's middle.
 */
class ModelObstaclesTest {

    /** A one-block piece in the block at (10, 64, 20). */
    private static ModelObstacles chair() {
        return ModelObstacles.of(10.5, 64, 20.5, 1, 1);
    }

    @Test
    void nothingIsInsideAnEmptySet() {
        assertTrue(ModelObstacles.NONE.isEmpty());
        assertFalse(ModelObstacles.NONE.solidAt(10.5, 64.5, 20.5));
        assertTrue(Double.isNaN(ModelObstacles.NONE.topAt(10.5, 20.5, 0, 256)));
    }

    @Test
    void aPointInsideThePieceIsBlocked() {
        assertTrue(chair().solidAt(10.5, 64.5, 20.5));
    }

    /**
     * The anchor is the FEET. A box measured from the entity's centre instead
     * would sit half a block into the floor and half a block short at the top,
     * which is a fence you can drive over the top of.
     */
    @Test
    void thePieceStandsOnTheBlockFloorRatherThanAroundItsMiddle() {
        assertTrue(chair().solidAt(10.5, 64.01, 20.5));
        assertFalse(chair().solidAt(10.5, 63.9, 20.5));
        assertTrue(chair().solidAt(10.5, 64.99, 20.5));
    }

    /**
     * Half open at the top, exactly like a block's collision box. Closed, a
     * vehicle that landed on the piece would read as inside it — and a vehicle
     * inside something is allowed to keep moving (see
     * {@link VehiclePhysics#resolve}), so it would drive on through the wall it
     * had just parked on.
     */
    @Test
    void restingOnThePieceIsNotBeingInsideIt() {
        assertFalse(chair().solidAt(10.5, 65, 20.5));
    }

    @Test
    void aPointBesideThePieceIsClear() {
        assertFalse(chair().solidAt(11.6, 64.5, 20.5));
        assertFalse(chair().solidAt(10.5, 64.5, 21.6));
    }

    /** The width is the depth too — an Interaction has one horizontal size. */
    @Test
    void aWidePieceCoversItsWholeFootprint() {
        ModelObstacles table = ModelObstacles.of(10.5, 64, 20.5, 3, 1);

        assertTrue(table.solidAt(11.9, 64.5, 21.9));
        assertFalse(table.solidAt(12.1, 64.5, 20.5));
    }

    @Test
    void theTopIsWhatAVehicleRestsOn() {
        assertEquals(65, chair().topAt(10.5, 20.5, 60, 70));
    }

    /**
     * The band is what separates the three questions asked of this: resting on
     * something below, climbing onto something above, and reading a wheel.
     * Without it a vehicle would be lifted onto whatever it drove into.
     */
    @Test
    void aTopOutsideTheBandIsNotThere() {
        assertTrue(Double.isNaN(chair().topAt(10.5, 20.5, 60, 64)));
        assertTrue(Double.isNaN(chair().topAt(10.5, 20.5, 66, 70)));
    }

    @Test
    void thereIsNoTopBesideThePiece() {
        assertTrue(Double.isNaN(chair().topAt(12, 20.5, 60, 70)));
    }

    /**
     * A vehicle with a wheel on a kerb sits on the kerb. The same rule the
     * block surfaces follow, and the two are read into one answer, so a
     * disagreement here would be a vehicle held up by ground it is not on.
     */
    @Test
    void theHighestOfSeveralWins() {
        ModelObstacles stack = ModelObstacles.of(10.5, 64, 20.5, 2, 0.5);
        ModelObstacles taller = ModelObstacles.of(10.5, 64, 20.5, 2, 1.5);

        assertEquals(64.5, stack.topAt(10.5, 20.5, 60, 70));
        assertEquals(65.5, taller.topAt(10.5, 20.5, 60, 70));
    }
}
