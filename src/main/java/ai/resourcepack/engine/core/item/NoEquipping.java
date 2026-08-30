package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Feature;

import org.bukkit.inventory.meta.ItemMeta;

/**
 * Below 1.21.2: what a thing is made of decides whether it can be worn.
 *
 * <p>Reported rather than silent, and reported as the limitation it is: an
 * item whose base material is already a helmet still goes on a head here. The
 * option is not ignored so much as reduced to what vanilla was always going to
 * do with it, and the difference matters to whoever wrote {@code armor: head}
 * on a stick.
 */
final class NoEquipping implements Equipping {

    @Override
    public boolean wearable(ItemMeta meta, ContentId id, String slot,
                            ItemComponents.Warner warner) {
        warner.unsupported("armor", Feature.EQUIPPABLE_ITEMS);
        return false;
    }
}
