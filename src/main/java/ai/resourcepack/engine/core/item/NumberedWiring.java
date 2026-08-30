package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;

import org.bukkit.inventory.meta.ItemMeta;

/**
 * 1.19.4 to 1.21.3: the model is chosen by a number.
 *
 * <p>One arm for both legacy eras, because
 * {@code ItemMeta.setCustomModelData(Integer)} has existed since 1.14 and
 * still does. What changed in 1.20.5 is where the server keeps the value —
 * a component rather than NBT — and that is not visible from here. The two
 * eras are still named separately in {@link ai.resourcepack.engine.api.ItemEra}
 * because the boundary is real on studio's side, where the difference shows up
 * in {@code /give} syntax.
 *
 * <p>The number comes from {@link ModelNumbers} and is the same number the
 * pack builder wrote a predicate for. If those two ever disagree, every custom
 * item on the server is the wrong model — which is why one allocator is shared
 * rather than each side working one out.
 */
final class NumberedWiring implements ItemModelWiring {

    private final ModelNumbers numbers;

    NumberedWiring(ModelNumbers numbers) {
        this.numbers = numbers;
    }

    @Override
    public void apply(ItemMeta meta, ContentId id) {
        meta.setCustomModelData(numbers.of(id));
    }
}
