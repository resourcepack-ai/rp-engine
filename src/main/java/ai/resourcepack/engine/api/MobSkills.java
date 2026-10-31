package ai.resourcepack.engine.api;

import org.bukkit.entity.Entity;

import java.util.Collection;
import java.util.Optional;

/**
 * Custom mobs' skills: the MythicMobs-style lines a mob's {@code skills:}
 * lists and the metaskills in a content folder's {@code skills/}.
 *
 * <p>The language is extended here, the way MythicMobs' is: a plugin
 * registers a mechanic, a condition or a targeter by name, and any mob file
 * can then use it. Names are case-insensitive. Registering a name that
 * already exists replaces it, the engine's own included, so register yours
 * under a prefix ({@code myplugin:smite}) unless replacing is the point.
 * A line naming something nobody has registered does nothing (the load says
 * so), and starts working as soon as it is registered: names are looked up
 * when a line runs, so a plugin that enables after the engine needs no reload.
 *
 * <p>Everything that touches an entity is main thread only, or on Folia the
 * thread owning the mob.
 */
public interface MobSkills {

    void registerMechanic(String name, SkillMechanic mechanic, String... aliases);

    void registerCondition(String name, SkillCondition condition, String... aliases);

    void registerTargeter(String name, SkillTargeter targeter, String... aliases);

    /** Every metaskill, as {@code namespace:name} (lowercased). */
    Collection<ContentId> skills();

    /**
     * Casts a metaskill from a custom mob, aimed at {@code target} (null for
     * none: its lines then aim at the caster), as {@code skill{s=...}} would.
     * Its cooldown and conditions apply. False when there is no such skill,
     * the caster is not a custom mob, or a condition or cooldown said no.
     *
     * @param skill {@code mypack:GroundSlam}, or a bare name, looked for in the caster's own pack first
     */
    boolean cast(Entity caster, String skill, Entity target);

    /** A custom mob's stance, as {@code setstance} left it; empty for none. */
    Optional<String> stance(Entity mob);

    /** Sets a custom mob's stance, as {@code setstance} does. Null or blank clears it. */
    void stance(Entity mob, String stance);
}
