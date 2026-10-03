package ai.resourcepack.engine.core.serve;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who has finished loading what they were sent.
 *
 * <p>A rebuild replaces the file a bundle's URL points at, so a player whose
 * download of the old build was still running gets a failure — and must be
 * sent the new one. A player who already loaded the old build must NOT be:
 * re-sending is a reload of their whole client, and doing it to everybody
 * whenever somebody new walked in is what made the test server's screens go
 * dark on every join.
 */
class PackLoadsTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID MAIN = UUID.randomUUID();
    private static final UUID STUDIO = UUID.randomUUID();

    @Test
    void aPackJustSentIsNotLoadedYet() {
        PackLoads loads = new PackLoads();
        loads.sent(PLAYER, MAIN);
        assertFalse(loads.loaded(PLAYER, MAIN));
    }

    @Test
    void theClientSayingSoIsWhatLoadsIt() {
        PackLoads loads = new PackLoads();
        loads.sent(PLAYER, MAIN);
        loads.confirmed(PLAYER, MAIN);
        assertTrue(loads.loaded(PLAYER, MAIN));
    }

    @Test
    void sendingItAgainStartsOver() {
        PackLoads loads = new PackLoads();
        loads.sent(PLAYER, MAIN);
        loads.confirmed(PLAYER, MAIN);
        loads.sent(PLAYER, MAIN);
        assertFalse(loads.loaded(PLAYER, MAIN));
    }

    @Test
    void eachPackIsItsOwnAnswer() {
        PackLoads loads = new PackLoads();
        loads.sent(PLAYER, MAIN);
        loads.sent(PLAYER, STUDIO);
        loads.confirmed(PLAYER, STUDIO);
        assertFalse(loads.loaded(PLAYER, MAIN));
        assertTrue(loads.loaded(PLAYER, STUDIO));
    }

    /** Below 1.20.3 a status names no pack: it is about the one slot, so everything sent. */
    @Test
    void aStatusWithNoIdConfirmsEverythingSent() {
        PackLoads loads = new PackLoads();
        loads.sent(PLAYER, MAIN);
        loads.sent(PLAYER, STUDIO);
        loads.confirmed(PLAYER, null);
        assertTrue(loads.loaded(PLAYER, MAIN));
        assertTrue(loads.loaded(PLAYER, STUDIO));
    }

    /** A pack we never sent — another plugin's, or the published one — is not ours to record. */
    @Test
    void aConfirmationForSomethingNeverSentIsIgnored() {
        PackLoads loads = new PackLoads();
        loads.confirmed(PLAYER, MAIN);
        assertFalse(loads.loaded(PLAYER, MAIN));
    }

    /** Declining settles it: a rebuild must not ask somebody who said no a second time. */
    @Test
    void aDeclineIsSettledButNotLoaded() {
        PackLoads loads = new PackLoads();
        loads.sent(PLAYER, MAIN);
        assertFalse(loads.settled(PLAYER, MAIN));
        loads.declined(PLAYER, MAIN);
        assertTrue(loads.settled(PLAYER, MAIN));
        assertFalse(loads.loaded(PLAYER, MAIN));
    }

    @Test
    void leavingForgetsEverything() {
        PackLoads loads = new PackLoads();
        loads.sent(PLAYER, MAIN);
        loads.confirmed(PLAYER, MAIN);
        loads.forget(PLAYER);
        assertFalse(loads.loaded(PLAYER, MAIN));
    }
}
