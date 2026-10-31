package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.SkillCast;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** A {@link SkillCast}: one line's (or condition's, or targeter's) arguments, aimed at one target. */
final class SkillCastImpl implements SkillCast {

    final SkillContext context;
    final SkillLine.Args args;
    /** An Entity or a Location; null for "no target", which reads as the caster. */
    final Object target;
    private final SkillRuntime runtime;

    SkillCastImpl(SkillRuntime runtime, SkillContext context, SkillLine.Args args, Object target) {
        this.runtime = runtime;
        this.context = context;
        this.args = args == null ? SkillLine.Args.NONE : args;
        this.target = target;
    }

    SkillCastImpl aimedAt(Object other) {
        return new SkillCastImpl(runtime, context, args, other);
    }

    @Override
    public LivingEntity caster() {
        return context.caster;
    }

    @Override
    public Optional<ContentId> mob() {
        return Optional.ofNullable(context.mob);
    }

    @Override
    public Optional<Entity> trigger() {
        return Optional.ofNullable(context.trigger);
    }

    @Override
    public Optional<Entity> target() {
        return target instanceof Entity ? Optional.of((Entity) target) : Optional.empty();
    }

    /** The target entity, or the caster when the target is a point or nothing. */
    Entity entity() {
        return target instanceof Entity ? (Entity) target : (target == null ? context.caster : null);
    }

    @Override
    public Location location() {
        if (target instanceof Location) {
            return ((Location) target).clone();
        }
        if (target instanceof Entity && target != context.caster) {
            return ((Entity) target).getLocation();
        }
        return context.casterLocation();
    }

    @Override
    public double power() {
        return context.power;
    }

    @Override
    public Optional<String> stance() {
        String stance = context.state.stance;
        return stance == null || stance.isEmpty() ? Optional.empty() : Optional.of(stance);
    }

    String raw(String key, String... aliases) {
        String value = args.get(key);
        if (value == null) {
            value = args.get(aliases);
        }
        return value;
    }

    @Override
    public String string(String key, String otherwise, String... aliases) {
        String value = raw(key, aliases);
        return value == null ? otherwise : fill(value);
    }

    @Override
    public double number(String key, double otherwise, String... aliases) {
        String value = raw(key, aliases);
        if (value == null) {
            return otherwise;
        }
        return Ranged.pick(fill(value), ThreadLocalRandom.current()).orElse(otherwise);
    }

    @Override
    public boolean bool(String key, boolean otherwise, String... aliases) {
        String value = raw(key, aliases);
        if (value == null) {
            return otherwise;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        return text.equals("true") || text.equals("yes") || text.equals("1") || text.equals("on");
    }

    @Override
    public String fill(String text) {
        return SkillText.fill(text, (scope, field) -> {
            switch (scope) {
                case "caster":
                    return SkillText.field(context.caster, field, context.state.stance);
                case "trigger":
                    return SkillText.field(context.trigger, field, runtime.stanceOf(context.trigger));
                case "target":
                    if (target instanceof Entity) {
                        return SkillText.field((Entity) target, field, runtime.stanceOf((Entity) target));
                    }
                    if (target instanceof Location) {
                        Location at = (Location) target;
                        switch (field) {
                            case "l.x":
                            case "x":
                                return SkillText.number(at.getX());
                            case "l.y":
                            case "y":
                                return SkillText.number(at.getY());
                            case "l.z":
                            case "z":
                                return SkillText.number(at.getZ());
                            default:
                                return null;
                        }
                    }
                    return SkillText.field(context.caster, field, context.state.stance);
                case "skill":
                    switch (field) {
                        case "power":
                            return SkillText.number(context.power);
                        case "damage":
                        case "var.damage-amount":
                        case "var.damage.amount":
                            return SkillText.number(context.run.damage);
                        case "trigger":
                            return context.triggerName;
                        default:
                            return null;
                    }
                default:
                    return null;
            }
        });
    }
}
