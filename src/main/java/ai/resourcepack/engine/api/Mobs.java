package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Optional;

/**
 * Custom mobs: the ones a content folder's {@code entities/} declares and the
 * ones a Studio push carries ({@code studio:<id>}), one catalogue.
 *
 * <p>Every method that touches an entity is main thread only, or on Folia the
 * thread that owns the entity or the place.
 */
public interface Mobs {

    /** Every custom mob id, sorted. */
    Collection<ContentId> ids();

    /** What one is. */
    Optional<EntityInfo> info(ContentId id);

    /** Spawns one, wearing its model, playing its spawn animation. Empty if there is no such mob. */
    Optional<Entity> spawn(Location where, ContentId id);

    /** Which custom mob this is, or empty for anything else. */
    Optional<ContentId> idOf(Entity entity);

    /** What it is doing, as far as its animations go. Empty for an entity that is not one. */
    Optional<MobState> state(Entity entity);

    /**
     * Plays the animation mapped to {@code state}, as though it had happened:
     * a one-shot to its end, then back to moving. False when the mob has no
     * animation for it, or a higher one-shot is playing.
     */
    boolean play(Entity entity, MobState state);

    /**
     * Plays one of its model's animations by name, outside the state map, for
     * {@code seconds} (0 for the animation's own length), then hands back.
     */
    boolean play(Entity entity, String animation, double seconds);

    /** A spawn egg for {@code id}: used on a block, it spawns that mob there. */
    Optional<ItemStack> egg(ContentId id, int amount);
}
