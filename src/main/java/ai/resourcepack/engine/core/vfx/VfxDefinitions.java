package ai.resourcepack.engine.core.vfx;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.VfxInfo;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A content folder's {@code vfx/}: effects this server's own pack draws, with
 * or without Studio.
 *
 * <p>Each top-level key of a file is one effect, and it is one of three things:
 *
 * <pre>
 * frost:
 *   preset: frost_nova         # one of the presets in this jar (/rp vfx presets)
 *   name: Frost Nova           # what /rp vfx calls it; the preset's name if absent
 *
 * burst:
 *   file: vfx/burst.json       # Studio's JSON for one
 *
 * spark:
 *   recipe:                    # the same JSON, inline (JSON is YAML)
 *     duration: 30
 *     layers: [...]
 * </pre>
 *
 * <p>A {@code .json} file in {@code vfx/} that is a recipe is also one effect,
 * named after the file. Every recipe is cleaned by Studio's rules
 * ({@link VfxRecipe#clean}), so what it changed is reported here and the
 * effect plays as it would in Studio's preview.
 */
public final class VfxDefinitions {

    /** What the files said, as the parse understood it. */
    public record Result(Map<ContentId, VfxEntry> effects, List<Diagnostic> diagnostics) {
    }

    private VfxDefinitions() {
    }

    /**
     * @param readFile a path relative to the content folder to its text, or null
     */
    public static Result parse(LoadReport loaded, Function<String, String> readFile) {
        Map<ContentId, VfxEntry> effects = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        VfxData data = VfxData.get();
        Gson gson = new Gson();
        for (ContentDefinition definition : loaded.definitions(ContentKind.VFX)) {
            DefinitionNode body = definition.body();
            String origin = definition.origin();
            String where = definition.id().toString();
            Optional<String> named = body.string("name").map(String::trim).filter(s -> !s.isEmpty());
            Optional<String> presetKey = body.string("preset").map(String::trim);
            JsonElement raw;
            String preset = null;
            String name;
            if (presetKey.isPresent()) {
                Optional<VfxData.Preset> found = data.preset(presetKey.get());
                if (found.isEmpty()) {
                    diagnostics.add(Diagnostic.error(origin, where, "preset: " + presetKey.get()
                            + " is not one of the presets. /rp vfx presets lists them. Skipped."));
                    continue;
                }
                raw = found.get().recipe();
                preset = found.get().key();
                name = named.orElse(found.get().name());
            } else if (body.has("recipe")) {
                raw = gson.toJsonTree(body.raw("recipe"));
                name = named.orElse(definition.id().path());
            } else if (body.string("file").isPresent()) {
                String file = body.string("file").get().trim();
                String text = readFile == null ? null : readFile.apply(file);
                if (text == null || text.isBlank()) {
                    diagnostics.add(Diagnostic.error(origin, where, "file: " + file
                            + " is not a file in the content folder. Skipped."));
                    continue;
                }
                try {
                    raw = JsonParser.parseString(text);
                } catch (JsonParseException e) {
                    diagnostics.add(Diagnostic.error(origin, where, "file: " + file + " is not JSON: "
                            + e.getMessage() + " Skipped."));
                    continue;
                }
                name = named.orElse(definition.id().path());
            } else {
                diagnostics.add(Diagnostic.error(origin, where,
                        "A VFX needs preset: (one of /rp vfx presets), recipe: or file:. Skipped."));
                continue;
            }
            VfxRecipe recipe = VfxRecipe.clean(raw);
            if (!recipe.drawable()) {
                diagnostics.add(Diagnostic.error(origin, where, "Nothing in it can be drawn, so it was left out: "
                        + String.join("; ", recipe.problems())));
                continue;
            }
            if (!recipe.problems().isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where, "Cleaned the way Studio cleans a recipe: "
                        + String.join("; ", recipe.problems())));
            }
            effects.put(definition.id(), new VfxEntry(
                    new VfxInfo(definition.id(), name, recipe.duration(), VfxInfo.Source.CONTENT, preset), recipe));
        }
        return new Result(Map.copyOf(effects), List.copyOf(diagnostics));
    }
}
