package ai.resourcepack.engine.core.textfx;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A content folder's {@code text-effects/}: effects this server's own pack
 * draws, with or without Studio.
 *
 * <p>Each top-level key of a file is one effect, and it is one of three things:
 *
 * <pre>
 * fire:
 *   preset: blaze            # one of the presets in this jar (/rp effects presets)
 *
 * wobble:
 *   name: Wobbly             # what /rp effects calls it; the key if absent
 *   pad: [0, 3, 0, 3]        # room it draws into: left, top, right, bottom
 *   glsl: |
 *     vec4 effect(Fx f) {
 *         float a = G(f.p - vec2(0.0, sin(f.t * 4.0 - f.o.x * 0.4) * 1.5));
 *         return vec4(vec3(1.0), a);
 *     }
 *
 * shimmer:
 *   file: mypack/text-effects/shimmer.glsl   # the same GLSL, in a file of its own
 * </pre>
 *
 * <p><strong>Code is held to Studio's rules and nothing more</strong>: there
 * is no compiler on a server, and a text shader that does not compile does not
 * degrade, the client refuses the whole pack. So the rules here refuse what
 * would hang a GPU or reach past the effect, and the docs say to try code in
 * Studio's preview, which does compile it, before shipping it.
 */
public final class TextEffectDefinitions {

    /**
     * One effect as a file wrote it.
     *
     * @param preset the preset it came from, if it did
     */
    public record Authored(ContentId id, String name, String glsl, int[] pad, Optional<String> preset) {
    }

    /** What the files said, as the parse understood it. */
    public record Result(Map<ContentId, Authored> effects, List<Diagnostic> diagnostics) {
    }

    private TextEffectDefinitions() {
    }

    /**
     * @param readFile a path relative to the content folder to its text, or null
     */
    public static Result parse(LoadReport loaded, Function<String, String> readFile) {
        Map<ContentId, Authored> effects = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        TextEffectShaders shaders = TextEffectShaders.get();
        TextEffectPresets presets = TextEffectPresets.get();
        for (ContentDefinition definition : loaded.definitions(ContentKind.TEXT_EFFECT)) {
            DefinitionNode body = definition.body();
            String origin = definition.origin();
            String where = definition.id().toString();
            String name = body.string("name").map(String::trim).filter(s -> !s.isEmpty())
                    .orElse(definition.id().path());

            String glsl;
            int[] pad;
            Optional<String> preset = body.string("preset").map(String::trim);
            if (preset.isPresent()) {
                Optional<TextEffectPresets.Preset> found = presets.find(preset.get());
                if (found.isEmpty()) {
                    diagnostics.add(Diagnostic.error(origin, where, "preset: " + preset.get()
                            + " is not one of the presets. /rp effects presets lists them. Skipped."));
                    continue;
                }
                glsl = found.get().glsl();
                pad = found.get().pad();
                preset = Optional.of(found.get().key());
            } else {
                Optional<String> inline = body.string("glsl");
                Optional<String> file = body.string("file");
                if (inline.isPresent()) {
                    glsl = inline.get();
                } else if (file.isPresent()) {
                    glsl = readFile == null ? null : readFile.apply(file.get().trim());
                    if (glsl == null || glsl.isBlank()) {
                        diagnostics.add(Diagnostic.error(origin, where, "file: " + file.get()
                                + " is not a file in the content folder. Skipped."));
                        continue;
                    }
                } else {
                    diagnostics.add(Diagnostic.error(origin, where,
                            "A Text Effect needs preset: (one of /rp effects presets), glsl: or file:. Skipped."));
                    continue;
                }
                pad = pad(body, shaders.maxPad(), origin, where, diagnostics);
                List<String> problems = shaders.problems(glsl);
                if (!problems.isEmpty()) {
                    // Refused, not shipped broken: one effect that does not
                    // compile would take the whole pack down with it.
                    diagnostics.add(Diagnostic.error(origin, where, "Its code breaks the rules every Text Effect "
                            + "is held to, so it was left out: " + String.join(" ", problems)));
                    continue;
                }
            }
            if (body.has("pad") && preset.isPresent()) {
                pad = pad(body, shaders.maxPad(), origin, where, diagnostics);
            }
            effects.put(definition.id(), new Authored(definition.id(), name, glsl, pad, preset));
        }
        return new Result(Map.copyOf(effects), List.copyOf(diagnostics));
    }

    /** {@code pad: [left, top, right, bottom]}, each 0 to the limit; absent is none. */
    private static int[] pad(DefinitionNode body, int max, String origin, String where, List<Diagnostic> diagnostics) {
        Object raw = body.raw("pad");
        int[] out = new int[4];
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> list) || list.size() != 4) {
            diagnostics.add(Diagnostic.warning(origin, where, "pad: should be four numbers, [left, top, right, bottom]. Using none."));
            return out;
        }
        for (int i = 0; i < 4; i++) {
            double n;
            try {
                n = Double.parseDouble(String.valueOf(list.get(i)));
            } catch (NumberFormatException e) {
                n = 0;
            }
            int side = (int) Math.round(Math.max(0, Math.min(max, Double.isFinite(n) ? n : 0)));
            if (side != n) {
                diagnostics.add(Diagnostic.warning(origin, where, "pad: " + list.get(i) + " is not a whole number from 0 to "
                        + max + ". Using " + side + "."));
            }
            out[i] = side;
        }
        return out;
    }
}
