package ai.resourcepack.engine.core.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a model into the parts a server can move.
 *
 * <p>Every rule here is also a rule in studio's {@code Studio's rig builder}, and
 * the two have to agree: one animator plays both, so a difference shows up as
 * a model that animates correctly from a push and wrongly from a folder.
 */
class ModelRigsTest {

    private static JsonObject model(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** A cube. Index into {@code elements} is what an animator names it by. */
    private static String cube(int from) {
        return "{\"from\":[" + from + ",0,0],\"to\":[" + (from + 4) + ",4,4],\"faces\":{}}";
    }

    private static String bone(String name, float originY, String children) {
        return "{\"name\":\"" + name + "\",\"origin\":[8," + originY + ",8],\"children\":[" + children + "]}";
    }

    private static String rotate(String target) {
        return "\"" + target + "\":{\"rotation\":[{\"time\":0,\"value\":[0,0,0]},"
                + "{\"time\":1,\"value\":[0,90,0]}]}";
    }

    private static String animation(String animators) {
        return "{\"name\":\"spin\",\"length\":1,\"loop\":true,"
                + "\"triggers\":[{\"type\":\"loop\"}],\"animators\":{" + animators + "}}";
    }

    private static JsonObject animated() {
        return model("{\"elements\":[" + cube(0) + "," + cube(4) + "," + cube(8) + "],"
                + "\"groups\":[" + bone("arm", 6, "0") + "," + bone("still", 0, "2") + "],"
                + "\"animations\":[" + animation(rotate("g:0")) + "]}");
    }

    // ---- when there is nothing to move ---------------------------------

    @Test
    void aModelWithNoAnimationsHasNoRig() {
        assertTrue(ModelRigs.compute("mypack:chair",
                model("{\"elements\":[" + cube(0) + "]}")).isEmpty());
    }

    @Test
    void anAnimatorWithNoKeyframesIsNotAnimated() {
        // What Blockbench leaves behind when the last keyframe on a bone is
        // deleted. Treating it as animated gives that bone a part of its own
        // that never moves.
        Optional<ModelRigs.Rig> rig = ModelRigs.compute("mypack:chair",
                model("{\"elements\":[" + cube(0) + "],"
                        + "\"groups\":[" + bone("arm", 6, "0") + "],"
                        + "\"animations\":[" + animation("\"g:0\":{\"rotation\":[]}") + "]}"));
        assertTrue(rig.isEmpty());
    }

    // ---- the split ------------------------------------------------------

    @Test
    void anAnimatedBoneBecomesItsOwnPart() {
        List<ModelRigs.Part> parts = ModelRigs.compute("mypack:golem", animated()).orElseThrow().parts();

        assertEquals(2, parts.size(), "one moving part and one still remainder");
        assertEquals(List.of(0), parts.get(0).elements());
        assertEquals("g:0", parts.get(0).program().get(0).target());
        assertArrayEqualsish(new float[]{8f, 6f, 8f}, parts.get(0).program().get(0).pivot());
    }

    @Test
    void everythingUnanimatedRidesAlongAsOneStillPart() {
        List<ModelRigs.Part> parts = ModelRigs.compute("mypack:golem", animated()).orElseThrow().parts();

        ModelRigs.Part remainder = parts.get(parts.size() - 1);
        assertTrue(remainder.program().isEmpty(), "the remainder does not move");
        // Cube 2 is in a bone, but a bone nothing animates — so it is still,
        // and it rides with the loose cube 1 rather than getting a part.
        assertEquals(List.of(1, 2), remainder.elements());
    }

    @Test
    void aCubeWithItsOwnAnimatorComposesInsideItsBone() {
        // The reason a program is a list rather than one step: the cube's
        // rotation happens in the space its bone has already moved.
        ModelRigs.Rig rig = ModelRigs.compute("mypack:golem",
                model("{\"elements\":[" + cube(0) + "],"
                        + "\"groups\":[" + bone("arm", 6, "0") + "],"
                        + "\"animations\":[" + animation(rotate("g:0") + "," + rotate("0")) + "]}"))
                .orElseThrow();

        List<ModelRigs.Step> program = rig.parts().get(0).program();
        assertEquals(2, program.size());
        assertEquals("g:0", program.get(0).target(), "the bone step is outermost");
        assertEquals("0", program.get(1).target());
    }

    @Test
    void aLooseAnimatedCubeTurnsAboutItsOwnCentre() {
        ModelRigs.Rig rig = ModelRigs.compute("mypack:golem",
                model("{\"elements\":[" + cube(0) + "],"
                        + "\"animations\":[" + animation(rotate("0")) + "]}")).orElseThrow();

        // from [0,0,0] to [4,4,4]: a cube nobody gave a rotation origin has
        // never been asked where its pivot is, and its centre is the only
        // answer that does not translate it.
        assertArrayEqualsish(new float[]{2f, 2f, 2f}, rig.parts().get(0).program().get(0).pivot());
    }

    @Test
    void aCubeWithARotationOriginTurnsAboutThat() {
        ModelRigs.Rig rig = ModelRigs.compute("mypack:golem",
                model("{\"elements\":[{\"from\":[0,0,0],\"to\":[4,4,4],\"faces\":{},"
                        + "\"rotation\":{\"origin\":[1,2,3],\"axis\":\"y\",\"angle\":22.5}}],"
                        + "\"animations\":[" + animation(rotate("0")) + "]}")).orElseThrow();

        assertArrayEqualsish(new float[]{1f, 2f, 3f}, rig.parts().get(0).program().get(0).pivot());
    }

    @Test
    void noCubeIsDrawnTwice() {
        // A cube claimed by a bone must not also appear in the remainder, or
        // it is rendered by two displays and doubles up in game.
        ModelRigs.Rig rig = ModelRigs.compute("mypack:golem", animated()).orElseThrow();

        List<Integer> all = rig.parts().stream().flatMap(part -> part.elements().stream()).toList();
        assertEquals(all.size(), all.stream().distinct().count(), "a cube is in exactly one part");
        assertEquals(3, all.size(), "and every cube is in one");
    }

    // ---- what the parts are called --------------------------------------

    @Test
    void partsAreNamedAfterTheModelAndNumberedFromZero() {
        List<ModelRigs.Part> parts = ModelRigs.compute("mypack:golem", animated()).orElseThrow().parts();

        assertEquals("mypack:golem__part0", parts.get(0).item());
        assertEquals("mypack:golem__part1", parts.get(1).item());
        assertEquals(1, ModelRigs.partIndexOf("mypack:golem__part1"));
        assertEquals(-1, ModelRigs.partIndexOf("mypack:golem"));
    }

    // ---- the part's own model -------------------------------------------

    @Test
    void aPartModelCarriesOnlyItsOwnCubes() {
        JsonObject source = animated();
        source.add("textures", JsonParser.parseString("{\"0\":\"mypack:item/golem\"}"));
        ModelRigs.Rig rig = ModelRigs.compute("mypack:golem", source).orElseThrow();

        JsonObject part = ModelRigs.partModel(source, rig.parts().get(0));
        assertEquals(1, part.getAsJsonArray("elements").size());
        assertEquals("mypack:item/golem",
                part.getAsJsonObject("textures").get("0").getAsString());
        // Read by the server, meaningless to the client, and the largest thing
        // in the file — a copy in every part is pure weight in the download.
        assertFalse(part.has("animations"));
        assertFalse(part.has("groups"));
    }

    // ---- the manifest ----------------------------------------------------

    @Test
    void theManifestIsTheShapeTheStoreReads() {
        // Deliberately the same JSON a studio push sends, so the store's
        // replace-a-whole-pack rule applies to a content folder for free.
        ModelRigs.Rig rig = ModelRigs.compute("mypack:golem", animated()).orElseThrow();
        String json = ModelRigs.manifest("content-folder", Map.of("mypack:golem", rig)).toString();

        RigStore store = new RigStore(new java.io.File("build/tmp/does-not-exist"));
        assertTrue(store.updateFromJson(json).ok());

        RigStore.Rig stored = store.get("mypack:golem");
        assertEquals(2, stored.parts.size());
        assertEquals("mypack:golem__part0", stored.parts.get(0).item);
        assertEquals("g:0", stored.parts.get(0).program.get(0).target);
        assertEquals(1, stored.animations.size());
        assertEquals("spin", stored.animations.get(0).name);
        assertTrue(stored.animations.get(0).loop);
    }

    @Test
    void areloadReplacesEveryAuthoredRigRatherThanMergingOverThem() {
        // A model whose animation somebody deleted has to STOP being animated:
        // placement branches on whether a rig exists, so a stale entry keeps
        // spawning the old one.
        RigStore store = new RigStore(new java.io.File("build/tmp/does-not-exist"));
        ModelRigs.Rig rig = ModelRigs.compute("mypack:golem", animated()).orElseThrow();
        store.updateFromJson(ModelRigs.manifest("content-folder", Map.of("mypack:golem", rig)).toString());

        store.updateFromJson(ModelRigs.manifest("content-folder", Map.of()).toString());
        assertSame(null, store.get("mypack:golem"));
    }

    private static void assertArrayEqualsish(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 0.0001f, "component " + i);
        }
    }
}
