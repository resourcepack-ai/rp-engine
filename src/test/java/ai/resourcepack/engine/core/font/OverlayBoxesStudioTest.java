package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.OverlayInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OverlayBoxes} against Studio's own layout of the same containers.
 *
 * <p>Studio lays a container out with every label as wide as it is written
 * and ships each run at that {@code x}; this engine lays it out again from
 * what the runs came to. With nothing filled in the two have to agree to the
 * pixel, or a container jumps the moment the engine takes over. The fixture is
 * what Studio's linker sends for its sample objects, written by Studio's own
 * check of the same layout; regenerate it there when either end changes.
 */
class OverlayBoxesStudioTest {

    @Test
    void laysOutEveryStudioSampleExactlyWhereStudioDid() throws Exception {
        JsonObject fixture;
        try (var in = getClass().getResourceAsStream("/shader-boxes/studio.json")) {
            assertNotNull(in, "shader-boxes/studio.json");
            fixture = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int checked = 0;
        for (JsonElement element : fixture.getAsJsonArray("overlays")) {
            JsonObject overlay = element.getAsJsonObject();
            String id = overlay.get("id").getAsString();
            JsonArray runs = overlay.getAsJsonArray("runs");
            int[] ink = new int[runs.size()];
            boolean[] drawn = new boolean[runs.size()];
            int[] x = new int[runs.size()];
            for (int i = 0; i < runs.size(); i++) {
                JsonObject run = runs.get(i).getAsJsonObject();
                x[i] = run.get("x").getAsInt();
                drawn[i] = true;
                if (run.has("bar")) {
                    ink[i] = run.get("bar").getAsInt();
                } else if (run.has("advance")) {
                    ink[i] = run.get("advance").getAsInt() - 1;
                } else {
                    ink[i] = TextWidth.of(run.get("text").getAsString()) - 1;
                }
            }
            List<OverlayInfo.Box> boxes = new ArrayList<>();
            for (JsonElement raw : overlay.getAsJsonArray("boxes")) {
                JsonObject box = raw.getAsJsonObject();
                List<OverlayInfo.Box.Item> items = new ArrayList<>();
                for (JsonElement rawItem : box.getAsJsonArray("items")) {
                    JsonObject item = rawItem.getAsJsonObject();
                    List<Integer> itemRuns = new ArrayList<>();
                    item.getAsJsonArray("runs").forEach(r -> itemRuns.add(r.getAsInt()));
                    items.add(new OverlayInfo.Box.Item(itemRuns, item.get("box").getAsInt()));
                }
                boxes.add(new OverlayInfo.Box(box.get("x").getAsInt(), box.get("pivot").getAsDouble(),
                        box.get("row").getAsBoolean(), box.get("gap").getAsInt(), box.get("pad").getAsInt(),
                        box.get("min").getAsInt(), box.get("align").getAsDouble(),
                        box.get("background").getAsInt(), items));
            }
            OverlayBoxes.Layout layout = OverlayBoxes.layout(boxes, ink, drawn);
            for (int i = 0; i < runs.size(); i++) {
                Integer start = layout.starts()[i];
                if (start != null) {
                    assertEquals(x[i], start.intValue(), id + " run " + i + " " + runs.get(i));
                    checked++;
                }
            }
            for (int b = 0; b < boxes.size(); b++) {
                int bg = boxes.get(b).background();
                if (bg < 0) {
                    continue;
                }
                assertTrue(layout.shown()[b], id + " box " + b + " is shown");
                assertEquals(x[bg], layout.left()[b], id + " box " + b + " left");
                assertEquals(ink[bg], layout.width()[b], id + " box " + b + " width");
            }
        }
        assertTrue(checked > 10, "checked " + checked + " runs");
    }
}
