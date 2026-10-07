package ai.resourcepack.engine.core.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The beat's body, which the far end parses and is the whole of what is sent. */
class HeartbeatTest {

    @Test
    void theBodyIsTheIdAndTheAddonNames() {
        assertEquals("{\"id\":\"abc\",\"addons\":[\"RPESkateboards\",\"RPEBmx\"]}",
                Heartbeat.body("abc", List.of("RPESkateboards", "RPEBmx")));
        assertEquals("{\"id\":\"abc\",\"addons\":[]}", Heartbeat.body("abc", List.of()));
    }

    @Test
    void aNameCannotBreakOutOfItsString() {
        assertEquals("{\"id\":\"abc\",\"addons\":[\"a\\\"b\\\\c\"]}", Heartbeat.body("abc", List.of("a\"b\\c\n")));
    }
}
