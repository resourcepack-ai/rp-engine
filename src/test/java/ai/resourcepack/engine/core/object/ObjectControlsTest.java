package ai.resourcepack.engine.core.object;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds {@link ObjectControls#color} to the colours Studio's item shader was
 * written to decode. {@code object/controls.txt} is written by Studio from its
 * own layout, so a change to either end's bit packing fails here rather than
 * as an object that plays the wrong state in game.
 */
class ObjectControlsTest {

    @Test
    void packsWhatTheShaderDecodes() throws IOException {
        String text;
        try (InputStream in = ObjectControlsTest.class.getResourceAsStream("/object/controls.txt")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int cases = 0;
        for (String line : text.split("\n")) {
            line = line.strip();
            if (line.startsWith("# period ")) {
                String[] parts = line.split(" ");
                assertEquals(ObjectControls.PERIOD, Integer.parseInt(parts[2]), "the period");
                assertEquals(ObjectControls.SETTLED, Integer.parseInt(parts[4]), "the settled stamp");
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(" ");
            int expected = Integer.parseInt(f[4], 16);
            int actual = ObjectControls.color(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                    Long.parseLong(f[2]), Integer.parseInt(f[3]));
            assertEquals(expected, actual, () -> "the colour for " + String.join(" ", f[0], f[1], f[2], f[3]));
            cases++;
        }
        assertTrue(cases > 0, "the fixture has cases");
    }
}
