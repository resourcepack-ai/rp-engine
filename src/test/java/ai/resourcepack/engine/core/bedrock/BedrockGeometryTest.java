package ai.resourcepack.engine.core.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockGeometryTest {

    private static BufferedImage solid(int size, int argb) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) image.setRGB(x, y, argb);
        return image;
    }

    private static JsonObject model(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final String CHAIR = """
        {"textures":{"0":"mypack:item/wood","1":"minecraft:block/stone"},
         "elements":[
           {"name":"seat","from":[2,6,2],"to":[14,8,14],
            "faces":{"north":{"texture":"#0","uv":[0,0,12,2]},"up":{"texture":"#0"},"down":{"texture":"#1"}}},
           {"name":"lever","from":[7,8,7],"to":[9,14,9],"rotation":{"axis":"z","angle":22.5,"origin":[8,8,8]},
            "faces":{"east":{"texture":"#1"}}},
           {"name":"tilt","from":[0,0,0],"to":[2,2,2],"free_rotation":{"origin":[1,1,1],"angles":[30,0,0]},
            "faces":{"south":{"texture":"#0"}}}
         ],
         "groups":[{"name":"Lever Arm","origin":[8,8,8],"children":[1]}],
         "animations":[{"name":"Pull","length":1,"loop":true,
           "animators":{"g:0":{"rotation":[{"time":0,"value":[0,0,0]},{"time":1,"value":[10,20,30],"interpolation":"smooth"}],
                               "position":[{"time":0,"value":[1,2,3]}]}}}]}
        """;

    @Test
    void aChairBecomesBedrockGeometryWithBlockbenchsCoordinates() {
        BedrockGeometry.Converted out = BedrockGeometry.convert("rpengine:model_3", "mypack_chair", model(CHAIR),
                ref -> ref.equals("mypack:item/wood") ? solid(16, 0xFF885522) : null).orElseThrow();

        JsonObject geo = out.geometry().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        assertEquals("geometry.rpe.mypack_chair", geo.getAsJsonObject("description").get("identifier").getAsString());
        JsonArray bones = geo.getAsJsonArray("bones");
        assertEquals("lever_arm", bones.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("root", bones.get(1).getAsJsonObject().get("name").getAsString());

        // Seat: origin = [8 - to.x, from.y, from.z - 8], size = to - from.
        JsonObject seat = bones.get(1).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals("[-6.0,6.0,-6.0]", seat.get("origin").toString());
        assertEquals("[12.0,2.0,12.0]", seat.get("size").toString());
        // Two textures, so a 2x1 atlas of 16px tiles; north's uv lands in the first.
        assertEquals(32, out.atlas().getWidth());
        assertEquals("[0.0,0.0]", seat.getAsJsonObject("uv").getAsJsonObject("north").get("uv").toString());
        // Up and down flip both axes.
        assertEquals("[-12.0,-12.0]", seat.getAsJsonObject("uv").getAsJsonObject("up").get("uv_size").toString());
        // The stone the pack does not ship is the missing-texture tile, not a hole.
        assertEquals(0xFFF800F8, out.atlas().getRGB(16, 0));

        // A z turn keeps its sign; the pivot mirrors x.
        JsonObject lever = bones.get(0).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals("[0.0,0.0,22.5]", lever.get("rotation").toString());
        assertEquals("[0.0,8.0,0.0]", lever.get("pivot").toString());

        // A one-axis free rotation is exactly that axis, negated like any x turn.
        JsonObject tilt = bones.get(1).getAsJsonObject().getAsJsonArray("cubes").get(1).getAsJsonObject();
        assertEquals("[-30.0,0.0,0.0]", tilt.get("rotation").toString());

        JsonObject animation = out.animations().getAsJsonObject("animations")
                .getAsJsonObject("animation.rpe.mypack_chair.pull");
        assertTrue(animation.get("loop").getAsBoolean());
        JsonObject channels = animation.getAsJsonObject("bones").getAsJsonObject("lever_arm");
        assertEquals("[-10.0,-20.0,30.0]", channels.getAsJsonObject("rotation").getAsJsonObject("1.0").get("post").toString());
        assertEquals("[-1.0,2.0,3.0]", channels.getAsJsonObject("position").get("0.0").toString());

        JsonObject entity = out.entity().getAsJsonObject("minecraft:client_entity").getAsJsonObject("description");
        assertEquals("rpengine:model_3", entity.get("identifier").getAsString());
        assertEquals("animation.rpe.mypack_chair.pull", entity.getAsJsonObject("animations").get("pull").getAsString());
        assertEquals("[\"pull\"]", entity.getAsJsonObject("scripts").get("animate").toString());
    }

    @Test
    void aModelWithoutCubesConvertsToNothing() {
        assertFalse(BedrockGeometry.convert("x", "y", model("{\"elements\":[]}"), ref -> null).isPresent());
    }

    @Test
    void aStaticModelHasNoAnimationFile() {
        String json = "{\"textures\":{\"0\":\"a:b\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
                + "\"faces\":{\"north\":{\"texture\":\"#0\"}}}]}";
        BedrockGeometry.Converted out = BedrockGeometry.convert("x", "y", model(json), ref -> solid(16, -1)).orElseThrow();
        assertNull(out.animations());
        assertNotNull(out.geometry());
    }

    @Test
    void aCompoundTurnIsTheSameRotationInBedrocksOrder() {
        float[] zyx = BedrockGeometry.eulerXyzToZyx(new float[] {30, 45, 60});
        // Rebuild both matrices and compare: Rx(30)Ry(45)Rz(60) == Rz(z)Ry(y)Rx(x).
        double[][] xyz = mul(mul(rx(30), ry(45)), rz(60));
        double[][] back = mul(mul(rz(zyx[2]), ry(zyx[1])), rx(zyx[0]));
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) assertEquals(xyz[i][j], back[i][j], 1e-5);
        // And a single axis is untouched.
        float[] single = BedrockGeometry.eulerXyzToZyx(new float[] {0, 22.5f, 0});
        assertEquals(22.5f, single[1], 1e-4);
        assertEquals(0f, single[0], 1e-4);
    }

    private static double[][] rx(double d) {
        double r = Math.toRadians(d);
        return new double[][] {{1, 0, 0}, {0, Math.cos(r), -Math.sin(r)}, {0, Math.sin(r), Math.cos(r)}};
    }

    private static double[][] ry(double d) {
        double r = Math.toRadians(d);
        return new double[][] {{Math.cos(r), 0, Math.sin(r)}, {0, 1, 0}, {-Math.sin(r), 0, Math.cos(r)}};
    }

    private static double[][] rz(double d) {
        double r = Math.toRadians(d);
        return new double[][] {{Math.cos(r), -Math.sin(r), 0}, {Math.sin(r), Math.cos(r), 0}, {0, 0, 1}};
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) c[i][j] += a[i][k] * b[k][j];
        return c;
    }
}
