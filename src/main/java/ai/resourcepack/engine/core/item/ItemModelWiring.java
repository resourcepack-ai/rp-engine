package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemEra;

import org.bukkit.inventory.meta.ItemMeta;

/**
 * How this server is told which model an item wears.
 *
 * <p>Two arms, chosen once from {@link ItemEra} and then never asked about
 * again. The interface exists mostly so that the newer arm's call to
 * {@code setItemModel} lives in a class that is only ever <em>instantiated</em>
 * on a server that has the method.
 *
 * <p>That distinction is the reason this is not an {@code if} in
 * {@code ItemsImpl}. A method that does not exist on the running server fails
 * when its call site is first reached, and only then — so a branch nobody
 * takes is usually harmless, and "usually" is doing a lot of work there. The
 * JVM is permitted to resolve more eagerly than that, verification pulls in
 * types it needs to merge, and neither is something to bet a plugin's ability
 * to start on. One class per arm costs nothing and removes the question.
 *
 * @see ItemEra
 */
public interface ItemModelWiring {

    /**
     * Points a stack's meta at the model for {@code id}.
     *
     * <p>The meta is not applied to the stack here; the caller is mid-way
     * through building one and applies it once.
     */
    void apply(ItemMeta meta, ContentId id);

    /**
     * The arm for an era.
     *
     * @param numbers the allocator, needed only by the numbered arm. It is
     *                still asked for on the definitions arm rather than being
     *                nullable there, because a caller that has to decide
     *                whether to build one is a caller that has to know which
     *                arm it is on, which is what this type is for
     */
    static ItemModelWiring forEra(ItemEra era, ModelNumbers numbers) {
        return era.needsNumbers() ? new NumberedWiring(numbers) : new DefinitionsWiring();
    }
}
