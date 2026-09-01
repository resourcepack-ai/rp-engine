package ai.resourcepack.engine.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The movement states a stance is worn for, ported from the engine this one
 * replaced.
 *
 * <p>These tests are the port's proof: the wire names and the resolution order
 * are a contract with manifests that already exist, so anything that differs
 * from the original is a stance that silently stops being worn.
 */
class EmoteTriggerTest {

    @Test
    void airWinsOverEverything() {
        // Being off the ground beats every other thing a body is doing.
        assertEquals(EmoteTrigger.JUMP, EmoteTrigger.of(true, true, true, true));
        assertEquals(EmoteTrigger.JUMP, EmoteTrigger.of(false, false, false, true));
    }

    @Test
    void crouchingBeatsSprintingAndSprintingBeatsWalking() {
        assertEquals(EmoteTrigger.SNEAK_MOVE, EmoteTrigger.of(true, true, true, false));
        assertEquals(EmoteTrigger.SNEAK_IDLE, EmoteTrigger.of(true, true, false, false));
        assertEquals(EmoteTrigger.SPRINT, EmoteTrigger.of(false, true, true, false));
        assertEquals(EmoteTrigger.WALK, EmoteTrigger.of(false, false, true, false));
        assertEquals(EmoteTrigger.IDLE, EmoteTrigger.of(false, false, false, false));
    }

    @Test
    void exactlyOneStateHolds() {
        // An emote cannot be half-playing, so a crouch-walk is SNEAK_MOVE and
        // never also WALK.
        assertEquals(EmoteTrigger.SNEAK_MOVE, EmoteTrigger.of(true, false, true, false));
    }

    @Test
    void sneakIsAnUmbrellaAndIsNeverResolvedTo() {
        for (boolean sneaking : new boolean[]{true, false}) {
            for (boolean sprinting : new boolean[]{true, false}) {
                for (boolean moving : new boolean[]{true, false}) {
                    for (boolean airborne : new boolean[]{true, false}) {
                        assertTrue(EmoteTrigger.of(sneaking, sprinting, moving, airborne)
                                        != EmoteTrigger.SNEAK,
                                "a state meaning either of two things cannot drive one clock");
                    }
                }
            }
        }
    }

    @Test
    void aPackBuiltBeforeTheSplitFallsBackToSneak() {
        // The two crouching states find the one emote an older pack named.
        assertEquals(EmoteTrigger.SNEAK, EmoteTrigger.SNEAK_IDLE.fallback());
        assertEquals(EmoteTrigger.SNEAK, EmoteTrigger.SNEAK_MOVE.fallback());
        assertNull(EmoteTrigger.SNEAK.fallback());
        assertNull(EmoteTrigger.WALK.fallback());
    }

    @Test
    void sneakCoversBothCrouchingStates() {
        // The other direction. A caller asking whether a set covers SNEAK_MOVE
        // would otherwise be told no about a set that plainly does.
        assertEquals(EnumSet.of(EmoteTrigger.SNEAK_IDLE, EmoteTrigger.SNEAK_MOVE),
                EmoteTrigger.SNEAK.covers());
        assertEquals(EnumSet.of(EmoteTrigger.WALK), EmoteTrigger.WALK.covers());
    }

    @Test
    void wireNamesAreTheOnesStudioWrites() {
        assertEquals("idle", EmoteTrigger.IDLE.wireName());
        assertEquals("sneak_idle", EmoteTrigger.SNEAK_IDLE.wireName());
        assertEquals("sneak_move", EmoteTrigger.SNEAK_MOVE.wireName());
        assertEquals(EmoteTrigger.SPRINT, EmoteTrigger.of("sprint"));
    }

    @Test
    void aStateThisJarHasNeverHeardOfIsNullRatherThanAThrow() {
        // A newer studio may name a state this build cannot detect. Refusing
        // to load the emote at all, or loading a stance whose condition can
        // never hold, are both worse than playing the states it does know.
        assertNull(EmoteTrigger.of("moonwalk"));
        assertNull(EmoteTrigger.of((String) null));
    }

    @Test
    void everyStateRoundTripsThroughItsWireName() {
        for (EmoteTrigger trigger : EmoteTrigger.values()) {
            assertEquals(trigger, EmoteTrigger.of(trigger.wireName()));
        }
    }
}
