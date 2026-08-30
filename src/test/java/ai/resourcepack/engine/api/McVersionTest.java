package ai.resourcepack.engine.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McVersionTest {

    @Test
    void parsesWhatTheServerActuallyReports() {
        // getBukkitVersion()'s real shape, which is the only string this
        // parser is guaranteed to be handed in production.
        assertEquals(McVersion.of(1, 21, 8), McVersion.parse("1.21.8-R0.1-SNAPSHOT").orElseThrow());
    }

    @Test
    void parsesAVersionWithNoPatch() {
        assertEquals(McVersion.of(1, 21), McVersion.parse("1.21").orElseThrow());
        assertEquals(McVersion.of(1, 21, 0), McVersion.parse("1.21").orElseThrow());
    }

    @Test
    void parsesTheDateShapedScheme() {
        // 26.1 is not 1.x, and nothing here assumes a leading 1.
        assertEquals(McVersion.of(26, 1), McVersion.parse("26.1").orElseThrow());
    }

    @Test
    void stopsAtTheFirstThingThatIsNotAVersion() {
        assertEquals(McVersion.of(1, 21, 9), McVersion.parse("1.21.9-pre2").orElseThrow());
    }

    @Test
    void refusesAStringWithNoLeadingNumber() {
        assertFalse(McVersion.parse("unknown").isPresent());
        assertFalse(McVersion.parse("").isPresent());
        assertFalse(McVersion.parse(null).isPresent());
    }

    @Test
    void ignoresAFourthComponent() {
        // Nothing releases one, but a fork's build string might, and it must
        // not shift the three that matter.
        assertEquals(McVersion.of(1, 21, 8), McVersion.parse("1.21.8.3").orElseThrow());
    }

    @Test
    void ordersByNumberAndNotByText() {
        // The bug this type exists to prevent: "1.21.10" sorts before
        // "1.21.9" as a string, and every floor written as text is wrong on
        // exactly the release nobody tested.
        assertTrue(McVersion.of(1, 21, 10).atLeast(McVersion.of(1, 21, 9)));
        assertFalse(McVersion.of(1, 21, 9).atLeast(McVersion.of(1, 21, 10)));
    }

    @Test
    void ordersTheDateSchemeAboveEveryOneDotVersion() {
        assertTrue(McVersion.of(26, 1).atLeast(McVersion.of(1, 21, 11)));
        assertTrue(McVersion.of(26, 2).atLeast(McVersion.of(26, 1)));
    }

    @Test
    void treatsAMissingPatchAsZero() {
        assertTrue(McVersion.of(1, 21, 1).atLeast(McVersion.of(1, 21)));
        assertFalse(McVersion.of(1, 21).atLeast(McVersion.of(1, 21, 1)));
    }

    @Test
    void writesItselfTheWayMojangDoes() {
        assertEquals("1.21", McVersion.of(1, 21).toString());
        assertEquals("1.21.4", McVersion.of(1, 21, 4).toString());
        assertEquals("26.1", McVersion.of(26, 1).toString());
    }

    @Test
    void theFloorIsWhereDisplayEntitiesArrived() {
        assertEquals(McVersion.of(1, 19, 4), McVersion.OLDEST_SUPPORTED);
    }
}
