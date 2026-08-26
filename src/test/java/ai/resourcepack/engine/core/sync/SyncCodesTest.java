package ai.resourcepack.engine.core.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void aUuidRefReadsBackAsTheUuidItNames() {
        // The push that made this necessary: studio addresses a party member
        // by uuid, and a plugin that cannot turn that back into a player
        // answers "unknown-code" for somebody standing right there.
        assertEquals(UUID.fromString("069a79f4-a3df-4229-adc0-7f26f6c2ec3a"),
                SyncCodes.uuidOf("069a79f4a3df4229adc07f26f6c2ec3a"));
        // Case is the wire's to choose, not ours.
        assertEquals(UUID.fromString("069a79f4-a3df-4229-adc0-7f26f6c2ec3a"),
                SyncCodes.uuidOf("069A79F4A3DF4229ADC07F26F6C2EC3A"));
    }

    @Test
    void anythingThatIsNotAUuidRefReadsBackAsNothing() {
        assertNull(SyncCodes.uuidOf("48213097"));
        assertNull(SyncCodes.uuidOf("069a79f4-a3df-4229-adc0-7f26f6c2ec3a"));
        assertNull(SyncCodes.uuidOf(""));
        assertNull(SyncCodes.uuidOf(null));
    }
}
