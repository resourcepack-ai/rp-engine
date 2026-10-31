package ai.resourcepack.engine.core.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser against {@code skill-lines/parser-cases.json}, a file a second
 * parser reads too: Studio's mob editor shows and edits a mob's skills as
 * MythicMobs lines and checks them as the author types, with a port of this
 * grammar, and its verify script runs that port over the same cases. A case
 * that passes here and fails there (or the reverse) is the two disagreeing
 * about what a line means.
 */
class SkillParserFixturesTest {

    private static JsonObject cases() throws IOException {
        try (InputStream in = SkillParserFixturesTest.class.getResourceAsStream("/skill-lines/parser-cases.json")) {
            assertNotNull(in, "skill-lines/parser-cases.json is missing");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Map<String, String> args(JsonObject owner) {
        Map<String, String> out = new LinkedHashMap<>();
        if (owner != null && owner.has("args")) {
            owner.getAsJsonObject("args").entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        }
        return out;
    }

    private static boolean flag(JsonObject owner, String key) {
        return owner.has(key) && owner.get(key).getAsBoolean();
    }

    private static Double bound(JsonElement value) {
        return value == null || value.isJsonNull() ? null : value.getAsDouble();
    }

    private static Double finite(double value) {
        return Double.isInfinite(value) ? null : value;
    }

    @Test
    void everyLineCase() throws IOException {
        for (JsonElement element : cases().getAsJsonArray("lines")) {
            JsonObject expected = element.getAsJsonObject();
            String text = expected.get("line").getAsString();
            SkillParser.Parsed parsed = SkillParser.parse(text);
            if (expected.has("error")) {
                assertFalse(parsed.ok(), () -> text + " should not parse");
                String want = expected.get("error").getAsString();
                assertTrue(parsed.error.contains(want), () -> text + ": \"" + parsed.error + "\" does not say " + want);
                continue;
            }
            assertTrue(parsed.ok(), () -> text + ": " + parsed.error);
            SkillLine line = parsed.line;
            assertEquals(expected.get("mechanic").getAsString(), line.mechanic, text);
            assertEquals(args(expected), line.args.values, text);
            JsonObject targeter = expected.has("targeter") ? expected.getAsJsonObject("targeter") : null;
            if (targeter == null) {
                assertEquals(null, line.targeter, text);
            } else {
                assertNotNull(line.targeter, text);
                assertEquals(targeter.get("name").getAsString(), line.targeter.name, text);
                assertEquals(args(targeter), line.targeter.args.values, text);
            }
            JsonObject trigger = expected.has("trigger") ? expected.getAsJsonObject("trigger") : null;
            if (trigger == null) {
                assertEquals(null, line.trigger, text);
            } else {
                assertNotNull(line.trigger, text);
                assertEquals(trigger.get("name").getAsString(), line.trigger.name, text);
                assertEquals(trigger.has("arg") ? trigger.get("arg").getAsString() : null, line.trigger.arg, text);
            }
            JsonArray conditions = expected.has("conditions") ? expected.getAsJsonArray("conditions") : new JsonArray();
            assertEquals(conditions.size(), line.conditions.size(), text);
            for (int i = 0; i < conditions.size(); i++) {
                JsonObject want = conditions.get(i).getAsJsonObject();
                SkillLine.Cond got = line.conditions.get(i);
                assertEquals(want.get("name").getAsString(), got.ref.name, text);
                assertEquals(args(want), got.ref.args.values, text);
                assertEquals(flag(want, "negate"), got.negate, text);
                assertEquals(flag(want, "onTrigger"), got.onTrigger, text);
            }
            JsonObject health = expected.has("health") ? expected.getAsJsonObject("health") : null;
            if (health == null) {
                assertEquals(null, line.health, text);
            } else {
                assertNotNull(line.health, text);
                assertEquals(health.get("op").getAsString(), String.valueOf(line.health.range.op), text);
                assertEquals(bound(health.get("low")), finite(line.health.range.low), text);
                assertEquals(bound(health.get("high")), finite(line.health.range.high), text);
                assertEquals(health.get("percent").getAsBoolean(), line.health.range.percent, text);
                assertEquals(health.get("once").getAsBoolean(), line.health.once, text);
            }
            assertEquals(expected.has("chance") ? expected.get("chance").getAsDouble() : 1.0, line.chance, 1e-9, text);
        }
    }

    @Test
    void everyConditionCase() throws IOException {
        for (JsonElement element : cases().getAsJsonArray("conditions")) {
            JsonObject expected = element.getAsJsonObject();
            String text = expected.get("line").getAsString();
            SkillParser.Parsed parsed = SkillParser.condition(text, false);
            if (expected.has("error")) {
                assertFalse(parsed.ok(), () -> text + " should not parse");
                String want = expected.get("error").getAsString();
                assertTrue(parsed.error.contains(want), () -> text + ": \"" + parsed.error + "\" does not say " + want);
                continue;
            }
            assertTrue(parsed.ok(), () -> text + ": " + parsed.error);
            SkillLine.Cond cond = parsed.line.conditions.get(0);
            assertEquals(expected.get("name").getAsString(), cond.ref.name, text);
            assertEquals(args(expected), cond.ref.args.values, text);
            assertEquals(flag(expected, "negate"), cond.negate, text);
            assertEquals(expected.get("action").getAsString(), cond.action, text);
            assertEquals(expected.has("actionArg") ? expected.get("actionArg").getAsString() : null, cond.actionArg, text);
        }
    }

    @Test
    void everyInlineCase() throws IOException {
        for (JsonElement element : cases().getAsJsonArray("inline")) {
            JsonObject expected = element.getAsJsonObject();
            List<String> want = new ArrayList<>();
            expected.getAsJsonArray("lines").forEach(line -> want.add(line.getAsString()));
            assertEquals(want, SkillParser.inline(expected.get("value").getAsString()), expected.get("value").getAsString());
        }
    }
}
