package ai.resourcepack.engine.core.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncCodesTest {

    @Test
    void anEightDigitCodeIsValid() {
        assertTrue(SyncCodes.isValid("48213097"));
        assertTrue(SyncCodes.isValid("00000000"));
    }

    @Test
    void aThirtyTwoHexUuidIsValid() {
        // The permalink flow. The two shapes do not collide, which is why one
        // protocol carries both.
        assertTrue(SyncCodes.isValid("069a79f4a3df4229adc07f26f6c2ec3a"));
    }

    @Test
    void anythingThatIsNotEitherShapeIsRefused() {
        // The one that prompted this: a stray command landed in the code slot
        // and was reported as synced, which is a lie about a thing that will
        // never arrive.
        assertFalse(SyncCodes.isValid("/link"));
        assertFalse(SyncCodes.isValid("4821309"));
        assertFalse(SyncCodes.isValid("482130977"));
        assertFalse(SyncCodes.isValid("4821309a"));
        assertFalse(SyncCodes.isValid("069a79f4-a3df-4229-adc0-7f26f6c2ec3a"));
        assertFalse(SyncCodes.isValid(""));
        assertFalse(SyncCodes.isValid(null));
    }
}
