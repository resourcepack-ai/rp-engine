package ai.resourcepack.engine.api;

/**
 * A condition a plugin adds to the mob skill language, registered with
 * {@link MobSkills#registerCondition}. Tested against {@code cast.target()}:
 * the caster in a {@code Conditions:} list or an inline {@code ?}, the
 * trigger for {@code ?~}, each candidate in {@code TargetConditions:} or a
 * targeter's {@code conditions=}.
 */
@FunctionalInterface
public interface SkillCondition {

    boolean test(SkillCast cast);
}
