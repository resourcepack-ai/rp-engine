package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.emote.AuthoredEmotes;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BetterModel's plugin folder, dropped into the content folder as it is:
 * {@code models/} become items that wear them, {@code players/} animations
 * become emotes.
 */
class BetterModelTest {

    @TempDir
    Path content;

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** The smallest Blockbench project with one cube. */
    private static String model() {
        return """
                {"meta": {"format_version": "5.0", "model_format": "free"},
                 "resolution": {"width": 16, "height": 16},
                 "elements": [{"name": "cube", "from": [0,0,0], "to": [16,16,16], "origin": [0,0,0],
                   "faces": {}, "uuid": "e1"}],
                 "outliner": [{"name": "body", "origin": [0,0,0], "uuid": "g1", "children": ["e1"]}],
                 "textures": []}
                """;
    }

    /** A player rig with one animation touching the bones BetterModel tags. */
    private static String player() {
        return """
                {"meta": {"format_version": "5.0", "model_format": "free"},
                 "elements": [], "outliner": [],
                 "animations": [{"name": "wave", "loop": "loop", "length": 1.0, "animators": {
                   "a": {"name": "pra_right_arm", "keyframes": [
                     {"channel": "rotation", "time": 0.5, "interpolation": "catmullrom",
                      "data_points": [{"x": "-160", "y": "0", "z": "-20"}]},
                     {"channel": "rotation", "time": 0, "data_points": [{"x": 0, "y": 0, "z": 0}]}]},
                   "b": {"name": "pc_chest", "keyframes": [
                     {"channel": "rotation", "time": 0, "data_points": [{"x": "-10", "y": "0", "z": "0"}]}]},
                   "c": {"name": "pw_waist", "keyframes": [
                     {"channel": "rotation", "time": 0, "data_points": [{"x": "-5", "y": "0", "z": "0"}]}]},
                   "d": {"name": "player_root", "keyframes": [
                     {"channel": "position", "time": 0, "data_points": [{"x": 0, "y": "math.sin(q.anim_time)", "z": 0}]}]},
                   "e": {"name": "pri_right_item", "keyframes": [
                     {"channel": "rotation", "time": 0, "data_points": [{"x": 0, "y": 0, "z": 0}]}]}
                 }}]}
                """;
    }

    @Test
    void theirPluginFolderLoadsAsItIs() throws IOException {
        write("bettermodel/config.yml", "item: leather_horse_armor\nnamespace: bettermodel\n");
        write("bettermodel/models/knight.bbmodel", model());
        write("bettermodel/models/bosses/dragon.bbmodel", model());
        write("bettermodel/players/steve.bbmodel", player());
        write("bettermodel/build/assets/bettermodel/models/x.json", "{}");

        LoadReport report = new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
        assertTrue(report.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
                report.diagnostics().toString());

        var items = ItemDefinitions.parse(report).items();
        ItemInfo dragon = items.get(ContentId.parse("bettermodel:dragon").orElseThrow());
        assertEquals("bosses/dragon", dragon.model().orElseThrow(), "found where it is, under its subfolder");
        assertTrue(items.containsKey(ContentId.parse("bettermodel:knight").orElseThrow()));

        AuthoredEmotes.Result emotes = AuthoredEmotes.parse(report);
        assertEquals(1, report.definitions(ContentKind.EMOTE).size());
        var wave = report.definitions(ContentKind.EMOTE).get(0);
        assertEquals("wave", wave.id().path(), "steve's animations are named by the animation alone");
        var animators = wave.body().node("animators").orElseThrow();
        assertTrue(animators.node("rightArm").isPresent());
        // The chest is the most specific torso bone that moves.
        assertEquals(-10.0, ((Number) ((java.util.List<?>) ((java.util.Map<?, ?>) ((java.util.List<?>)
                animators.node("body").orElseThrow().raw("rotation")).get(0)).get("value")).get(0)).doubleValue());
        assertTrue(wave.body().node("root").isPresent(), "player_root moves the whole figure");
        assertTrue(emotes.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
                emotes.diagnostics().toString());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.message().contains("Molang")));
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.message().contains("pri_right_item")));
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.message().contains("hip, waist and chest")));
    }
}
