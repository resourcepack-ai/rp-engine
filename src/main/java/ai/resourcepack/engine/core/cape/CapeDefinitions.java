package ai.resourcepack.engine.core.cape;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackFiles;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code capes/} out of a content folder.
 *
 * <pre>{@code
 * founder:
 *   name: "Founder Cape"          # optional; the id when absent
 *   texture: capes/founder        # assets/textures/capes/founder.png; capes/<id> when absent
 *   permission: myserver.founder  # optional; needed to pick it for yourself
 * }</pre>
 *
 * <p>The texture is vanilla's cape file: 64x32, or a whole multiple of it
 * (128x64 for an HD cape), in Mojang's layout, so a cape somebody already has
 * drops straight in. Read here, once, so a missing or misshapen file is a
 * load diagnostic rather than a purple box on somebody's back.
 */
public final class CapeDefinitions {

    private static final Set<String> KEYS = Set.of("name", "texture", "permission");

    private CapeDefinitions() {
    }

    /**
     * @param capes by id ({@code namespace:path}), in load order
     * @param files per namespace, the model and item definition each cape adds to its pack
     */
    public record Result(Map<String, Cape> capes, Map<String, Map<String, byte[]>> files, List<Diagnostic> diagnostics) {

        public static Result empty() {
            return new Result(Map.of(), Map.of(), List.of());
        }
    }

    public static Result parse(LoadReport loaded, PackFiles files) {
        if (loaded == null) {
            return Result.empty();
        }
        Map<String, Cape> capes = new LinkedHashMap<>();
        Map<String, Map<String, byte[]>> out = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ContentDefinition definition : loaded.definitions(ContentKind.CAPE)) {
            DefinitionNode body = definition.body();
            String origin = definition.origin();
            String namespace = definition.id().namespace();
            String key = definition.id().path();
            for (String written : body.keys()) {
                if (!KEYS.contains(written)) {
                    diagnostics.add(Diagnostic.warning(origin, key, written + " is not a cape setting, so it was "
                            + "ignored. A cape takes name, texture and permission."));
                }
            }
            String texture = body.string("texture").orElse("capes/" + key);
            if (texture.endsWith(".png")) {
                texture = texture.substring(0, texture.length() - 4);
            }
            String path = "assets/textures/" + texture + ".png";
            Optional<byte[]> png = files.read(namespace, path);
            if (png.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, key, "No texture at " + path
                        + ". A cape is a PNG in vanilla's cape layout, 64x32 (or 128x64 for HD)."));
                continue;
            }
            int[] size = pngSize(png.get());
            if (size == null || size[0] % 64 != 0 || size[0] == 0 || size[1] * 2 != size[0]) {
                diagnostics.add(Diagnostic.error(origin, key, path + " is "
                        + (size == null ? "not a PNG" : size[0] + "x" + size[1])
                        + ". A cape texture is 64x32, or a whole multiple of it such as 128x64."));
                continue;
            }
            String id = namespace + ":" + key;
            String modelId = namespace + ":cape/" + key;
            Map<String, byte[]> written = out.computeIfAbsent(namespace, n -> new LinkedHashMap<>());
            written.put("assets/" + namespace + "/models/cape/" + key + ".json",
                    CapeModel.json(namespace + ":" + texture).getBytes(StandardCharsets.UTF_8));
            written.put("assets/" + namespace + "/items/cape/" + key + ".json",
                    CapeModel.itemDefinition(modelId).getBytes(StandardCharsets.UTF_8));
            capes.put(id, new Cape(id, body.string("name").orElse(null), "cape:" + namespace + "/" + key,
                    namespace + ":cape/", body.string("permission").filter(p -> !p.isBlank()).orElse(null)));
        }
        return new Result(Map.copyOf(capes), Map.copyOf(out), List.copyOf(diagnostics));
    }

    /** Width and height out of a PNG's header, or null if it is not one. */
    static int[] pngSize(byte[] png) {
        if (png == null || png.length < 24 || (png[0] & 0xff) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            return null;
        }
        return new int[] {readInt(png, 16), readInt(png, 20)};
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }
}
