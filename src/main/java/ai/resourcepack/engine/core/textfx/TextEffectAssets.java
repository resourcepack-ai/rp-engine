package ai.resourcepack.engine.core.textfx;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the text shader that draws a bundle's text effects.
 *
 * <p>One shader per version group, as Studio ships it: the group this server's
 * own version is in goes in the pack's base, which is what a client too old
 * for overlays reads, and every other group in an overlay of its own that only
 * clients of those formats load. A core shader written for one renderer does
 * not compile on another, and one that does not compile drops the whole pack,
 * so no client is ever handed a group that is not its own.
 *
 * <p>A bundle carries the effects of the namespaces in it, and nothing at all
 * when it has none, so a server that never writes {@code text-effects/} ships
 * exactly the pack it always did.
 */
public final class TextEffectAssets implements PackContributor {

    private static final String SHADERS = "assets/minecraft/shaders/";

    private final Map<ContentId, TextEffectDefinitions.Authored> effects;
    private final Map<ContentId, Integer> indices;
    private final int packFormat;

    /**
     * @param indices    each effect's index, from {@link TextEffectIds}; an effect without one is not shipped
     * @param packFormat this server's resource pack format, which decides the base
     */
    public TextEffectAssets(Map<ContentId, TextEffectDefinitions.Authored> effects, Map<ContentId, Integer> indices,
                            int packFormat) {
        this.effects = effects;
        this.indices = indices;
        this.packFormat = packFormat;
    }

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        List<TextEffectShaders.Effect> mine = new ArrayList<>();
        for (TextEffectDefinitions.Authored effect : effects.values()) {
            Integer index = indices.get(effect.id());
            if (index != null && bundle.namespaces().contains(effect.id().namespace())) {
                mine.add(new TextEffectShaders.Effect(effect.id().toString(), index, effect.glsl(), effect.pad()));
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        TextEffectShaders shaders = TextEffectShaders.get();
        boolean based = false;
        for (TextEffectShaders.Group group : shaders.groups()) {
            boolean base = !based && group.covers(packFormat);
            String root = base ? "" : group.directory() + "/";
            Map<String, String> files = shaders.files(group, mine);
            if (base && files.keySet().stream().anyMatch(file -> into.has(SHADERS + file))) {
                // The pack's own files already replace the text shader (an
                // imported pack that brought one, say). Ours would overwrite
                // theirs, so theirs stays and this version gets an overlay,
                // which a client of it reads over the base.
                into.warn(bundle.name(), "text-effects", "This bundle already has its own text shader, so Text Effects "
                        + "for this server's version go in an overlay above it, and that shader stops drawing.");
                base = false;
                root = group.directory() + "/";
            }
            based |= base;
            for (Map.Entry<String, String> file : files.entrySet()) {
                into.add(root + SHADERS + file.getKey(), file.getValue().getBytes(StandardCharsets.UTF_8));
            }
            if (!base) {
                into.overlay(group.directory(), group.minFormat(), group.maxFormat());
            }
        }
    }
}
