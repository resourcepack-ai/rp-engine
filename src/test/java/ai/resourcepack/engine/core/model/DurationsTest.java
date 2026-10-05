package ai.resourcepack.engine.core.model;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticks, seconds and minutes, and a bare number is ticks — the unit every
 * plugin a pack migrates from counts in.
 */
class DurationsTest {

    @Test
    void eachUnitIsItsOwnNumberOfTicks() {
        assertEquals(OptionalLong.of(10), Durations.ticks("10t"));
        assertEquals(OptionalLong.of(200), Durations.ticks("10s"));
        assertEquals(OptionalLong.of(2400), Durations.ticks("2m"));
    }

    @Test
    void aBareNumberIsTicks() {
        assertEquals(OptionalLong.of(200), Durations.ticks("200"));
        assertEquals(OptionalLong.of(0), Durations.ticks("0"));
    }

    @Test
    void secondsAndMinutesMayHaveAFraction() {
        assertEquals(OptionalLong.of(30), Durations.ticks("1.5s"));
        assertEquals(OptionalLong.of(600), Durations.ticks("0.5m"));
    }

    @Test
    void spacingAndCaseDoNotMatter() {
        assertEquals(OptionalLong.of(200), Durations.ticks(" 10 S "));
    }

    @Test
    void halfATickIsNotATime() {
        // More likely seconds somebody forgot to mark than ticks.
        assertTrue(Durations.ticks("1.5").isEmpty());
        assertTrue(Durations.ticks("1.5t").isEmpty());
    }

    @Test
    void nonsenseIsRefused() {
        assertTrue(Durations.ticks("soon").isEmpty());
        assertTrue(Durations.ticks("-5s").isEmpty());
        assertTrue(Durations.ticks("s").isEmpty());
        assertTrue(Durations.ticks("").isEmpty());
        assertTrue(Durations.ticks(null).isEmpty());
        assertTrue(Durations.ticks("10h").isEmpty(), "hours are not a unit here");
        assertTrue(Durations.ticks("99999m").isEmpty(), "longer than a week is a typo");
    }
}
