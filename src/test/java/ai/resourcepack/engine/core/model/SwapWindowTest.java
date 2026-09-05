package ai.resourcepack.engine.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window a carried rig's animation swap is eased over.
 *
 * <p>Only the deadline arithmetic is here, because only the deadline
 * arithmetic is arithmetic — what it BUYS is a longer
 * {@code setInterpolationDuration} on a display, which no test without a
 * client can see. Worth pinning anyway: a window that never closes is a rig
 * permanently interpolating over five ticks, which is the chunky motion the
 * carried path was built to avoid.
 */
class SwapWindowTest {

    @Test
    void noWindowIsNotSwapping() {
        assertFalse(RigAnimations.swapping(null, 100));
    }

    @Test
    void itIsOpenForItsWholeLength() {
        long opened = 100;
        long until = opened + RigAnimations.CARRIED_SWAP_TICKS;
        for (long t = opened; t < until; t++) {
            assertTrue(RigAnimations.swapping(until, t), "closed early at " + t);
        }
    }

    @Test
    void itClosesOnTheDeadlineAndStaysClosed() {
        long until = 100 + RigAnimations.CARRIED_SWAP_TICKS;
        assertFalse(RigAnimations.swapping(until, until), "the deadline tick is over, not still going");
        assertFalse(RigAnimations.swapping(until, until + 1000));
    }
}
