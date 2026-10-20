package ai.resourcepack.engine.core.serve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which failures send a player this server's own copies
 * (host.fallback-on-download-failure).
 *
 * <p>A status does not say which send it is about, so the rule is that a
 * failure counts only after exactly one acceptance since our last send: none,
 * and it is left over from an earlier build; two, and another pack went into
 * the same slot.
 */
class DownloadFallbackTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID BASE = UUID.randomUUID();
    private static final UUID RIGS = UUID.randomUUID();
    private static final UUID STUDIO = UUID.randomUUID();

    @Test
    void thePackHostFailingAnAcceptedPackFallsBack() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.sent(PLAYER, BASE, true);
        assertFalse(fallback.failedOnPackHost(PLAYER, BASE, "ACCEPTED"));
        assertTrue(fallback.failedOnPackHost(PLAYER, BASE, "FAILED_DOWNLOAD"));
    }

    @Test
    void aPackThatLoadedHasNothingToFallBackOn() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.sent(PLAYER, BASE, true);
        fallback.failedOnPackHost(PLAYER, BASE, "ACCEPTED");
        fallback.failedOnPackHost(PLAYER, BASE, "DOWNLOADED");
        assertFalse(fallback.failedOnPackHost(PLAYER, BASE, "SUCCESSFULLY_LOADED"));
        assertFalse(fallback.failedOnPackHost(PLAYER, BASE, "FAILED_DOWNLOAD"));
    }

    @Test
    void aPackNotFromThePackHostIsNotFallenBackOn() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.sent(PLAYER, STUDIO, false);
        fallback.failedOnPackHost(PLAYER, STUDIO, "ACCEPTED");
        assertFalse(fallback.failedOnPackHost(PLAYER, STUDIO, "FAILED_DOWNLOAD"));
    }

    @Test
    void aPackWeDidNotSendIsNotOurs() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.sent(PLAYER, BASE, true);
        fallback.failedOnPackHost(PLAYER, STUDIO, "ACCEPTED");
        assertFalse(fallback.failedOnPackHost(PLAYER, STUDIO, "FAILED_DOWNLOAD"));
    }

    /** A rebuild is sent with the same id; the old build's failure arrives before the new one is accepted. */
    @Test
    void aFailureLeftOverFromAnEarlierBuildIsNotTheNewOnes() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.sent(PLAYER, BASE, true);
        fallback.failedOnPackHost(PLAYER, BASE, "ACCEPTED");
        fallback.sent(PLAYER, BASE, true);
        assertFalse(fallback.failedOnPackHost(PLAYER, BASE, "FAILED_DOWNLOAD"));

        fallback.failedOnPackHost(PLAYER, BASE, "ACCEPTED");
        assertTrue(fallback.failedOnPackHost(PLAYER, BASE, "FAILED_DOWNLOAD"));
    }

    @Test
    void aPlayerAlreadySentOurCopiesIsNotFallenBackAgain() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.serveLocally(PLAYER);
        fallback.sent(PLAYER, BASE, true);
        fallback.failedOnPackHost(PLAYER, BASE, "ACCEPTED");
        assertFalse(fallback.failedOnPackHost(PLAYER, BASE, "FAILED_DOWNLOAD"));
    }

    /** Below 1.20.3 a status names no pack: the one slot is what it is about. */
    @Test
    void belowStackingOurOwnFailureFallsBack() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.sent(PLAYER, null, true);
        fallback.failedOnPackHost(PLAYER, null, "ACCEPTED");
        assertTrue(fallback.failedOnPackHost(PLAYER, null, "FAILED_DOWNLOAD"));
    }

    /** Another plugin's pack (or the distribution pack) took the slot after ours, and it is what failed. */
    @Test
    void belowStackingSomebodyElsesFailureIsNotOurs() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.sent(PLAYER, null, true);
        fallback.failedOnPackHost(PLAYER, null, "ACCEPTED");
        fallback.failedOnPackHost(PLAYER, null, "ACCEPTED");
        assertFalse(fallback.failedOnPackHost(PLAYER, null, "FAILED_DOWNLOAD"));
    }

    @Test
    void aRemovedPackIsForgotten() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.sent(PLAYER, BASE, true);
        fallback.failedOnPackHost(PLAYER, BASE, "ACCEPTED");
        fallback.removed(PLAYER, BASE);
        assertFalse(fallback.failedOnPackHost(PLAYER, BASE, "FAILED_DOWNLOAD"));
    }

    @Test
    void aPlayerWhoLeftStartsOver() {
        DownloadFallback fallback = new DownloadFallback();
        fallback.serveLocally(PLAYER);
        fallback.forget(PLAYER);
        assertFalse(fallback.local(PLAYER));
    }

    /** A pack sent again lands on top, so everything above the failed one goes again after it. */
    @Test
    void theFailedPackAndEverythingAboveItAreSentAgain() {
        List<UUID> stack = List.of(BASE, RIGS, STUDIO);
        assertEquals(1, DownloadFallback.resendFrom(stack, RIGS));
        assertEquals(0, DownloadFallback.resendFrom(stack, BASE));
        assertEquals(-1, DownloadFallback.resendFrom(stack, UUID.randomUUID()));
    }

    @Test
    void belowStackingTheTopIsWhatWasSent() {
        assertEquals(2, DownloadFallback.resendFrom(List.of(BASE, RIGS, STUDIO), null));
    }
}
