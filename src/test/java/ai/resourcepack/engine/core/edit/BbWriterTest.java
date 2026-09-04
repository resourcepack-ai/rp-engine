package ai.resourcepack.engine.core.edit;

import ai.resourcepack.engine.core.item.BbModel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writing an edited model back into the project it came from.
 *
 * <p>Almost every test here is a round trip rather than an assertion about the
 * file, and that is the point: the property this class has to have is that
 * {@code BbModel.convert(BbWriter.write(project, model)) == model}. Asserting
 * on the project's own JSON would pin an internal shape and would not notice a
 * bone whose index moved.
 */
class BbWriterTest {

    /** A one-pixel PNG, embedded the way Blockbench embeds one. */
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private static final byte[] PNG = Base64.getDecoder().decode(PNG_BASE64);

    /** A project with two textures, two cubes, two bones and an animation. */
    private static String project() {
        return "{"
                + "\"meta\": {\"format_version\": \"4.5\", \"model_format\": \"free\"},"
                + "\"name\": \"golem\","
                + "\"resolution\": {\"width\": 32, \"height\": 32},"
                + "\"display\": {\"head\": {\"scale\": [2, 2, 2]}},"
                + "\"textures\": ["
                + "  {\"name\": \"skin\", \"uuid\": \"tex-1\", \"uv_width\": 32, \"uv_height\": 32,"
                + "   \"source\": \"data:image/png;base64," + PNG_BASE64 + "\"},"
                + "  {\"name\": \"trim\", \"uuid\": \"tex-2\", \"uv_width\": 16, \"uv_height\": 16,"
                + "   \"source\": \"data:image/png;base64," + PNG_BASE64 + "\"}"
                + "],"
                + "\"elements\": ["
                + "  {\"name\": \"body\", \"uuid\": \"cube-1\", \"from\": [4, 0, 4], \"to\": [12, 10, 12],"
                + "   \"rotation\": [0, 22.5, 0], \"origin\": [8, 0, 8],"
                + "   \"faces\": {\"north\": {\"uv\": [0, 0, 16, 20], \"texture\": 0},"
                + "               \"up\": {\"uv\": [0, 0, 8, 8], \"texture\": 1, \"rotation\": 90}}},"
                + "  {\"name\": \"arm\", \"uuid\": \"cube-2\", \"from\": [12, 4, 6], \"to\": [14, 10, 10],"
                + "   \"faces\": {\"east\": {\"uv\": [0, 0, 4, 12], \"texture\": 0}}}"
                + "],"
                + "\"outliner\": ["
                + "  {\"name\": \"root\", \"uuid\": \"bone-1\", \"origin\": [8, 0, 8], \"children\": ["
                + "     \"cube-1\","
                + "     {\"name\": \"arm\", \"uuid\": \"bone-2\", \"origin\": [12, 10, 8],"
                + "      \"children\": [\"cube-2\"]}"
                + "  ]}"
                + "],"
                + "\"animations\": [{"
                + "  \"name\": \"wave\", \"loop\": \"loop\", \"length\": 2,"
                + "  \"animators\": {\"bone-2\": {\"keyframes\": ["
                + "     {\"channel\": \"rotation\", \"time\": 0, \"interpolation\": \"linear\","
                + "      \"data_points\": [{\"x\": 0, \"y\": 0, \"z\": 0}]},"
                + "     {\"channel\": \"rotation\", \"time\": 1, \"interpolation\": \"catmullrom\","
                + "      \"data_points\": [{\"x\": 0, \"y\": 0, \"z\": -40}]}"
                + "  ]}}"
                + "}]"
                + "}";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject read(byte[] project) {
        return BbModel.convert(project, "mypack", "golem").orElseThrow().model();
    }

    private static List<byte[]> art(int slots) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            out.add(PNG);
        }
        return out;
    }

    // ---- the round trip -----------------------------------------------------

    @Test
    void anUneditedModelComesBackUnchanged() {
        byte[] original = bytes(project());
        JsonObject model = read(original);

        byte[] written = BbWriter.write(original, model, art(2), "mypack", "golem").orElseThrow();

        assertEquals(model.toString(), read(written).toString());
    }

    @Test
    void writingTwiceProducesTheSameFile() {
        byte[] original = bytes(project());
        JsonObject model = read(original);

        byte[] once = BbWriter.write(original, model, art(2), "mypack", "golem").orElseThrow();
        byte[] twice = BbWriter.write(once, read(once), art(2), "mypack", "golem").orElseThrow();

        assertEquals(new String(once, StandardCharsets.UTF_8), new String(twice, StandardCharsets.UTF_8));
    }

    @Test
    void anEditedCubeComesBackEdited() {
        byte[] original = bytes(project());
        JsonObject model = read(original);
        JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        JsonArray moved = new JsonArray();
        moved.add(2);
        moved.add(1);
        moved.add(3);
        element.add("from", moved);

        byte[] written = BbWriter.write(original, model, art(2), "mypack", "golem").orElseThrow();

        JsonObject back = read(written).getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals("[2.0,1.0,3.0]", back.get("from").toString());
    }

    @Test
    void aFaceRepointedAtTheOtherTextureStaysThere() {
        byte[] original = bytes(project());
        JsonObject model = read(original);
        model.getAsJsonArray("elements").get(1).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("east")
                .addProperty("texture", "#1");

        byte[] written = BbWriter.write(original, model, art(2), "mypack", "golem").orElseThrow();

        assertEquals("#1", read(written).getAsJsonArray("elements").get(1).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("east").get("texture").getAsString());
    }

    /**
     * UVs live in the TEXTURE's own space, not the project's, and the two
     * textures here are different sizes on purpose. A single project-wide
     * scale would put the second face's crop in the wrong place, which is the
     * mistake the ModelEngine converter made on a live server.
     */
    @Test
    void uvsSurviveTexturesOfDifferentSizes() {
        byte[] original = bytes(project());
        JsonObject model = read(original);
        JsonObject faces = model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces");
        String north = faces.getAsJsonObject("north").get("uv").toString();
        String up = faces.getAsJsonObject("up").get("uv").toString();

        byte[] written = BbWriter.write(original, model, art(2), "mypack", "golem").orElseThrow();

        JsonObject back = read(written).getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces");
        assertEquals(north, back.getAsJsonObject("north").get("uv").toString());
        assertEquals(up, back.getAsJsonObject("up").get("uv").toString());
    }

    @Test
    void bonesAndTheirParentsSurvive() {
        byte[] original = bytes(project());
        JsonObject model = read(original);

        byte[] written = BbWriter.write(original, model, art(2), "mypack", "golem").orElseThrow();

        JsonArray groups = read(written).getAsJsonArray("groups");
        assertEquals(2, groups.size());
        assertEquals("root", groups.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(-1, groups.get(0).getAsJsonObject().get("parent").getAsInt());
        assertEquals("arm", groups.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals(0, groups.get(1).getAsJsonObject().get("parent").getAsInt());
    }

    @Test
    void animationsSurviveWithTheirKeyframes() {
        byte[] original = bytes(project());
        JsonObject model = read(original);

        byte[] written = BbWriter.write(original, model, art(2), "mypack", "golem").orElseThrow();

        JsonObject animation = read(written).getAsJsonArray("animations").get(0).getAsJsonObject();
        assertEquals("wave", animation.get("name").getAsString());
        assertEquals("loop", animation.get("mode").getAsString());
        JsonArray frames = animation.getAsJsonObject("animators").getAsJsonObject("g:1")
                .getAsJsonArray("rotation");
        assertEquals(2, frames.size());
        assertEquals("[0.0,0.0,-40.0]", frames.get(1).getAsJsonObject().get("value").toString());
        assertEquals("smooth", frames.get(1).getAsJsonObject().get("interpolation").getAsString());
    }

    /**
     * <strong>The case this class is most likely to get wrong.</strong> A
     * project's outliner has no index — the ORDER is the index — so a flat bone
     * list that is not in pre-order has to be renumbered before it is written,
     * along with every animator keyed on it. An editor that appends a new bone
     * with an earlier parent produces exactly such a list, and getting this
     * wrong attaches an animation to the wrong limb with nothing to say so.
     */
    @Test
    void aBoneListThatIsNotInPreOrderIsRenumbered() {
        byte[] original = bytes(project());
        JsonObject model = read(original);

        // Bones: 0 root(-1), 1 arm(0). Add a second root AFTER them, then a
        // child of the FIRST root — which puts the list out of pre-order.
        JsonArray groups = model.getAsJsonArray("groups");
        groups.add(bone("tail", -1));
        groups.add(bone("hand", 0));

        // The animation is keyed on "arm", which is index 1 today and will not
        // be after the renumber.
        JsonObject animators = model.getAsJsonArray("animations").get(0).getAsJsonObject()
                .getAsJsonObject("animators");
        assertTrue(animators.has("g:1"));

        byte[] written = BbWriter.write(original, model, art(2), "mypack", "golem").orElseThrow();

        JsonArray back = read(written).getAsJsonArray("groups");
        List<String> names = new ArrayList<>();
        for (JsonElement group : back) {
            names.add(group.getAsJsonObject().get("name").getAsString());
        }
        // Pre-order: root, then its children in order, then the other root.
        assertEquals(Arrays.asList("root", "arm", "hand", "tail"), names);

        // And the animator followed the bone rather than the number.
        JsonObject backAnimators = read(written).getAsJsonArray("animations").get(0)
                .getAsJsonObject().getAsJsonObject("animators");
        assertTrue(backAnimators.has("g:1"), "arm is still index 1 here");
        assertEquals(1, names.indexOf("arm"));
    }

    private static JsonObject bone(String name, int parent, int... children) {
        JsonObject group = new JsonObject();
        group.addProperty("name", name);
        JsonArray origin = new JsonArray();
        origin.add(8);
        origin.add(0);
        origin.add(8);
        group.add("origin", origin);
        JsonArray kids = new JsonArray();
        for (int child : children) {
            kids.add(child);
        }
        group.add("children", kids);
        group.addProperty("parent", parent);
        return group;
    }

    // ---- what it keeps and what it refuses ----------------------------------

    @Test
    void everythingTheEngineDoesNotReadIsKept() {
        byte[] original = bytes(project());

        byte[] written = BbWriter.write(original, read(original), art(2), "mypack", "golem").orElseThrow();

        JsonObject project = JsonParser.parseString(new String(written, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("4.5", project.getAsJsonObject("meta").get("format_version").getAsString());
        assertNotNull(project.getAsJsonObject("display"), "display settings are not ours to drop");
        assertEquals("skin", project.getAsJsonArray("textures").get(0).getAsJsonObject()
                .get("name").getAsString(), "a texture keeps its name");
    }

    @Test
    void newArtIsEmbedded() {
        byte[] original = bytes(project());

        byte[] written = BbWriter.write(original, read(original), art(2), "mypack", "golem").orElseThrow();

        String source = JsonParser.parseString(new String(written, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("textures").get(0).getAsJsonObject()
                .get("source").getAsString();
        assertTrue(source.startsWith("data:image/png;base64,"), source);
        assertEquals(PNG_BASE64, source.substring("data:image/png;base64,".length()));
    }

    @Test
    void aTextureAddedInTheEditorGetsASlotOfItsOwn() {
        byte[] original = bytes(project());
        JsonObject model = read(original);
        model.getAsJsonObject("textures").addProperty("2", "mypack:item/extra");
        model.getAsJsonArray("elements").get(1).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("east")
                .addProperty("texture", "#2");

        byte[] written = BbWriter.write(original, model, art(3), "mypack", "golem").orElseThrow();

        JsonObject project = JsonParser.parseString(new String(written, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(3, project.getAsJsonArray("textures").size());
        assertEquals("#2", read(written).getAsJsonArray("elements").get(1).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("east").get("texture").getAsString());
    }

    @Test
    void nothingIsWrittenForAModelWithNoGeometry() {
        JsonObject empty = new JsonObject();
        empty.add("textures", new JsonObject());
        empty.add("elements", new JsonArray());

        assertFalse(BbWriter.write(bytes(project()), empty, List.of(), "mypack", "golem").isPresent());
    }

    @Test
    void nothingIsWrittenWhenTheOriginalIsNotAProject() {
        Optional<byte[]> written = BbWriter.write(bytes("not json"), read(bytes(project())),
                art(2), "mypack", "golem");
        assertFalse(written.isPresent());
    }
}
