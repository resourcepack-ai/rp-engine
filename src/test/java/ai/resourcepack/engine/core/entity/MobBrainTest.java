package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.MobState;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules for which of a mob's animations plays. */
class MobBrainTest {

    private static Map<MobState, String> mapped(MobState... states) {
        Map<MobState, String> out = new EnumMap<>(MobState.class);
        for (MobState state : states) {
            out.put(state, state.key());
        }
        return out;
    }

    @Test
    void theOrderIsThePrecedenceAndStudioWritesTheSameNames() {
        // Studio's MOB_STATES is a hand copy of this, order included.
        List<String> keys = new java.util.ArrayList<>();
        for (MobState state : MobState.values()) {
            keys.add(state.key());
        }
        assertEquals(List.of("death", "spawn", "attack", "hurt", "run", "walk", "idle"), keys);
        assertTrue(MobState.HURT.oneShot());
        assertFalse(MobState.RUN.oneShot());
    }

    @Test
    void aBlankLoopFallsThroughDownwardsOnly() {
        Map<MobState, String> walkOnly = mapped(MobState.WALK);
        assertEquals(MobState.WALK, MobState.loop(true, true, walkOnly).orElseThrow());
        assertEquals(MobState.WALK, MobState.loop(false, true, walkOnly).orElseThrow());
        // Standing still with only a walk cycle is the rest pose, not walking on the spot.
        assertTrue(MobState.loop(false, false, walkOnly).isEmpty());

        Map<MobState, String> idleOnly = mapped(MobState.IDLE);
        assertEquals(MobState.IDLE, MobState.loop(true, true, idleOnly).orElseThrow());
        assertTrue(MobState.loop(true, true, Map.of()).isEmpty());

        Map<MobState, String> all = mapped(MobState.RUN, MobState.WALK, MobState.IDLE);
        assertEquals(MobState.RUN, MobState.loop(true, true, all).orElseThrow());
    }

    @Test
    void theFirstLookPicksTheLoopAtOnce() {
        MobBrain brain = new MobBrain();
        assertTrue(brain.tick(0, MobState.IDLE));
        assertEquals(MobState.IDLE, brain.showing());
    }

    @Test
    void aLoopChangesOnlyOnceItHasSettled() {
        MobBrain brain = new MobBrain();
        brain.tick(0, MobState.IDLE);
        assertFalse(brain.tick(2, MobState.WALK), "one look at a step is noise");
        assertEquals(MobState.IDLE, brain.showing());
        assertTrue(brain.tick(4, MobState.WALK));
        assertEquals(MobState.WALK, brain.showing());
        // A flicker back and forth never settles.
        assertFalse(brain.tick(6, MobState.IDLE));
        assertFalse(brain.tick(8, MobState.WALK));
        assertEquals(MobState.WALK, brain.showing());
    }

    @Test
    void aOneShotRunsToItsEndThenHandsToWhatTheMobIsDoingNow() {
        MobBrain brain = new MobBrain();
        brain.tick(0, MobState.IDLE);
        assertTrue(brain.trigger(MobState.HURT, 10, 10));
        assertEquals(MobState.HURT, brain.showing());
        assertFalse(brain.tick(12, MobState.RUN));
        assertFalse(brain.tick(14, MobState.RUN));
        assertEquals(MobState.HURT, brain.showing(), "a loop never cuts a one-shot");
        assertTrue(brain.tick(20, MobState.RUN));
        assertEquals(MobState.RUN, brain.showing(), "no settling wait when a one-shot ends");
    }

    @Test
    void onlyAHigherOrEqualOneShotCutsIn() {
        MobBrain brain = new MobBrain();
        brain.tick(0, null);
        assertTrue(brain.trigger(MobState.ATTACK, 0, 20));
        assertFalse(brain.trigger(MobState.HURT, 5, 10), "hurt is lower than attack");
        assertEquals(MobState.ATTACK, brain.showing());
        assertTrue(brain.trigger(MobState.ATTACK, 6, 20), "an equal one restarts");
        assertTrue(brain.trigger(MobState.DEATH, 7, 30));
        assertEquals(MobState.DEATH, brain.showing());
        assertTrue(brain.dead());
    }

    @Test
    void afterDeathNothingElsePlaysEver() {
        MobBrain brain = new MobBrain();
        brain.tick(0, MobState.IDLE);
        brain.trigger(MobState.DEATH, 1, 5);
        assertFalse(brain.trigger(MobState.HURT, 2, 5));
        assertFalse(brain.tick(100, MobState.WALK));
        assertEquals(MobState.DEATH, brain.showing());
    }

    @Test
    void aLowerOneShotPlaysOnceTheHigherHasEnded() {
        MobBrain brain = new MobBrain();
        brain.tick(0, null);
        brain.trigger(MobState.SPAWN, 0, 10);
        assertTrue(brain.trigger(MobState.HURT, 10, 5), "spawn is over by tick 10");
        assertEquals(MobState.HURT, brain.showing());
        assertTrue(brain.tick(15, null));
        assertNull(brain.showing());
    }
}
