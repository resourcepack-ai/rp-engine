package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 1.21.4 and up: the id is the model reference.
 *
 * <p>The whole id scheme in one method. Nothing is allocated, nothing is
 * remembered between restarts, and the string that arrives here is the same
 * string the pack builder wrote a definition file at.
 *
 * <p>Only instantiated on a server that has {@code setItemModel}; see
 * {@link ItemModelWiring} for why that is a class boundary and not a branch.
 */
final class DefinitionsWiring implements ItemModelWiring {

    @Override
    public void apply(ItemMeta meta, ContentId id) {
        meta.setItemModel(new NamespacedKey(id.namespace(), id.path()));
    }
}
