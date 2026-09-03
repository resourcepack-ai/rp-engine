package ai.resourcepack.engine.api;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which animation a vehicle plays for what it is doing.
 *
 * <p>A vehicle is in several states at once, and a rig has one clock, so
 * something has to choose. That choice is the part of this feature most likely
 * to be quietly wrong — it has no visible failure, it just plays the less
 * interesting of two animations — which is why it is arithmetic in an enum
 * with tests rather than a switch in the runtime.
 */
class VehicleStateTest {

    private static Map<VehicleState, String> named(VehicleState... states) {
        Map<VehicleState, String> animations = new EnumMap<>(VehicleState.class);
        for (VehicleState state : states) {
            animations.put(state, state.key() + "-animation");
        }
        return animations;
    }

    /** The ordinary case: one state active, one animation configured. */
    @Test
    void playsTheAnimationForTheStateItIsIn() {
        assertEquals("moving-animation",
                VehicleState.choose(Set.of(VehicleState.MOVING),
                        named(VehicleState.IDLE, VehicleState.MOVING)).orElseThrow());
    }

    /**
     * Leaving the ground beats everything: a car mid-jump is not driving,
     * whatever its wheels are doing.
     */
    @Test
    void airborneBeatsEverythingElse() {
        Set<VehicleState> jumping =
                EnumSet.of(VehicleState.MOVING, VehicleState.TURNING, VehicleState.AIRBORNE);
        assertEquals("airborne-animation",
                VehicleState.choose(jumping, named(VehicleState.values())).orElseThrow());
    }

    /** Direction of travel beats turning: a car reversing round a corner is reversing. */
    @Test
    void travelBeatsTurning() {
        assertEquals("reversing-animation",
                VehicleState.choose(EnumSet.of(VehicleState.REVERSING, VehicleState.TURNING),
                        named(VehicleState.values())).orElseThrow());
        assertEquals("moving-animation",
                VehicleState.choose(EnumSet.of(VehicleState.MOVING, VehicleState.TURNING),
                        named(VehicleState.values())).orElseThrow());
    }

    /**
     * Being in water is the LEAST specific thing a vehicle can be doing,
     * because for a boat it is simply the normal case. A boat that reported
     * "submerged" over "moving" would play its floating animation for its
     * whole life.
     */
    @Test
    void submergedLosesToActuallyDoingSomething() {
        assertEquals("moving-animation",
                VehicleState.choose(EnumSet.of(VehicleState.MOVING, VehicleState.SUBMERGED),
                        named(VehicleState.values())).orElseThrow());
        // And it loses to standing still, too. SUBMERGED is below IDLE, so a
        // boat that is not doing anything plays its idle animation rather than
        // its water one — which is what stops a rowing cycle mapped to
        // `submerged` running at the mooring.
        assertEquals("idle-animation",
                VehicleState.choose(EnumSet.of(VehicleState.IDLE, VehicleState.SUBMERGED),
                        named(VehicleState.values())).orElseThrow());
    }

    /**
     * <strong>The bug this ordering exists to make impossible.</strong>
     *
     * <p>A pack that put its rowing cycle on every state and then cleared
     * {@code idle} to stop the boat rowing while moored got no change at all:
     * SUBMERGED was above IDLE, it is permanently true for a boat, and it took
     * the empty slot. Clearing idle now means what it looks like it means.
     */
    @Test
    void clearingIdleSilencesAMooredBoat() {
        Map<VehicleState, String> everythingButIdle = named(VehicleState.values());
        everythingButIdle.remove(VehicleState.IDLE);

        assertTrue(VehicleState.choose(EnumSet.of(VehicleState.IDLE, VehicleState.SUBMERGED),
                everythingButIdle).isEmpty());
        // Still rows the moment it is under way, which is the whole point of
        // clearing only the one state.
        assertEquals("moving-animation",
                VehicleState.choose(EnumSet.of(VehicleState.MOVING, VehicleState.SUBMERGED),
                        everythingButIdle).orElseThrow());
    }

    /**
     * IDLE stops the walk, and only IDLE does. A blank state above it still
     * falls through, or a cornering car with no leaning animation would fall
     * silent mid-corner.
     */
    @Test
    void idleIsTheFloorAndNothingElseIs() {
        Map<VehicleState, String> justSubmerged = named(VehicleState.SUBMERGED);

        // Turning is blank, and turning is not the floor: a boat swinging
        // round in the water reaches SUBMERGED.
        assertEquals("submerged-animation",
                VehicleState.choose(EnumSet.of(VehicleState.TURNING, VehicleState.SUBMERGED),
                        justSubmerged).orElseThrow());
        // The same boat, stationary. IDLE is blank AND is the floor, so it
        // never reaches SUBMERGED.
        assertTrue(VehicleState.choose(EnumSet.of(VehicleState.IDLE, VehicleState.SUBMERGED),
                justSubmerged).isEmpty());
    }

    /**
     * <strong>The rule the whole vocabulary rests on.</strong> An author who
     * configured only idle and moving must get sensible behaviour without ever
     * learning that SUBMERGED or TURNING exist — so an unconfigured state is
     * skipped and the next one down is tried, rather than being played empty.
     */
    @Test
    void anUnconfiguredStateFallsThroughToTheNextOne() {
        Map<VehicleState, String> justTheTwo = named(VehicleState.IDLE, VehicleState.MOVING);

        // A boat in water, driving: submerged and turning are both unset, so
        // it plays the drive cycle rather than nothing.
        assertEquals("moving-animation",
                VehicleState.choose(
                        EnumSet.of(VehicleState.MOVING, VehicleState.TURNING, VehicleState.SUBMERGED),
                        justTheTwo).orElseThrow());

        // Airborne is unset too, so a car off a kerb keeps driving.
        assertEquals("moving-animation",
                VehicleState.choose(EnumSet.of(VehicleState.MOVING, VehicleState.AIRBORNE),
                        justTheTwo).orElseThrow());
    }

    /**
     * <strong>A boat is ALWAYS submerged, which is what makes this the case
     * worth pinning down.</strong>
     *
     * <p>A water vehicle is in that state for its entire life, so it is the one
     * state that could shadow every quieter one — which is precisely why it is
     * the LAST. A moored boat is idle, and what it plays is its idle animation
     * whether or not it also has a water one.
     */
    @Test
    void aBoatSittingInWaterPlaysItsIdleAnimation() {
        Set<VehicleState> floating = EnumSet.of(VehicleState.IDLE, VehicleState.SUBMERGED);

        // Only idle mapped.
        assertEquals("idle-animation",
                VehicleState.choose(floating, named(VehicleState.IDLE)).orElseThrow());

        // And with the whole set mapped, still idle: standing still is a more
        // specific description of a moored boat than being in water, which is
        // true of it every moment of its life.
        assertEquals("idle-animation",
                VehicleState.choose(floating, named(VehicleState.values())).orElseThrow());
    }

    /**
     * The same boat under way. MOVING beats SUBMERGED, or a boat would play
     * its floating animation for its whole life and its rowing one never.
     */
    @Test
    void aBoatUnderWayPlaysItsMovingAnimation() {
        Set<VehicleState> rowing = EnumSet.of(VehicleState.MOVING, VehicleState.SUBMERGED);
        assertEquals("moving-animation",
                VehicleState.choose(rowing, named(VehicleState.values())).orElseThrow());
        // With only idle and moving mapped — the ordinary two-state pack — a
        // boat rows when it moves and idles when it stops, and never has to
        // know that SUBMERGED exists at all.
        Map<VehicleState, String> justTheTwo = named(VehicleState.IDLE, VehicleState.MOVING);
        assertEquals("moving-animation", VehicleState.choose(rowing, justTheTwo).orElseThrow());
        assertEquals("idle-animation",
                VehicleState.choose(EnumSet.of(VehicleState.IDLE, VehicleState.SUBMERGED), justTheTwo)
                        .orElseThrow());
    }

    /**
     * Empty, not a guess. A pack that configured nothing plays nothing, which
     * the runtime turns into stopping rather than into an animation named "".
     */
    @Test
    void aVehicleThatConfiguredNothingPlaysNothing() {
        assertTrue(VehicleState.choose(Set.of(VehicleState.MOVING), Map.of()).isEmpty());
        assertTrue(VehicleState.choose(Set.of(VehicleState.MOVING),
                named(VehicleState.REVERSING)).isEmpty());
        assertTrue(VehicleState.choose(Set.of(), named(VehicleState.values())).isEmpty());
        assertTrue(VehicleState.choose(null, named(VehicleState.values())).isEmpty());
    }

    /** An entry with an empty name is not an animation, and must not win its state. */
    @Test
    void anEmptyNameIsNotAnAnimation() {
        Map<VehicleState, String> partial = new EnumMap<>(VehicleState.class);
        partial.put(VehicleState.MOVING, "");
        partial.put(VehicleState.IDLE, "parked");
        assertTrue(VehicleState.choose(Set.of(VehicleState.MOVING), partial).isEmpty());
    }

    /**
     * The names are written into YAML by hand and into the push manifest by
     * studio, so both spellings have to survive the trip.
     */
    @Test
    void everyStateParsesFromTheNameItIsWrittenAs() {
        for (VehicleState state : VehicleState.values()) {
            assertEquals(state, VehicleState.parse(state.key()).orElseThrow());
            assertEquals(state, VehicleState.parse(state.key().toUpperCase()).orElseThrow());
            assertEquals(state, VehicleState.parse("  " + state.key() + " ").orElseThrow());
        }
        assertTrue(VehicleState.parse("hovering").isEmpty());
        assertTrue(VehicleState.parse(null).isEmpty());
    }
}
