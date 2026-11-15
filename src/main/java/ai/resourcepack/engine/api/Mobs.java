package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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

    /**
     * Spawns one, wearing its model, playing its spawn animation. Empty if
     * there is no such mob, or a plugin cancelled its
     * {@link ai.resourcepack.engine.api.event.MobSpawnEvent}.
     */
    Optional<Entity> spawn(Location where, ContentId id);

    /**
     * Spawns one at {@code level} (1 and up), or at a level rolled from its
     * own {@code level:} range when {@code level} is 0. Empty if there is no
     * such mob, or a plugin cancelled its
     * {@link ai.resourcepack.engine.api.event.MobSpawnEvent}.
     */
    default Optional<Entity> spawn(Location where, ContentId id, int level) {
        return spawn(where, id);
    }

    /** Which custom mob this is, or empty for anything else. */
    Optional<ContentId> idOf(Entity entity);

    /** Whether this entity is a custom mob at all. */
    default boolean isMob(Entity entity) {
        return idOf(entity).isPresent();
    }

    /** A custom mob's level: 1 unless it was spawned at another, and for anything that is not one. */
    default int level(Entity entity) {
        return 1;
    }

    /**
     * Every custom mob within {@code radius} blocks of a place, nearest first:
     * the entities themselves, which {@link #idOf} names. For "is a boss
     * already standing here" and for an ability that hits every one in reach.
     */
    default List<Entity> near(Location centre, double radius) {
        if (centre == null || centre.getWorld() == null || radius <= 0) {
            return List.of();
        }
        List<Entity> found = new ArrayList<>();
        for (Entity entity : centre.getWorld().getNearbyEntities(centre, radius, radius, radius)) {
            if (isMob(entity) && entity.getLocation().distanceSquared(centre) <= radius * radius) {
                found.add(entity);
            }
        }
        found.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(centre)));
        return found;
    }

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
