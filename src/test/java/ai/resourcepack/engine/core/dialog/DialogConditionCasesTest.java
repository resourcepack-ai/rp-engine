package ai.resourcepack.engine.core.dialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every case in {@code dialog-conditions/cases.json}, the file Dialog
 * Engine's own condition test reads too and Studio's evaluator is held to, so
 * the three ends of the condition language answer every case alike.
 */
class DialogConditionCasesTest {

    @Test
    void answersEverySharedCase() throws IOException {
        JsonArray cases;
        try (InputStream in = DialogConditionCasesTest.class.getResourceAsStream("/dialog-conditions/cases.json")) {
            cases = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("cases");
        }
        assertTrue(cases.size() > 0);
        for (JsonElement element : cases) {
            JsonArray c = element.getAsJsonArray();
            String expression = c.get(0).getAsString();
            Map<String, String> values = new HashMap<>();
            c.get(1).getAsJsonObject().entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
            Set<String> held = new HashSet<>();
            c.get(2).getAsJsonArray().forEach(p -> held.add(p.getAsString()));
            boolean want = c.get(3).getAsBoolean();
            boolean got = DialogConditions.test(expression, name -> Optional.ofNullable(values.get(name)), held::contains);
            assertEquals(want, got, () -> expression + " with " + values + " and " + held);
        }
    }
}
