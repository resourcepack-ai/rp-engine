package ai.resourcepack.engine.core.cape;

import ai.resourcepack.engine.core.armor3d.Armor3dSet;

import java.util.List;
import java.util.Map;

/**
 * A cape this server can put on somebody: a texture in vanilla's cape layout,
 * drawn as one display hanging off their back.
 *
 * <p>Worn through the 3D armour runtime ({@code WornArmour}), which already
 * stands displays on a player's limbs with the right latency for each
 * audience; a cape is one more part on the body bone, turned by vanilla's own
 * cape physics. {@link #art()} is the one-part "set" that runtime is handed,
 * which is also how the display's item is named: a pushed cape by the
 * {@code custom_model_data} string Studio's pack dispatches, a content
 * folder's by its own item definition.
 *
 * @param id            {@code namespace:path} for a content folder's, the bare
 *                      Studio id for a push's (shown as {@code studio:<id>})
 * @param model         the display's model string: {@code cape:<id>} for a push
 *                      (Studio's {@code items/paper.json} case),
 *                      {@code cape:<namespace>/<path>} for a content folder's
 * @param itemModelRoot for a content folder's cape, the item definition folder
 *                      its model is under ({@code <namespace>:cape/}); null for a push
 * @param permission    a node needed to choose it for yourself, or null
 */
public record Cape(String id, String name, String model, String itemModelRoot, String permission) {

    public Cape {
        name = name == null || name.isBlank() ? id : name;
    }

    /** The cape as the 3D armour runtime wears it: a set with no pieces, whose art the model names. */
    public Armor3dSet art() {
        return new Armor3dSet("cape:" + id, name, Map.of(), List.of(), itemModelRoot);
    }

    /** How it is named to a person and typed: {@code christmas:founder}, or {@code studio:a1b2c3}. */
    public String label() {
        return CapeCatalogue.label(id);
    }
}
