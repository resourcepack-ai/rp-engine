package ai.resourcepack.engine.core.emote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rig the engine bakes, checked for the two things that fail silently.
 *
 * <p>An element outside -16..32 drops the WHOLE model, not the element, and
 * the client says nothing; a bone whose model id does not match what the
 * store composes for it is a bone that spawns as a plain sheet of paper. Both
 * are exactly the class of mistake Studio's own verify script exists for, and
 * this is that script's half on this side.
 */
class RigBakerTest {

    private static RigBaker.Baked bake() {
        return RigBaker.bake(List.of(
                new RigBaker.Skin(RigBaker.DEFAULT_KEY, new byte[] {1, 2, 3}, RigGeometry.WIDE),
                new RigBaker.Skin("0123456789abcdef0123456789abcdef", new byte[] {4}, RigGeometry.SLIM)));
    }

    @Test
    void everyElementFitsTheModelBounds() {
        for (String variant : List.of(RigGeometry.WIDE, RigGeometry.SLIM)) {
            for (boolean jointed : new boolean[] {false, true}) {
                for (RigGeometry.Bone bone : RigGeometry.bones(variant, jointed)) {
                    JsonArray elements = RigGeometry.elements(bone, variant);
                    assertEquals(2, elements.size(), bone.key + " has a box and an overlay");
                    for (JsonElement element : elements) {
                        for (String corner : List.of("from", "to")) {
                            for (JsonElement value : element.getAsJsonObject().getAsJsonArray(corner)) {
                                double v = value.getAsDouble();
                                assertTrue(v >= -16 && v <= 32,
                                        bone.key + " " + corner + " " + v + " is outside -16..32");
                            }
                        }
                        JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
                        assertEquals(6, faces.size(), bone.key + " has six faces");
                    }
                }
            }
        }
    }

    @Test
    void aSlimArmIsAPixelNarrower() {
        JsonArray wide = RigGeometry.elements(RigGeometry.bones(RigGeometry.WIDE, false).get(2), RigGeometry.WIDE);
        JsonArray slim = RigGeometry.elements(RigGeometry.bones(RigGeometry.SLIM, false).get(2), RigGeometry.SLIM);
        double wideWidth = wide.get(0).getAsJsonObject().getAsJsonArray("to").get(0).getAsDouble()
                - wide.get(0).getAsJsonObject().getAsJsonArray("from").get(0).getAsDouble();
        double slimWidth = slim.get(0).getAsJsonObject().getAsJsonArray("to").get(0).getAsDouble()
                - slim.get(0).getAsJsonObject().getAsJsonArray("from").get(0).getAsDouble();
        assertEquals(4, wideWidth, 1e-9);
        assertEquals(3, slimWidth, 1e-9);
    }

    @Test
    void theHeadSitsOnTheShouldersAtTheOriginTheDirectorUses() {
        // Rig px (0,24,0) is the neck; a block up and centred it is (8,16,8).
        RigGeometry.Bone head = RigGeometry.bones(RigGeometry.WIDE, false).get(0);
        assertEquals(24, head.pivot[1], 1e-9);
        double[] model = RigGeometry.toModelPoint(head.pivot[0], head.pivot[1], head.pivot[2]);
        assertEquals(8, model[0], 1e-9);
        assertEquals(16, model[1], 1e-9);
        assertEquals(8, model[2], 1e-9);
        assertEquals(4, RigGeometry.rootPivot()[1], 1e-9, "the root turns about the hip, a block up");
    }

    @Test
    void everyBoneTheStoreCanAskForIsBaked() {
        RigBaker.Baked baked = bake();
        assertEquals(2, baked.players.size());
        for (Map.Entry<String, EmoteStore.PlayerRig> entry : baked.players.entrySet()) {
            EmoteStore.PlayerRig rig = entry.getValue();
            assertTrue(rig.jointed);
            for (String bone : RigGeometry.ALL_BONES) {
                for (String variant : java.util.Arrays.asList(RigGeometry.WIDE, RigGeometry.SLIM, null)) {
                    for (boolean jointed : new boolean[] {true, false}) {
                        if (!jointed && !RigGeometry.WHOLE_BONES.contains(bone)) {
                            continue;
                        }
                        String id = EmoteStore.boneItemId(rig, bone, variant, jointed);
                        assertTrue(baked.files.containsKey("assets/rpengine/models/block/" + id + ".json"),
                                "no model for " + id);
                        assertTrue(baked.files.containsKey("assets/rpengine/items/" + id + ".json"),
                                "no item definition for " + id);
                        assertTrue(NativeRigItems.isNative(id), id + " should read as native");
                    }
                }
            }
        }
    }

    @Test
    void theModelNamesTheSkinTextureInTheEngineNamespace() {
        RigBaker.Baked baked = bake();
        String prefix = RigBaker.prefixFor(RigBaker.DEFAULT_KEY);
        byte[] head = baked.files.get("assets/rpengine/models/block/" + prefix + "__head.json");
        assertNotNull(head);
        JsonObject model = JsonParser.parseString(new String(head, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("rpengine:block/" + prefix, model.getAsJsonObject("textures").get("skin").getAsString());
        assertTrue(baked.files.containsKey("assets/rpengine/textures/block/" + prefix + ".png"));
        Set<String> ids = baked.files.keySet();
        assertTrue(ids.stream().allMatch(id -> id.startsWith("assets/rpengine/")), "nothing lands outside rpengine");
    }

    @Test
    void manifestTablesHaveParentsBeforeChildren() {
        List<EmoteStore.Bone> jointed = RigGeometry.manifestJointedBones();
        assertEquals(10, jointed.size());
        for (int i = 0; i < jointed.size(); i++) {
            String parent = jointed.get(i).parent;
            if (parent == null) {
                continue;
            }
            boolean before = false;
            for (int j = 0; j < i; j++) {
                if (parent.equals(jointed.get(j).key)) {
                    before = true;
                }
            }
            assertTrue(before, jointed.get(i).key + "'s parent " + parent + " comes after it");
        }
        assertEquals(6, RigGeometry.manifestBones().size());
    }
}
