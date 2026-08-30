package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.core.version.Compatibility;

import org.bukkit.inventory.meta.ItemMeta;

/**
 * Making an item wearable, on the versions where any item can be.
 *
 * <p>Two floors, one release apart, and they are genuinely different
 * questions. 1.21.2 decides <em>whether</em> a thing can be worn — that is the
 * equippable component, and it is what lets a wizard hat be a real helmet
 * without also being a leather cap. 1.21.4 decides what it looks like on a
 * body, through the pack's {@code equipment/} assets.
 *
 * <p>So a server between the two wears a custom helmet that draws as the
 * vanilla one, which is a real state and not a bug. Below both, only materials
 * that were already armour can be worn at all, and the engine says so.
 *
 * @see Feature#EQUIPPABLE_ITEMS
 * @see Feature#ARMOUR_ART
 */
public interface Equipping {

    /**
     * Makes a stack wearable in {@code slot}, with the pack's own art where
     * the server can draw it.
     *
     * @return whether the item was made wearable by this call. A false does
     *         not mean nothing works — an item whose base material already
     *         fits the slot is still armour, which is why the caller does not
     *         treat it as an error
     */
    boolean wearable(ItemMeta meta, ContentId id, String slot,
                     ItemComponents.Warner warner);

    static Equipping forServer(Compatibility compatibility) {
        if (!compatibility.has(Feature.EQUIPPABLE_ITEMS)) {
            return new NoEquipping();
        }
        return new ComponentEquipping(compatibility.has(Feature.ARMOUR_ART));
    }
}
