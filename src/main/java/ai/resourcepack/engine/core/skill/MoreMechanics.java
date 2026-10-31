package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.MobState;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The second half of the built-in mechanics: projectiles, threat, boss bars,
 * mob variables, signals and the model swaps. Kept apart from
 * {@link Mechanics} only for length; same rules, same registry.
 */
final class MoreMechanics {

    private MoreMechanics() {
    }

    static void install(SkillRuntime runtime) {
        SkillRegistry r = runtime.registry;

        // --- projectiles ------------------------------------------------
        r.mechanic(target(runtime, (cast, aim) -> Projectiles.launch(runtime, cast, aim, "projectile"), false),
                "projectile", "proj");
        r.mechanic(target(runtime, (cast, aim) -> Projectiles.launch(runtime, cast, aim, "missile"), false),
                "missile", "homingmissile");
        r.mechanic(target(runtime, (cast, aim) -> Projectiles.launch(runtime, cast,
                aim instanceof Entity ? ((Entity) aim).getLocation() : aim, "totem"), false), "totem", "ward");
        r.mechanic(target(runtime, (cast, aim) -> aim instanceof LivingEntity
                && Projectiles.chain(runtime, cast, (LivingEntity) aim), false), "chain", "chainmissile");

        // --- signals ----------------------------------------------------
        r.mechanic(target(runtime, (cast, aim) -> {
            String name = cast.string("signal", "", "s", "name", "n");
            if (aim instanceof Entity && !name.isBlank()) {
                runtime.signal((Entity) aim, name.trim(), cast.caster());
                return true;
            }
            return false;
        }, false), "signal", "sendsignal");

        // --- threat -----------------------------------------------------
        r.mechanic(target(runtime, (cast, aim) -> {
            ThreatTable table = runtime.threatOf(cast.caster());
            if (table == null || !(aim instanceof LivingEntity) || aim == cast.caster()) {
                return false;
            }
            UUID who = ((Entity) aim).getUniqueId();
            double amount = cast.number("amount", 1, "a") * cast.power();
            switch (cast.string("mode", "add", "m").toLowerCase(Locale.ROOT)) {
                case "set":
                    table.set(who, amount);
                    break;
                case "remove":
                case "subtract":
                    table.add(who, -amount);
                    break;
                case "multiply":
                    table.multiply(who, cast.number("amount", 1, "a"));
                    break;
                case "reset":
                case "clear":
                    table.remove(who);
                    break;
                default:
                    table.add(who, amount);
            }
            return true;
        }, false), "threat", "threatchange", "modifythreat");
        r.mechanic(target(runtime, (cast, aim) -> {
            // The target mob turns on the caster: top of its table, and its target now.
            if (!(aim instanceof Mob) || aim == cast.caster()) {
                return false;
            }
            ThreatTable table = runtime.threatOf((Entity) aim);
            if (table != null) {
                UUID top = table.top();
                double most = top == null ? 0 : table.get(top);
                table.set(cast.caster().getUniqueId(), most + Math.max(1, cast.number("amount", 1, "a")));
            }
            ((Mob) aim).setTarget(cast.caster());
            return true;
        }, false), "taunt");
        r.mechanic(group((context, line, targets) -> {
            ThreatTable table = runtime.threatOf(context.caster);
            if (table != null) {
                table.clear();
            }
        }, true), "clearthreat", "threatclear", "resetthreat");

        // --- boss bars --------------------------------------------------
        r.mechanic(group((context, line, targets) -> bar(runtime, context, line, targets, true), false),
                "barcreate", "createbossbar", "bossbarcreate", "bar");
        r.mechanic(group((context, line, targets) -> bar(runtime, context, line, targets, false), false),
                "barset", "setbossbar", "bossbarset");
        r.mechanic(group((context, line, targets) -> {
            SkillCastImpl cast = new SkillCastImpl(runtime, context, line.args, null);
            String name = cast.string("name", "bar", "n", "id").toLowerCase(Locale.ROOT);
            BossBar bar = context.state.bars.remove(name);
            if (bar != null) {
                bar.removeAll();
                bar.setVisible(false);
            }
        }, true), "barremove", "removebossbar", "bossbarremove");

        // --- variables --------------------------------------------------
        r.mechanic(group((context, line, targets) -> variable(runtime, context, line, targets, "set"), true),
                "setvariable", "setvar", "variableset", "var");
        r.mechanic(group((context, line, targets) -> variable(runtime, context, line, targets, "add"), true),
                "variableadd", "varadd", "addvariable", "variableincrement");
        r.mechanic(group((context, line, targets) -> variable(runtime, context, line, targets, "subtract"), true),
                "variablesubtract", "varsubtract", "varsub", "variabledecrement");
        r.mechanic(group((context, line, targets) -> variable(runtime, context, line, targets, "unset"), true),
                "variableunset", "varunset", "unsetvariable", "removevariable");

        // --- the model --------------------------------------------------
        r.mechanic(target(runtime, (cast, aim) -> aim instanceof Entity && runtime.host.boneVisible((Entity) aim,
                cast.string("bone", "", "b", "part", "pid", "p"), cast.bool("visible", false, "v")), true),
                "bonevisible", "partvisibility", "partvis", "bonevis", "hidebone");
        r.mechanic(target(runtime, (cast, aim) -> {
            String model = cast.string("model", "", "m", "mid", "id");
            if (!(aim instanceof Entity) || model.isBlank()) {
                return false;
            }
            Optional<ContentId> id = ContentId.parse(model.toLowerCase(Locale.ROOT).indexOf(':') > 0
                    ? model.toLowerCase(Locale.ROOT)
                    : (cast.context.mob == null ? "minecraft" : cast.context.mob.namespace()) + ":"
                    + model.toLowerCase(Locale.ROOT));
            return id.isPresent() && runtime.host.swapModel((Entity) aim, id.get());
        }, true), "swapmodel", "model", "changemodel", "setmodel");
        r.mechanic(target(runtime, (cast, aim) -> {
            String state = cast.string("state", "", "s", "type", "t");
            String animation = cast.string("animation", "", "a", "anim", "to");
            Optional<MobState> which = MobState.parse(state);
            if (!(aim instanceof Entity) || which.isEmpty()) {
                runtime.warnOnce("defaultstate:" + state, "defaultstate{state=" + state + "} is not one of the "
                        + "mob states (idle, walk, run, attack, hurt, spawn, death).");
                return false;
            }
            return runtime.host.defaultState((Entity) aim, which.get(), animation.isBlank() ? null : animation);
        }, true), "defaultstate", "remapstate", "setdefaultstate");
    }

    /** A target-at-a-time mechanic that wants the target itself, entity or place. */
    private static SkillRegistry.Mechanic target(SkillRuntime runtime,
                                                 java.util.function.BiPredicate<SkillCastImpl, Object> act,
                                                 boolean self) {
        return new SkillRegistry.Mechanic() {
            @Override
            public void run(SkillContext context, SkillLine line, List<Object> targets) {
                for (Object aim : targets) {
                    if (aim instanceof Entity && !((Entity) aim).isValid() && aim != context.caster) {
                        continue;
                    }
                    act.test(new SkillCastImpl(runtime, context, line.args, aim), aim);
                }
            }

            @Override
            public boolean selfByDefault() {
                return self;
            }
        };
    }

    private static SkillRegistry.Mechanic group(SkillRegistry.Mechanic body, boolean self) {
        return new SkillRegistry.Mechanic() {
            @Override
            public void run(SkillContext context, SkillLine line, List<Object> targets) {
                body.run(context, line, targets);
            }

            @Override
            public boolean selfByDefault() {
                return self;
            }
        };
    }

    // --- boss bars --------------------------------------------------------

    /**
     * {@code barcreate{name;display;color;style;value}} and {@code barset}: a
     * bar of the caster's, shown to the line's player targets (with no
     * targeter, every player within 32 blocks). {@code value} is 0 to 1, or a
     * percentage ({@code 50%}, or any placeholder over 1 such as
     * {@code <caster.php>}); absent, the caster's health.
     */
    private static void bar(SkillRuntime runtime, SkillContext context, SkillLine line, List<Object> targets,
                            boolean create) {
        SkillCastImpl cast = new SkillCastImpl(runtime, context, line.args, null);
        String name = cast.string("name", "bar", "n", "id").toLowerCase(Locale.ROOT);
        BossBar bar = context.state.bars.get(name);
        String title = colour(cast.string("display", null, "title", "t", "text", "d"));
        BarColor color = enumOf(BarColor.class, cast.string("color", null, "c", "colour"));
        BarStyle style = enumOf(BarStyle.class, cast.string("style", null, "s"));
        if (bar == null) {
            if (!create && context.state.bars.isEmpty()) {
                return;
            }
            if (context.state.bars.size() >= 8) {
                runtime.warnOnce("bars", "A mob tried to keep more than 8 skill boss bars; the rest are refused.");
                return;
            }
            bar = Bukkit.createBossBar(title == null ? SkillText.name(context.caster) : title,
                    color == null ? BarColor.PURPLE : color, style == null ? BarStyle.SOLID : style);
            context.state.bars.put(name, bar);
        } else {
            if (title != null) {
                bar.setTitle(title);
            }
            if (color != null) {
                bar.setColor(color);
            }
            if (style != null) {
                bar.setStyle(style);
            }
        }
        String written = cast.raw("value", "v", "progress");
        double value;
        if (written == null) {
            double max = SkillMath.maxHealth(context.caster);
            value = max <= 0 ? 0 : context.caster.getHealth() / max;
        } else {
            String filled = cast.fill(written).trim();
            boolean percent = filled.endsWith("%");
            double parsed = Ranged.pick(filled.replace("%", ""), java.util.concurrent.ThreadLocalRandom.current())
                    .orElse(1.0);
            value = percent || parsed > 1 ? parsed / 100 : parsed;
        }
        bar.setProgress(Math.max(0, Math.min(1, value)));
        List<Player> viewers = new ArrayList<>();
        for (Object target : targets) {
            if (target instanceof Player) {
                viewers.add((Player) target);
            }
        }
        if (viewers.isEmpty() && line.targeter == null && (create || bar.getPlayers().isEmpty())) {
            Location at = context.casterLocation();
            if (at.getWorld() != null) {
                for (Player player : at.getWorld().getPlayers()) {
                    if (player.getLocation().distanceSquared(at) <= 32 * 32) {
                        viewers.add(player);
                    }
                }
            }
        }
        for (Player viewer : viewers) {
            if (!bar.getPlayers().contains(viewer)) {
                bar.addPlayer(viewer);
            }
        }
        bar.setVisible(true);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String colour(String text) {
        return text == null ? null : ChatColor.translateAlternateColorCodes('&',
                ai.resourcepack.engine.core.textfx.TextEffectCatalogue.formatInstalled(text));
    }

    // --- variables --------------------------------------------------------

    /**
     * {@code setvariable{var=phase;value=2}}, {@code variableadd{var;amount}},
     * {@code variablesubtract}, {@code variableunset}. A variable lives on a
     * custom mob: the caster ({@code var=phase} or {@code var=caster.phase}),
     * or each of the line's targets that is a custom mob
     * ({@code var=target.phase}). MythicMobs' other scopes (skill, global,
     * world) are not kept and say so.
     */
    private static void variable(SkillRuntime runtime, SkillContext context, SkillLine line, List<Object> targets,
                                 String mode) {
        SkillCastImpl cast = new SkillCastImpl(runtime, context, line.args, null);
        String written = cast.string("variable", "", "var", "name", "n", "key", "k").trim().toLowerCase(Locale.ROOT);
        if (written.isEmpty()) {
            return;
        }
        List<SkillState> on = new ArrayList<>();
        if (written.startsWith("target.")) {
            written = written.substring("target.".length());
            for (Object target : targets) {
                if (target instanceof Entity) {
                    SkillState state = runtime.state((Entity) target);
                    if (state != null) {
                        on.add(state);
                    }
                }
            }
        } else {
            for (String scope : new String[]{"skill.", "global.", "world.", "trigger."}) {
                if (written.startsWith(scope)) {
                    runtime.warnOnce("varscope:" + scope, "Mob variables live on the mob (var=" + written.substring(
                            scope.length()) + " or var=target.<name>); " + scope + " variables are not kept.");
                    return;
                }
            }
            on.add(context.state);
        }
        String name = SkillState.varName(written);
        for (SkillState state : on) {
            switch (mode) {
                case "set":
                    state.vars.put(name, cast.string("value", "", "val", "v"));
                    break;
                case "unset":
                    state.vars.remove(name);
                    break;
                default:
                    double amount = cast.number("amount", 1, "a", "value", "v");
                    double was;
                    try {
                        was = Double.parseDouble(state.vars.getOrDefault(name, "0").trim());
                    } catch (NumberFormatException e) {
                        was = 0;
                    }
                    state.vars.put(name, SkillText.number(mode.equals("add") ? was + amount : was - amount));
            }
            if (state.vars.size() > 64) {
                runtime.warnOnce("vars", "A mob has more than 64 variables; check a skill that names them from data.");
            }
        }
    }
}
