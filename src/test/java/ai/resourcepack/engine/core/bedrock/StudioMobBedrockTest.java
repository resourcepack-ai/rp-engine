package ai.resourcepack.engine.core.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mob Studio pushed, as Studio's {@code .mcpack} draws it for a Bedrock
 * player: {@code bedrock/studio-mob.json} is what Studio's
 * {@code scripts/gen-mob-bedrock.mjs} writes from its own pack builder.
 * {@link BedrockMobs} swaps such a mob's body to Studio's entity by name and
 * plays its animations by name, so every name it asks for has to be one
 * Studio's pack defines, and the turn it expects baked in has to be the one
 * {@link BedrockContent} bakes into the server's own.
 */
class StudioMobBedrockTest {

    private static JsonObject fixture() throws IOException {
        try (InputStream in = StudioMobBedrockTest.class.getResourceAsStream("/bedrock/studio-mob.json")) {
            assertNotNull(in, "bedrock/studio-mob.json is missing: run studio's scripts/gen-mob-bedrock.mjs");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject description(JsonObject entity) {
        return entity.getAsJsonObject("minecraft:client_entity").getAsJsonObject("description");
    }

    @Test
    void theEngineFindsTheMobEntityAtTheModelsSlot() throws IOException {
        JsonObject f = fixture();
        String modelId = f.get("modelId").getAsString();
        int slot = f.get("slot").getAsInt();
        // The slot is read off rpai_items.json, keyed by the model id the mob's rig carries.
        JsonObject item = f.getAsJsonObject("items").getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(modelId, item.get("model").getAsString());
        assertEquals(slot, item.get("slot").getAsInt());
        assertTrue(slot >= 1 && slot <= StudioMobNames.POOL);
        assertEquals(StudioMobNames.mobIdentifier(slot), description(f.getAsJsonObject("mobEntity")).get("identifier").getAsString());
        // Not the placed entity's id, which the bridge spawns for a placed model.
        assertFalse(description(f.getAsJsonObject("placedEntity")).get("identifier").getAsString()
                .equals(StudioMobNames.mobIdentifier(slot)));
    }

    @Test
    void everyAnimationTheEnginePlaysIsOneTheEntityHas() throws IOException {
        JsonObject f = fixture();
        String modelId = f.get("modelId").getAsString();
        JsonObject listed = description(f.getAsJsonObject("mobEntity")).getAsJsonObject("animations");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, JsonElement> e : listed.entrySet()) {
            ids.add(e.getValue().getAsString());
        }
        JsonObject defined = f.getAsJsonObject("animations").getAsJsonObject("animations");
        for (JsonElement name : f.getAsJsonArray("animationNames")) {
            String id = StudioMobNames.animationId(modelId, name.getAsString());
            assertTrue(ids.contains(id), () -> "the mob entity does not list " + id);
            assertTrue(defined.has(id), () -> "the pack does not define " + id);
        }
        String rest = StudioMobNames.restAnimation(modelId);
        assertTrue(ids.contains(rest), "the mob entity does not list its rest animation");
        assertTrue(f.getAsJsonObject("restAnimation").getAsJsonObject("animations").has(rest));
        // A mob plays one state at a time: nothing starts on its own.
        assertFalse(description(f.getAsJsonObject("mobEntity")).has("scripts"));
    }

    @Test
    void theTurnIsBakedAsTheServersOwnMobsAre() throws IOException {
        JsonObject f = fixture();
        float turn = f.get("turn").getAsFloat();
        JsonObject turned = f.getAsJsonObject("turnedGeometry");
        String id = turned.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonObject("description").get("identifier").getAsString();
        assertEquals(description(f.getAsJsonObject("mobEntity")).getAsJsonObject("geometry").get("default").getAsString(), id);
        JsonObject engine = BedrockContent.turnedGeometry(f.getAsJsonObject("geometry"), id, turn);
        assertEquals(normalized(engine), normalized(turned));
    }

    /** Numbers compared as numbers: Gson writes -270 as -270.0 where JavaScript writes -270. */
    private static JsonElement normalized(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (java.util.Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                out.add(entry.getKey(), normalized(entry.getValue()));
            }
            return out;
        }
        if (e.isJsonArray()) {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            e.getAsJsonArray().forEach(v -> out.add(normalized(v)));
            return out;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return new com.google.gson.JsonPrimitive(e.getAsDouble());
        }
        return e;
    }
}
