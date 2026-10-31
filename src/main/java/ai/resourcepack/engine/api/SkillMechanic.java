package ai.resourcepack.engine.api;

/**
 * A mechanic a plugin adds to the mob skill language, registered with
 * {@link MobSkills#registerMechanic}. Called once per target the line's
 * targeter found, on the caster's thread.
 *
 * <pre>{@code
 * skills.registerMechanic("myplugin:smite", cast -> {
 *     cast.target().ifPresent(t -> t.getWorld().strikeLightningEffect(t.getLocation()));
 *     return true;
 * });
 * // and in a mob:  - myplugin:smite @target ~onAttack 0.2
 * }</pre>
 */
@FunctionalInterface
public interface SkillMechanic {

    /** Does it to {@code cast.target()} or at {@code cast.location()}. False when it could not. */
    boolean cast(SkillCast cast);
}
