package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Collection;
import java.util.List;

/**
 * A targeter a plugin adds to the mob skill language, registered with
 * {@link MobSkills#registerTargeter}. Returns entities, or points: override
 * the one it is about. Its own options are read from {@code cast}
 * ({@code @MyTargeter{r=8}} is {@code cast.number("r", ...)}); the shared ones
 * ({@code limit}, {@code sort}, offsets, {@code conditions}) are applied after.
 */
public interface SkillTargeter {

    /** The entities it picks. */
    default Collection<Entity> entities(SkillCast cast) {
        return List.of();
    }

    /** The places it picks, for a targeter of points. */
    default Collection<Location> locations(SkillCast cast) {
        return List.of();
    }
}
