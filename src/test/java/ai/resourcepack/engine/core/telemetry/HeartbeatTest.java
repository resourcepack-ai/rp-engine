package ai.resourcepack.engine.core.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The beat's body, which the far end parses and is the whole of what is sent. */
class HeartbeatTest {

    @Test
    void theBodyIsTheIdThePlayerCountAndTheAddonNames() {
        assertEquals("{\"id\":\"abc\",\"players\":12,\"addons\":[\"RPESkateboards\",\"RPEBmx\"]}",
                Heartbeat.body("abc", 12, List.of("RPESkateboards", "RPEBmx")));
        assertEquals("{\"id\":\"abc\",\"players\":0,\"addons\":[]}", Heartbeat.body("abc", 0, List.of()));
    }

    @Test
    void aNameCannotBreakOutOfItsString() {
        assertEquals("{\"id\":\"abc\",\"players\":0,\"addons\":[\"a\\\"b\\\\c\"]}", Heartbeat.body("abc", 0, List.of("a\"b\\c\n")));
    }
}
