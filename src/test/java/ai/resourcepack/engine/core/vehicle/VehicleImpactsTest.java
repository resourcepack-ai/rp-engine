package ai.resourcepack.engine.core.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two vehicles meeting, on paper.
 *
 * <p>The whole of {@link VehicleImpacts} is arithmetic over ten numbers, which
 * is exactly the sort of thing that comes out plausible and backwards — a
 * vehicle that spins INTO whatever hit it, or a lorry that bounces off a moped.
 * These are the cases that tell those apart.
 *
 * <p>A car is 1.4 wide and 3.0 long throughout, so a pair nose to tail touch
 * with their centres 3.0 apart and every position below is quoted against that.
 * Yaw 0 faces +z; see {@link VehiclePhysics#seatOffset}.
 */
class VehicleImpactsTest {

    private static final double WIDTH = 1.4;
    private static final double LENGTH = 3.0;

    /** A car at {@code (x, z)} facing {@code yaw}, travelling {@code speed} along its own nose. */
    private static VehicleImpacts.Body car(double x, double z, double yaw, double speed, double mass) {
        double[] v = VehiclePhysics.worldVelocity(yaw, speed, 0);
        return new VehicleImpacts.Body(x, z, yaw, WIDTH, LENGTH, v[0], v[1], 0, mass);
    }

    private static VehicleImpacts.Body car(double x, double z, double yaw, double speed) {
        return car(x, z, yaw, speed, 10);
    }

    // --- the contact ---------------------------------------------------

    @Test
    void carsWellApartDoNotTouch() {
        assertNull(VehicleImpacts.contact(car(0, 0, 0, 0), car(0, 8, 0, 0)));
        assertNull(VehicleImpacts.contact(car(0, 0, 0, 0), car(4, 0, 0, 0)));
    }

    @Test
    void carsNoseToTailTouchAtExactlyTheirLength() {
        // A hair over their combined half-lengths is clear; a hair under is not.
        assertNull(VehicleImpacts.contact(car(0, 0, 0, 0), car(0, 3.05, 0, 0)));
        assertNotNull(VehicleImpacts.contact(car(0, 0, 0, 0), car(0, 2.95, 0, 0)));
    }

    @Test
    void sideBySideCarsClearEachOtherAtTheirWidth() {
        assertNull(VehicleImpacts.contact(car(0, 0, 0, 0), car(1.45, 0, 0, 0)));
        assertNotNull(VehicleImpacts.contact(car(0, 0, 0, 0), car(1.35, 0, 0, 0)));
    }

    @Test
    void aCrossedCarIsMeasuredOnItsOwnAxes() {
        // Broadside on: the second car is turned across the road, so what has
        // to clear is its WIDTH along z and its LENGTH across x. A test that
        // used one box's axes for both would have these the wrong way round.
        assertNotNull(VehicleImpacts.contact(car(0, 0, 0, 0), car(0, 2.0, 90, 0)));
        assertNull(VehicleImpacts.contact(car(0, 0, 0, 0), car(0, 2.3, 90, 0)));
        assertNotNull(VehicleImpacts.contact(car(0, 0, 0, 0), car(2.0, 0, 90, 0)));
    }

    @Test
    void theNormalIsTheShallowestWayOut() {
        // Nose to tail: the way out is along z, not sideways.
        VehicleImpacts.Contact c = VehicleImpacts.contact(car(0, 0, 0, 0), car(0, 2.8, 0, 0));
        assertNotNull(c);
        assertEquals(0, c.nx(), 1e-9);
        assertEquals(1, c.nz(), 1e-9);
        assertEquals(0.2, c.depth(), 1e-9);
    }

    @Test
    void aSquareRearEndTouchesOnTheCentreLine() {
        // The point every "take the deepest corner" shortcut gets wrong, and
        // the reason it matters is the test below this one.
        VehicleImpacts.Contact c = VehicleImpacts.contact(car(0, 0, 0, 10), car(0, 2.8, 0, 0));
        assertNotNull(c);
        assertEquals(0, c.px(), 1e-9);
        assertEquals(1.3, c.pz(), 1e-9);
    }

    @Test
    void aCornerClipTouchesAtTheCornerRatherThanTheCentre() {
        // Overlapping in x over [0.5, 0.7] only, so the contact is at 0.6.
        VehicleImpacts.Contact c = VehicleImpacts.contact(car(0, 0, 0, 10), car(1.2, 2.9, 0, 0));
        assertNotNull(c);
        assertEquals(0.6, c.px(), 1e-9);
        assertEquals(1.4, c.pz(), 1e-9);
    }

    // --- what each comes away with --------------------------------------

    @Test
    void aHeadOnStopsBothAndSendsThemBackABit() {
        VehicleImpacts.Body a = car(0, 0, 0, 10);
        VehicleImpacts.Body b = car(0, 2.8, 180, 10);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, VehicleImpacts.contact(a, b));

        // Each was doing 10 at the other; each ends up doing a little the other
        // way. Symmetric to the last digit, since they are the same car.
        assertEquals(-11.5, x.a().dvz(), 1e-6);
        assertEquals(11.5, x.b().dvz(), 1e-6);
        assertEquals(0, x.a().dvx(), 1e-9);
        // Square on the nose: nothing to turn either of them.
        assertEquals(0, x.a().dspin(), 1e-9);
        assertEquals(0, x.b().dspin(), 1e-9);
        assertEquals(20, x.closingSpeed(), 1e-9);
        assertTrue(x.normalImpulse() > 0);
    }

    @Test
    void aSquareRearEndPushesTheCarInFrontAndSlowsTheOneBehind() {
        VehicleImpacts.Body a = car(0, 0, 0, 12);
        VehicleImpacts.Body b = car(0, 2.8, 0, 2);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, VehicleImpacts.contact(a, b));

        assertTrue(x.a().dvz() < -3, "the car behind loses speed: " + x.a().dvz());
        assertTrue(x.b().dvz() > 3, "the car in front gains it: " + x.b().dvz());
        // Neither is turned. This is the assertion the contact point exists for.
        assertEquals(0, x.a().dspin(), 1e-9);
        assertEquals(0, x.b().dspin(), 1e-9);
    }

    @Test
    void aCornerClipSpinsBothAndTheDirectionsAreRight() {
        VehicleImpacts.Body a = car(0, 0, 0, 12);
        VehicleImpacts.Body b = car(1.2, 2.9, 0, 0);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, VehicleImpacts.contact(a, b));

        // a caught b on a's front-RIGHT (facing +z, +x is to the left), so a's
        // nose is knocked to its left — anticlockwise, which is a yaw rate
        // going down.
        assertTrue(x.a().dspin() < -1, "clipped car should swing: " + x.a().dspin());
        // b was shoved forward at its rear-right, which takes its nose the same
        // way round.
        assertTrue(x.b().dspin() < -1, "clipped car should swing: " + x.b().dspin());
    }

    @Test
    void momentumIsConserved() {
        // The one property that has to hold whatever the geometry: an impulse
        // between two bodies is equal and opposite, so nothing here can invent
        // motion. Checked on an ugly angled hit at unequal mass, which is where
        // an arithmetic slip would show.
        VehicleImpacts.Body a = car(0, 0, 0, 14, 30);
        VehicleImpacts.Body b = car(0.9, 2.0, 250, 6, 7);
        VehicleImpacts.Contact c = VehicleImpacts.contact(a, b);
        assertNotNull(c);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, c);

        assertTrue(x.a().dvx() != 0 && x.a().dvz() != 0, "and there is something to conserve");
        assertEquals(0, 30 * x.a().dvx() + 7 * x.b().dvx(), 1e-9);
        assertEquals(0, 30 * x.a().dvz() + 7 * x.b().dvz(), 1e-9);
    }

    @Test
    void theHeavierVehicleBarelyNotices() {
        VehicleImpacts.Body lorry = car(0, 0, 0, 10, 80);
        VehicleImpacts.Body moped = car(0, 2.8, 180, 10, 4);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(lorry, moped, VehicleImpacts.contact(lorry, moped));

        assertTrue(Math.abs(x.a().dvz()) < 2, "lorry should shrug it off: " + x.a().dvz());
        assertTrue(Math.abs(x.b().dvz()) > 20, "moped should be thrown: " + x.b().dvz());
        // And the same the other way about the shove that separates them.
        assertTrue(Math.abs(x.b().pushZ()) > 10 * Math.abs(x.a().pushZ()));
    }

    @Test
    void aStationaryCarIsPushedAndTheMovingOneIsSlowed() {
        VehicleImpacts.Body moving = car(0, 0, 0, 8);
        VehicleImpacts.Body parked = car(0, 2.8, 0, 0);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(moving, parked, VehicleImpacts.contact(moving, parked));

        assertTrue(x.b().dvz() > 0, "the parked car should move off");
        assertTrue(x.a().dvz() < 0, "the moving car should lose the speed it gave away");
        assertEquals(0, 10 * x.a().dvz() + 10 * x.b().dvz(), 1e-9);
    }

    // --- the failures this is written to avoid ---------------------------

    @Test
    void carsAlreadyMovingApartAreNotHitAgain() {
        // The classic double-hit: two vehicles still overlapping from last
        // tick's impact, already separating, given a second impulse for the
        // same collision — which is how one ends up shot across the map.
        VehicleImpacts.Body a = car(0, 0, 180, 4);
        VehicleImpacts.Body b = car(0, 2.8, 0, 4);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, VehicleImpacts.contact(a, b));

        assertEquals(0, x.a().dvx(), 1e-12);
        assertEquals(0, x.a().dvz(), 1e-12);
        assertEquals(0, x.b().dvz(), 1e-12);
        assertEquals(0, x.a().dspin(), 1e-12);
        // They are still inside each other, so they are still being separated.
        assertTrue(x.a().pushZ() < 0);
        assertTrue(x.b().pushZ() > 0);
    }

    @Test
    void aTouchAtWalkingPaceDoesNotBounce() {
        // Below RESTITUTION_SPEED the collision is perfectly inelastic, which
        // is what turns bumping into pushing. Both end at the same speed and
        // neither is sent backwards.
        VehicleImpacts.Body a = car(0, 0, 0, 1.0);
        VehicleImpacts.Body b = car(0, 2.9, 0, 0);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, VehicleImpacts.contact(a, b));

        double after = 1.0 + x.a().dvz();
        double pushed = x.b().dvz();
        assertEquals(after, pushed, 1e-9, "equal masses should end up matched");
        assertTrue(after > 0, "the car behind must not be sent back: " + after);
    }

    @Test
    void aRestingContactNeitherBouncesNorJitters() {
        VehicleImpacts.Body a = car(0, 0, 0, 0);
        VehicleImpacts.Body b = car(0, 2.99, 0, 0);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, VehicleImpacts.contact(a, b));

        assertEquals(0, x.a().dvz(), 1e-12);
        assertEquals(0, x.b().dvz(), 1e-12);
        // The overlap is under the slop, so nothing is even separated.
        assertEquals(0, x.a().pushZ(), 1e-12);
        assertEquals(0, x.b().pushZ(), 1e-12);
    }

    @Test
    void aVeryDeepOverlapIsEasedApartRatherThanFlung() {
        // Two vehicles spawned on top of each other. Whatever the depth, one
        // tick may not move either of them more than MAX_CORRECTION.
        VehicleImpacts.Body a = car(0, 0, 0, 0);
        VehicleImpacts.Body b = car(0, 0.1, 0, 0);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, VehicleImpacts.contact(a, b));

        double moved = Math.hypot(x.a().pushX(), x.a().pushZ())
                + Math.hypot(x.b().pushX(), x.b().pushZ());
        assertTrue(moved <= VehicleImpacts.MAX_CORRECTION + 1e-9, "flung: " + moved);
        assertTrue(moved > 0, "and it does have to get out eventually");
    }

    @Test
    void aHighSpeedImpactStaysBoundedByTheSpeedThatCausedIt() {
        // No configuration of masses and speeds may produce a change of
        // velocity out of proportion to the closing speed — which is the
        // difference between a crash and a vehicle leaving the world border.
        VehicleImpacts.Body a = car(0, 0, 0, 60, 2);
        VehicleImpacts.Body b = car(0, 2.8, 180, 60, 90);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, VehicleImpacts.contact(a, b));

        double closing = 120;
        assertTrue(Math.hypot(x.a().dvx(), x.a().dvz()) < closing * (1 + VehicleImpacts.RESTITUTION) + 1e-6);
        assertTrue(Math.hypot(x.b().dvx(), x.b().dvz()) < closing * (1 + VehicleImpacts.RESTITUTION) + 1e-6);
        assertTrue(Math.abs(x.a().dspin()) <= VehicleImpacts.MAX_SPIN);
        assertTrue(Math.abs(x.b().dspin()) <= VehicleImpacts.MAX_SPIN);
    }

    @Test
    void aGlancingBlowExchangesLessThanASquareOne() {
        VehicleImpacts.Body a = car(0, 0, 0, 12);
        VehicleImpacts.Body square = car(0, 2.8, 180, 0);
        VehicleImpacts.Body glancing = car(1.3, 0, 75, 0);
        double head = Math.abs(VehicleImpacts.resolve(a, square,
                VehicleImpacts.contact(a, square)).a().dvz());
        VehicleImpacts.Contact side = VehicleImpacts.contact(a, glancing);
        assertNotNull(side);
        double graze = Math.hypot(
                VehicleImpacts.resolve(a, glancing, side).a().dvx(),
                VehicleImpacts.resolve(a, glancing, side).a().dvz());
        assertTrue(graze < head, "a scrape should cost less than a crash: " + graze + " vs " + head);
    }

    @Test
    void sideSwipingDragsTheOtherCarAlong() {
        // The friction impulse, which is the whole difference between a
        // side-swipe and two frictionless boxes sliding past each other. Both
        // are pointing the same way and a is overtaking; it also has to be
        // drifting INTO b, because two vehicles merely overlapping with nothing
        // closing are not colliding — they are being separated, which the last
        // test but one is about.
        VehicleImpacts.Body a = new VehicleImpacts.Body(0, 0, 0, WIDTH, LENGTH, 2, 14, 0, 10);
        VehicleImpacts.Body b = car(1.3, 0, 0, 4);
        VehicleImpacts.Contact c = VehicleImpacts.contact(a, b);
        assertNotNull(c);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(a, b, c);

        assertTrue(x.b().dvz() > 0.05, "the car scraped should be dragged forward: " + x.b().dvz());
        assertTrue(x.a().dvz() < -0.05, "and the one scraping should pay for it: " + x.a().dvz());
        // Sideways, they are pushed apart.
        assertTrue(x.a().dvx() < 0 && x.b().dvx() > 0);
    }

    @Test
    void aSpinningCarIsMeasuredAtThePointItTouches() {
        // A body's edge is not moving at the speed of its centre. Two cars
        // whose CENTRES are stationary relative to each other still collide if
        // one is turning into the other — and reading centre velocities would
        // find nothing to do here at all.
        VehicleImpacts.Body still = new VehicleImpacts.Body(0, 0, 0, WIDTH, LENGTH, 0, 0, 0, 10);
        // Beside it and a little ahead, so the corner the two touch at is one
        // the spin is carrying INTO the other car rather than along it.
        VehicleImpacts.Body spinning = new VehicleImpacts.Body(1.35, 1.0, 0, WIDTH, LENGTH, 0, 0, -120, 10);
        VehicleImpacts.Contact c = VehicleImpacts.contact(still, spinning);
        assertNotNull(c);
        VehicleImpacts.Exchange x = VehicleImpacts.resolve(still, spinning, c);

        assertTrue(Math.hypot(x.a().dvx(), x.a().dvz()) > 0.1,
                "a spin swinging into something is an impact");
        // And the spin itself is answered rather than only the drift.
        assertTrue(x.b().dspin() > 0, "the spin should be resisted: " + x.b().dspin());
    }

    // --- how it lands back in the handling model -------------------------

    @Test
    void anImpactBecomesSpeedAndSlipInTheVehiclesOwnFrame() {
        // Facing +z at 10, hit square in the side from -x: the whole of it
        // should arrive as slip, and none of it should touch the speed.
        VehiclePhysics.State state = new VehiclePhysics.State(0, 10, 0);
        double[] v = VehiclePhysics.worldVelocity(0, 10, 0);
        VehiclePhysics.State hit = state.impacted(v[0] + 6, v[1], 0);

        assertEquals(10, hit.speed(), 1e-9);
        // Right of a car facing +z is -x, so a shove toward +x is slip to its
        // LEFT and comes out negative. See VehiclePhysics.right.
        assertEquals(-6, hit.slip(), 1e-9);
        // And the body is thrown over on its springs rather than staying level.
        assertTrue(hit.rollRate() != 0);
    }

    @Test
    void anImpactSurvivesBeingHitWhileTurned() {
        VehiclePhysics.State state = new VehiclePhysics.State(140, 8, 0);
        double[] v = VehiclePhysics.worldVelocity(140, 8, 0);
        VehiclePhysics.State hit = state.impacted(v[0], v[1], 45);

        assertEquals(8, hit.speed(), 1e-9);
        assertEquals(0, hit.slip(), 1e-9);
        assertEquals(45, hit.yawRate(), 1e-9);
        assertEquals(140, hit.yaw(), 1e-9);
    }

    @Test
    void anImpactWithNothingInItChangesNothing() {
        VehiclePhysics.State state = new VehiclePhysics.State(0, 10, 0);
        assertEquals(state.speed(), state.impacted(Double.NaN, 0, 0).speed(), 1e-9);
    }
}
