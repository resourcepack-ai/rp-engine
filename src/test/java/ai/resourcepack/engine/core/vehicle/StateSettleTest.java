package ai.resourcepack.engine.core.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wait, without a server.
 *
 * <p>Same argument as {@link VehiclePhysicsTest}: the rule this encodes — that
 * a state the vehicle crosses in three ticks is not a state anything should be
 * dressed or animated for — is two lines of arithmetic, and the alternative way
 * of finding it wrong is reversing a car on a test server and watching a single
 * frame go past.
 */
class StateSettleTest {

    /** How the runtime drives it: once a tick, with the vehicle's own age. */
    private static boolean run(StateSettle gate, String wanted, long from, long ticks) {
        boolean settled = false;
        for (long t = from; t < from + ticks; t++) {
            settled = gate.settled(wanted, t);
        }
        return settled;
    }

    @Test
    void firstAskWaits() {
        StateSettle gate = new StateSettle();
        assertFalse(gate.settled("moving", 0), "an answer's first tick is not a held answer");
    }

    @Test
    void anAnswerThatHoldsSettles() {
        StateSettle gate = new StateSettle();
        assertFalse(run(gate, "moving", 0, StateSettle.TICKS), "settled a tick early");
        assertTrue(gate.settled("moving", StateSettle.TICKS));
    }

    /**
     * The case this exists for: braking from forwards into reverse crosses IDLE
     * for about three ticks, which is shorter than the wait, so the detour never
     * gets to act and REVERSING does.
     */
    @Test
    void aStateOnlyPassedThroughNeverSettles() {
        StateSettle gate = new StateSettle();
        assertTrue(run(gate, "drive", 0, StateSettle.TICKS + 1), "the drive cycle should be held");

        assertFalse(run(gate, "idle", 100, 3), "three ticks of idle is a crossing, not a state");

        assertFalse(run(gate, "reverse", 103, StateSettle.TICKS), "the wait restarts on a new answer");
        assertTrue(gate.settled("reverse", 103 + StateSettle.TICKS), "reversing held and should play");
    }

    /**
     * The wait is in TICKS and not in transitions, which is the property that
     * makes it safe: a vehicle that really does stand still between a forward
     * and a reverse is idle, and gets the idle animation.
     */
    @Test
    void aStateThatReallyHoldsIsNotSuppressed() {
        StateSettle gate = new StateSettle();
        assertTrue(run(gate, "idle", 0, 40), "two seconds at a standstill is idle by any reading");
    }

    /** Nothing-at-all is an answer like any other, and waits like one. */
    @Test
    void nothingIsAnAnswerAndWaitsLikeOne() {
        StateSettle gate = new StateSettle();
        assertFalse(run(gate, null, 0, StateSettle.TICKS), "a stop is a change and waits");
        assertTrue(gate.settled(null, StateSettle.TICKS));
    }

    /**
     * Clearing re-arms. The runtime clears when the answer settles back onto
     * what is already running — whatever was being waited out never happened —
     * and a cleared gate must not then let the next answer through on its first
     * tick off a stale clock. Null is the trap: an emptied gate holding null
     * against a wanted null is the same value, and it still has to wait.
     */
    @Test
    void clearingRearmsTheWait() {
        StateSettle gate = new StateSettle();
        assertTrue(run(gate, "idle", 0, StateSettle.TICKS + 1));

        gate.clear();
        assertFalse(gate.settled("idle", 50), "a cleared gate starts its clock again");

        gate.clear();
        assertFalse(gate.settled(null, 60), "and does so for nothing-at-all too");
    }
}
