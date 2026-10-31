package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Optional;

/**
 * One mob skill line running against one target: what a {@link SkillMechanic}
 * is handed, and what a {@link SkillCondition} or {@link SkillTargeter} reads.
 *
 * <p>Arguments are read the way MythicMobs writes them, by any of their
 * names ({@code number("amount", 1, "a")}), with placeholders such as
 * {@code <caster.hp>} and {@code <target.name>} already filled in. A number
 * written as a range ({@code 2to5}) is picked from it each time it is read.
 *
 * <p>Only valid during the call it is handed to, on the caster's thread.
 */
public interface SkillCast {

    /** The mob casting the skill. */
    LivingEntity caster();

    /** Which custom mob the caster is, when it is one. */
    Optional<ContentId> mob();

    /** What set it off: whoever hit it, what it hit, who clicked it. Empty for a timer or a spawn. */
    Optional<Entity> trigger();

    /** The entity this run is aimed at, when the target is an entity. */
    Optional<Entity> target();

    /** Where this run is aimed: the target entity's location, or the point a location targeter chose. */
    Location location();

    /** The skill's power: 1 unless a {@code power} condition action raised it. Scales amounts. */
    double power();

    /** The mob's stance, empty when it has none. */
    Optional<String> stance();

    /** The argument under {@code key} or one of its {@code aliases}, placeholders filled; {@code otherwise} when unset. */
    String string(String key, String otherwise, String... aliases);

    /** A number argument; {@code otherwise} when unset or not a number. */
    double number(String key, double otherwise, String... aliases);

    /** A whole-number argument. */
    default int integer(String key, int otherwise, String... aliases) {
        return (int) Math.round(number(key, otherwise, aliases));
    }

    /** A true/false argument. */
    boolean bool(String key, boolean otherwise, String... aliases);

    /** Fills {@code <caster.hp>}-style placeholders in any text. */
    String fill(String text);
}
