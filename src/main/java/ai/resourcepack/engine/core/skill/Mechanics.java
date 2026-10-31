package ai.resourcepack.engine.core.skill;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.MobState;
import ai.resourcepack.engine.api.VfxInfo;
import ai.resourcepack.engine.api.VfxOptions;
import ai.resourcepack.engine.core.Regions;
import ai.resourcepack.engine.core.version.Vanilla;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;

/**
 * The built-in mechanics, under MythicMobs' names and the aliases it accepts
 * (and ModelEngine's, for the model ones). FORMAT.md's Skills section is the
 * list for authors; keep the two together.
 */
final class Mechanics {

    private Mechanics() {
    }

    /** A target-at-a-time mechanic: an entity, a point, or both. */
    private static SkillRegistry.Mechanic each(SkillRuntime runtime, BiPredicate<SkillCastImpl, Entity> onEntity,
                                               BiPredicate<SkillCastImpl, Location> onPlace, boolean self) {
        return new SkillRegistry.Mechanic() {
            @Override
            public void run(SkillContext context, SkillLine line, List<Object> targets) {
                for (Object target : targets) {
                    SkillCastImpl cast = new SkillCastImpl(runtime, context, line.args, target);
                    if (target instanceof Entity) {
                        Entity entity = (Entity) target;
                        if (!entity.isValid() && entity != context.caster) {
                            continue;
                        }
                        if (onEntity != null) {
                            onEntity.test(cast, entity);
                        } else if (onPlace != null) {
                            onPlace.test(cast, entity.getLocation());
                        }
                    } else if (target instanceof Location && onPlace != null) {
                        onPlace.test(cast, ((Location) target).clone());
                    }
                }
            }

            @Override
            public boolean selfByDefault() {
                return self;
            }
        };
    }

    private static SkillRegistry.Mechanic entities(SkillRuntime runtime, BiPredicate<SkillCastImpl, Entity> onEntity) {
        return each(runtime, onEntity, null, false);
    }

    private static SkillRegistry.Mechanic living(SkillRuntime runtime, BiPredicate<SkillCastImpl, LivingEntity> act) {
        return each(runtime, (cast, entity) -> entity instanceof LivingEntity && act.test(cast, (LivingEntity) entity),
                null, false);
    }

    private static SkillRegistry.Mechanic places(SkillRuntime runtime, BiPredicate<SkillCastImpl, Location> onPlace) {
        return each(runtime, null, onPlace, false);
    }

    static void install(SkillRuntime runtime) {
        SkillRegistry r = runtime.registry;

        // --- damage and health --------------------------------------------
        r.mechanic(living(runtime, (cast, target) -> damage(runtime, cast, target,
                cast.number("amount", 1, "a", "damage", "d") * cast.power())), "damage", "d");
        r.mechanic(living(runtime, (cast, target) -> {
            double base = attribute(cast.caster(), "attack_damage");
            return damage(runtime, cast, target, base * cast.number("multiplier", 1, "m") * cast.power());
        }), "basedamage", "bd");
        r.mechanic(living(runtime, (cast, target) -> damage(runtime, cast, target,
                SkillMath.maxHealth(target) * fraction(cast.number("percent", 0.1, "p", "amount", "a"))
                        * cast.power())), "percentdamage", "pdamage");
        r.mechanic(living(runtime, (cast, target) -> heal(target, cast.number("amount", 1, "a") * cast.power(),
                cast.bool("overheal", false, "oh"))), "heal", "h");
        r.mechanic(living(runtime, (cast, target) -> heal(target,
                SkillMath.maxHealth(target) * fraction(cast.number("multiplier", 0.1, "m", "percent", "p",
                        "amount", "a")) * cast.power(), false)), "healpercent", "healp");
        r.mechanic(living(runtime, (cast, target) -> {
            target.setHealth(clamp(cast.number("amount", 1, "a"), 0, SkillMath.maxHealth(target)));
            return true;
        }), "sethealth");
        r.mechanic(living(runtime, (cast, target) -> {
            AttributeInstance max = Vanilla.maxHealth().map(target::getAttribute).orElse(null);
            if (max == null) {
                return false;
            }
            double amount = clamp(cast.number("amount", 20, "a", "health"), 1, 1024);
            max.setBaseValue(amount);
            if (cast.bool("sethealth", true, "set", "sh")) {
                target.setHealth(amount);
            } else if (target.getHealth() > amount) {
                target.setHealth(amount);
            }
            return true;
        }), "setmaxhealth", "maxhealth");
        r.mechanic(living(runtime, (cast, target) -> {
            try {
                target.setAbsorptionAmount(target.getAbsorptionAmount() + cast.number("amount", 4, "a") * cast.power());
            } catch (IllegalArgumentException e) {
                // Over the max_absorption attribute (1.20.2+): an absorption effect instead.
                int level = Math.max(0, (int) Math.ceil(cast.number("amount", 4, "a") / 4) - 1);
                target.addPotionEffect(potion("absorption", 20 * 60, level, false, true));
            }
            return true;
        }), "shield", "absorb");
        r.mechanic(group((context, line, targets) -> {
            if (!context.run.eventLive || !(context.run.event instanceof EntityDamageEvent)) {
                return;
            }
            EntityDamageEvent event = (EntityDamageEvent) context.run.event;
            SkillCastImpl cast = new SkillCastImpl(runtime, context, line.args, null);
            double amount = cast.number("amount", 1, "a", "multiplier", "m");
            String action = cast.string("action", "multiply", "type", "t").toLowerCase(Locale.ROOT);
            double damage = event.getDamage();
            switch (action) {
                case "add":
                    damage += amount;
                    break;
                case "set":
                    damage = amount;
                    break;
                default:
                    damage *= amount;
            }
            event.setDamage(Math.max(0, damage));
        }, true), "modifydamage", "modifyincomingdamage", "modifyoutgoingdamage");

        // --- status ------------------------------------------------------
        r.mechanic(living(runtime, (cast, target) -> {
            Optional<PotionEffectType> type = effect(cast.string("type", "", "t", "potion", "p"));
            if (type.isEmpty()) {
                runtime.warnOnce("potion:" + cast.string("type", "", "t"), "No potion effect "
                        + cast.string("type", "", "t") + " (a mob skill's potion{type=...}).");
                return false;
            }
            int duration = cast.integer("duration", 100, "d");
            int level = Math.max(0, cast.integer("level", 1, "lvl", "l") - 1);
            return target.addPotionEffect(new PotionEffect(type.get(), duration, level, false,
                    cast.bool("hasparticles", true, "particles", "p"), cast.bool("hasicon", true, "icon", "i")));
        }), "potion", "effect:potion", "e:potion");
        r.mechanic(living(runtime, (cast, target) -> {
            String which = cast.string("type", "", "t", "potion", "p");
            if (which.isEmpty()) {
                for (PotionEffect effect : List.copyOf(target.getActivePotionEffects())) {
                    target.removePotionEffect(effect.getType());
                }
                return true;
            }
            effect(which).ifPresent(target::removePotionEffect);
            return true;
        }), "potionclear", "removepotion", "clearpotions", "cleareffects");
        r.mechanic(entities(runtime, (cast, target) -> {
            target.setFireTicks(Math.max(target.getFireTicks(), cast.integer("ticks", 60, "t", "duration", "d")));
            return true;
        }), "ignite", "burn");
        r.mechanic(entities(runtime, (cast, target) -> {
            target.setFireTicks(0);
            return true;
        }), "extinguish");
        r.mechanic(entities(runtime, (cast, target) -> {
            target.setFreezeTicks(Math.max(target.getFreezeTicks(), cast.integer("ticks", 140, "t", "duration", "d")));
            return true;
        }), "freeze");
        r.mechanic(entities(runtime, (cast, target) -> {
            target.setGlowing(true);
            int duration = cast.integer("duration", 100, "d");
            if (duration > 0) {
                Tasks.onLater(runtime.plugin, target, () -> target.setGlowing(false), () -> { }, duration);
            }
            return true;
        }), "glow");
        r.mechanic(living(runtime, (cast, target) -> speed(runtime, target,
                cast.number("multiplier", cast.number("speed", 1.5, "s"), "m"), cast.integer("duration", 100, "d"))),
                "speed", "setspeed", "modifyspeed");
        r.mechanic(living(runtime, (cast, target) -> {
            if (target instanceof Player) {
                Player player = (Player) target;
                player.setFoodLevel(Math.min(20, player.getFoodLevel() + cast.integer("amount", 1, "a")));
                player.setSaturation(Math.min(player.getFoodLevel(),
                        player.getSaturation() + (float) cast.number("saturation", 0, "s")));
            }
            return true;
        }), "feed");

        // --- movement ----------------------------------------------------
        // Leap and lunge move the CASTER toward the target, which is where they aim.
        r.mechanic(each(runtime, null, (cast, place) -> {
            LivingEntity self = cast.caster();
            double noise = cast.number("noise", 0, "n");
            Location to = noise > 0 ? SkillMath.scatter(place, 0, noise, ThreadLocalRandom.current()) : place;
            self.setVelocity(SkillMath.leap(self.getLocation(), to, cast.number("velocity", 100, "v") / 50.0));
            return true;
        }, false), "leap");
        r.mechanic(each(runtime, null, (cast, place) -> {
            LivingEntity self = cast.caster();
            Vector flat = place.toVector().subtract(self.getLocation().toVector()).setY(0);
            if (flat.lengthSquared() > 1.0E-6) {
                flat.normalize();
            }
            flat.multiply(cast.number("velocity", 1, "v"));
            flat.setY(cast.number("velocityy", 0.4, "vy", "yvelocity", "yv"));
            self.setVelocity(flat);
            return true;
        }, false), "lunge");
        r.mechanic(each(runtime, null, (cast, place) -> {
            LivingEntity self = cast.caster();
            Vector away = self.getLocation().toVector().subtract(place.toVector()).setY(0);
            if (away.lengthSquared() > 1.0E-6) {
                away.normalize();
            }
            away.multiply(cast.number("velocity", 1, "v"));
            away.setY(cast.number("velocityy", 0.3, "vy", "yvelocity", "yv"));
            self.setVelocity(away);
            return true;
        }, false), "disengage");
        r.mechanic(entities(runtime, (cast, target) -> {
            if (target == cast.caster()) {
                return false;
            }
            Location from = cast.bool("fromorigin", false, "fo") ? cast.context.casterLocation() : cast.context.casterLocation();
            Vector away = target.getLocation().toVector().subtract(from.toVector()).setY(0);
            if (away.lengthSquared() > 1.0E-6) {
                away.normalize();
            }
            away.multiply(cast.number("velocity", 1, "v") / 10.0);
            away.setY(cast.number("velocityy", 1, "vy", "yv", "yvelocity") / 10.0);
            target.setVelocity(away);
            return true;
        }), "throw");
        r.mechanic(entities(runtime, (cast, target) -> {
            Vector to = cast.context.casterLocation().toVector().subtract(target.getLocation().toVector());
            double distance = to.length();
            if (distance < 1.0E-3) {
                return false;
            }
            to.normalize().multiply(Math.min(3, distance / 4) * cast.number("velocity", 1, "v") / 10.0 * 2);
            to.setY(Math.max(to.getY(), 0.3));
            target.setVelocity(to);
            return true;
        }), "pull");
        r.mechanic(entities(runtime, (cast, target) -> {
            Location to = cast.context.casterLocation();
            to.setYaw(target.getLocation().getYaw());
            to.setPitch(target.getLocation().getPitch());
            return Regions.move(target, to);
        }), "forcepull");
        r.mechanic(entities(runtime, (cast, target) -> {
            String mode = cast.string("mode", "set", "m").toLowerCase(Locale.ROOT);
            Vector given = new Vector(cast.number("x", 0), cast.number("y", 0), cast.number("z", 0));
            Vector now = target.getVelocity();
            switch (mode) {
                case "add":
                    target.setVelocity(now.add(given));
                    break;
                case "multiply":
                    target.setVelocity(now.multiply(given));
                    break;
                case "remove":
                    target.setVelocity(new Vector());
                    break;
                default:
                    target.setVelocity(given);
            }
            return true;
        }), "velocity");
        r.mechanic(entities(runtime, (cast, target) -> {
            Vector now = target.getVelocity();
            target.setVelocity(now.setY(cast.number("velocity", 1, "v")));
            return true;
        }), "jump");
        // Teleport moves the caster to its target, as MythicMobs' does.
        r.mechanic(each(runtime, null, (cast, place) -> {
            double h = cast.number("spreadh", 0, "sh");
            double v = cast.number("spreadv", 0, "sv");
            Location to = h > 0 ? SkillMath.scatter(place, 0, h, ThreadLocalRandom.current()) : place;
            if (v > 0) {
                to.add(0, ThreadLocalRandom.current().nextDouble() * v, 0);
            }
            Location from = cast.caster().getLocation();
            to.setYaw(from.getYaw());
            to.setPitch(from.getPitch());
            return Regions.move(cast.caster(), to);
        }, false), "teleport", "tp");
        r.mechanic(entities(runtime, (cast, target) -> {
            Optional<Location> to = coordinates(cast.string("location", "", "l", "loc", "coordinates", "c"),
                    target.getWorld());
            if (to.isEmpty()) {
                return false;
            }
            to.get().setYaw(target.getLocation().getYaw());
            return Regions.move(target, to.get());
        }), "teleportto", "tpto", "tpt");

        // --- summoning ---------------------------------------------------
        r.mechanic(places(runtime, (cast, place) -> summon(runtime, cast, place)), "summon", "spawn", "summonmob");
        r.mechanic(group((context, line, targets) -> {
            if (!(context.caster instanceof Player)) {
                context.caster.setHealth(0);
            }
        }, true), "suicide");
        r.mechanic(entities(runtime, (cast, target) -> {
            if (target instanceof Player) {
                return false;
            }
            Regions.remove(runtime.plugin, target);
            return true;
        }), "remove");

        // --- projectiles -------------------------------------------------
        r.mechanic(places(runtime, (cast, place) -> shoot(runtime, cast, place,
                cast.string("type", "arrow", "t", "projectile", "p"))), "shoot", "shootprojectile");
        r.mechanic(places(runtime, (cast, place) -> shoot(runtime, cast, place, "fireball")), "shootfireball",
                "fireball");
        r.mechanic(places(runtime, (cast, place) -> shoot(runtime, cast, place, "wither_skull")), "shootskull",
                "skull");
        r.mechanic(places(runtime, (cast, place) -> {
            int amount = Math.max(1, Math.min(30, cast.integer("amount", 10, "a")));
            for (int i = 0; i < amount; i++) {
                double spread = cast.number("spread", 20, "s");
                Location jitter = place.clone().add(
                        (ThreadLocalRandom.current().nextDouble() - 0.5) * spread / 10,
                        (ThreadLocalRandom.current().nextDouble() - 0.5) * spread / 20,
                        (ThreadLocalRandom.current().nextDouble() - 0.5) * spread / 10);
                shoot(runtime, cast, jitter, "arrow");
            }
            return true;
        }), "arrowvolley", "volley");

        // --- effects in the world ----------------------------------------
        r.mechanic(places(runtime, (cast, place) -> {
            World world = place.getWorld();
            if (world == null) {
                return false;
            }
            float power = (float) cast.number("power", cast.number("yield", 2, "y"), "p");
            boolean fire = cast.bool("fire", false, "f", "setfire");
            boolean breaks = cast.bool("breakblocks", false, "bb", "blockdamage", "bd");
            return world.createExplosion(place, power, fire, breaks, cast.caster());
        }), "explosion", "explode");
        r.mechanic(places(runtime, (cast, place) -> {
            Effects.particle(runtime, place, "explosion", 1, 0, 0, 0, 0, null, 1, null);
            Effects.sound(runtime, place, "entity.generic.explode", 2, 1);
            return true;
        }), "fakeexplosion", "effect:explosion", "e:explosion");
        r.mechanic(places(runtime, (cast, place) -> {
            World world = place.getWorld();
            if (world == null) {
                return false;
            }
            if (cast.raw("damage", "d") != null) {
                world.strikeLightningEffect(place);
                double damage = cast.number("damage", 0, "d") * cast.power();
                Entity hit = cast.entity();
                if (hit instanceof LivingEntity && hit != cast.caster() && damage > 0) {
                    damage(runtime, cast, (LivingEntity) hit, damage);
                }
                return true;
            }
            world.strikeLightning(place);
            return true;
        }), "lightning");
        r.mechanic(places(runtime, (cast, place) -> {
            if (place.getWorld() == null) {
                return false;
            }
            place.getWorld().strikeLightningEffect(place);
            return true;
        }), "fakelightning", "effect:lightning", "e:lightning");
        r.mechanic(places(runtime, (cast, place) -> {
            Effects.particle(runtime, place.add(0, cast.number("yoffset", 0, "y"), 0),
                    cast.string("particle", "reddust", "p"), cast.integer("amount", 10, "a"),
                    cast.number("hspread", 0, "hs", "xspread"), cast.number("vspread", 0, "vs", "yspread"),
                    cast.number("hspread", 0, "hs", "zspread"), cast.number("speed", 0, "s"),
                    cast.raw("color", "c"), (float) cast.number("size", 1), cast.raw("material", "m", "block", "b"));
            return true;
        }), "particles", "particle", "effect:particles", "e:particles", "effect:particle", "e:p");
        r.mechanic(places(runtime, (cast, place) -> {
            double radius = cast.number("radius", 2, "r");
            int points = Math.max(1, Math.min(200, cast.integer("points", 16, "p")));
            for (Location spot : SkillMath.ring(place.add(0, cast.number("yoffset", 0, "y"), 0), radius, points)) {
                Effects.particle(runtime, spot, cast.string("particle", "reddust", "particletype"),
                        cast.integer("amount", 1, "a"), 0, 0, 0, cast.number("speed", 0, "s"), cast.raw("color", "c"),
                        (float) cast.number("size", 1), null);
            }
            return true;
        }), "particlering", "effect:particlering", "e:pr");
        r.mechanic(places(runtime, (cast, place) -> {
            double radius = cast.number("radius", 2, "r");
            int points = Math.max(1, Math.min(300, cast.integer("amount", 40, "a", "points")));
            for (Location spot : SkillMath.sphere(place.add(0, cast.number("yoffset", 0, "y"), 0), radius, points)) {
                Effects.particle(runtime, spot, cast.string("particle", "reddust", "p"), 1, 0, 0, 0,
                        cast.number("speed", 0, "s"), cast.raw("color", "c"), (float) cast.number("size", 1), null);
            }
            return true;
        }), "particlesphere", "effect:particlesphere", "e:ps");
        r.mechanic(places(runtime, (cast, place) -> {
            Location from = cast.context.casterLocation().add(0, cast.number("fromy", 1, "yoffset", "y"), 0);
            Location to = place.clone().add(0, cast.number("toy", 1), 0);
            Vector step = to.toVector().subtract(from.toVector());
            double length = step.length();
            double gap = Math.max(0.1, cast.number("distancebetween", 0.3, "db"));
            int count = (int) Math.min(200, length / gap);
            if (count <= 0) {
                return false;
            }
            step.multiply(1.0 / count);
            Location at = from.clone();
            for (int i = 0; i <= count; i++) {
                Effects.particle(runtime, at, cast.string("particle", "reddust", "p"), 1, 0, 0, 0, 0,
                        cast.raw("color", "c"), (float) cast.number("size", 1), null);
                at.add(step);
            }
            return true;
        }), "particleline", "effect:particleline", "e:pl");
        r.mechanic(places(runtime, (cast, place) -> Effects.sound(runtime, place,
                cast.string("sound", "entity.generic.explode", "s"), (float) cast.number("volume", 1, "v"),
                (float) cast.number("pitch", 1, "p"))), "sound", "effect:sound", "e:sound", "s");
        r.mechanic(places(runtime, (cast, place) -> vfx(runtime, cast, place)), "vfx", "effect:vfx", "playvfx");
        r.mechanic(places(runtime, (cast, place) -> {
            World world = place.getWorld();
            ItemStack stack = Effects.stack(runtime, cast.string("item", "", "i", "type", "t"),
                    Math.max(1, cast.integer("amount", 1, "a")));
            if (world == null || stack == null) {
                return false;
            }
            world.dropItemNaturally(place, stack);
            return true;
        }), "dropitem", "drop");

        // --- text --------------------------------------------------------
        r.mechanic(entities(runtime, (cast, target) -> {
            if (target instanceof Player) {
                ((Player) target).sendMessage(colour(cast.string("message", "", "m", "msg")));
            }
            return true;
        }), "message", "msg", "m");
        r.mechanic(entities(runtime, (cast, target) -> {
            if (target instanceof Player) {
                ((Player) target).spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        TextComponent.fromLegacyText(colour(cast.string("message", "", "m", "msg"))));
            }
            return true;
        }), "actionmessage", "actionbar", "actionbarmessage", "am");
        r.mechanic(entities(runtime, (cast, target) -> {
            if (target instanceof Player) {
                ((Player) target).sendTitle(colour(cast.string("title", "", "t")),
                        colour(cast.string("subtitle", "", "st")), cast.integer("fadein", 10, "fi"),
                        cast.integer("stay", 40, "d", "duration"), cast.integer("fadeout", 10, "fo"));
            }
            return true;
        }), "sendtitle", "title");
        r.mechanic(group((context, line, targets) -> {
            SkillCastImpl cast = new SkillCastImpl(runtime, context, line.args, null);
            double radius = cast.number("radius", 16, "r");
            String said = colour(cast.string("message", "", "m"));
            Location at = context.casterLocation();
            if (at.getWorld() == null) {
                return;
            }
            for (Player player : at.getWorld().getPlayers()) {
                if (player.getLocation().distanceSquared(at) <= radius * radius) {
                    player.sendMessage(SkillText.name(context.caster) + ChatColor.RESET + ": " + said);
                }
            }
        }, true), "speak");
        r.mechanic(entities(runtime, (cast, target) -> {
            String command = cast.string("command", "", "c", "cmd");
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (command.isBlank()) {
                return false;
            }
            if (cast.bool("astarget", false, "at") && target instanceof Player) {
                return ((Player) target).performCommand(command);
            }
            if (cast.bool("asop", false, "op")) {
                runtime.warnOnce("asop", "A mob skill asked for command{asop=true}. RP Engine never makes a "
                        + "player an operator, even for a moment; the command runs as the console instead.");
            }
            return Regions.console(runtime.plugin, command);
        }), "command", "cmd", "consolecommand");

        // --- flow --------------------------------------------------------
        r.mechanic(group((context, line, targets) -> {
            SkillCastImpl cast = new SkillCastImpl(runtime, context, line.args, null);
            String name = cast.string("skill", "", "s", "meta", "m", "metaskill", "");
            runtime.cast(context, name, line.targeter == null && context.inherited == null
                    ? defaultTargets(context) : targets);
        }, false), "skill", "metaskill", "meta", "castskill");
        r.mechanic(group((context, line, targets) -> {
            SkillCastImpl cast = new SkillCastImpl(runtime, context, line.args, null);
            String list = cast.string("skills", "", "s", "skill");
            String[] names = list.split(",");
            List<String> options = new java.util.ArrayList<>();
            for (String name : names) {
                if (!name.isBlank()) {
                    options.add(name.trim());
                }
            }
            if (!options.isEmpty()) {
                runtime.cast(context, options.get(ThreadLocalRandom.current().nextInt(options.size())),
                        line.targeter == null && context.inherited == null ? defaultTargets(context) : targets);
            }
        }, false), "randomskill", "randomskills");
        r.mechanic(group((context, line, targets) -> {
            if (context.run.eventLive && context.run.event instanceof Cancellable) {
                ((Cancellable) context.run.event).setCancelled(true);
            }
        }, true), "cancelevent", "cancel");
        r.mechanic(group((context, line, targets) -> {
            SkillCastImpl cast = new SkillCastImpl(runtime, context, line.args, null);
            String stance = cast.string("stance", "", "s");
            context.state.stance = stance.isBlank() ? null : stance.trim();
        }, true), "setstance", "stance");
        r.mechanic(each(runtime, (cast, target) -> {
            if (cast.caster() instanceof Mob && target instanceof LivingEntity && target != cast.caster()) {
                ((Mob) cast.caster()).setTarget((LivingEntity) target);
                return true;
            }
            return false;
        }, null, false), "settarget", "target");
        r.mechanic(group((context, line, targets) -> {
            if (context.caster instanceof Mob) {
                ((Mob) context.caster).setTarget(null);
            }
        }, true), "cleartarget", "untarget");
        r.mechanic(living(runtime, (cast, target) -> {
            if (target instanceof Player) {
                return false;
            }
            target.setAI(cast.bool("ai", false, "enabled", "e"));
            return true;
        }), "setai");
        r.mechanic(living(runtime, (cast, target) -> Auras.start(runtime, cast, target)), "aura", "buff", "debuff");

        // --- the model (ours, and ModelEngine's names) --------------------
        r.mechanic(each(runtime, (cast, target) -> {
            String name = cast.string("animation", "", "a", "anim", "state", "s", "");
            if (name.isBlank()) {
                return false;
            }
            Optional<MobState> state = MobState.parse(name);
            if (state.isPresent() && runtime.host.mobs().info(runtime.host.mobs().idOf(target).orElse(null))
                    .map(info -> info.animations().containsKey(state.get())).orElse(false)) {
                return runtime.host.mobs().play(target, state.get());
            }
            return runtime.host.mobs().play(target, name, cast.number("duration", 0, "d") / 20.0);
        }, null, true), "animate", "animation", "playanimation", "state", "anim");
        r.mechanic(each(runtime, (cast, target) -> {
            int rgb = Effects.rgb(cast.string("color", "#FF0000", "c", "colour")).orElse(0xFF0000);
            return runtime.host.tint(target, rgb, Math.max(1, cast.integer("duration", 20, "d")));
        }, null, true), "tint");
    }

    /** A line-at-once mechanic. */
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

    /** What a metaskill's lines inherit when the call named no targeter: the trigger, else the caster. */
    private static List<Object> defaultTargets(SkillContext context) {
        return List.of(context.trigger != null ? context.trigger : context.caster);
    }

    static boolean damage(SkillRuntime runtime, SkillCastImpl cast, LivingEntity target, double amount) {
        if (amount <= 0 || target.isDead()) {
            return false;
        }
        if (target instanceof Player) {
            org.bukkit.GameMode mode = ((Player) target).getGameMode();
            if (mode == org.bukkit.GameMode.CREATIVE || mode == org.bukkit.GameMode.SPECTATOR) {
                return false;
            }
        }
        boolean[] dealing = runtime.skillDamage.get();
        boolean was = dealing[0];
        dealing[0] = true;
        try {
            if (cast.bool("ignorearmor", false, "ia", "ignorearmour", "ignoreall")) {
                double left = target.getHealth() - amount;
                if (left > 0) {
                    target.setHealth(left);
                    target.damage(0, cast.caster());
                } else {
                    target.damage(target.getHealth() + target.getAbsorptionAmount() + 1000, cast.caster());
                }
            } else {
                // No hurt cooldown between a skill's hits: MythicMobs' hits land even mid-flinch.
                if (cast.bool("ignoreinvulnerability", true, "ii")) {
                    target.setNoDamageTicks(0);
                }
                target.damage(amount, cast.caster());
            }
        } finally {
            dealing[0] = was;
        }
        return true;
    }

    private static boolean heal(LivingEntity target, double amount, boolean overheal) {
        if (amount <= 0 || target.isDead()) {
            return false;
        }
        double max = SkillMath.maxHealth(target);
        double to = target.getHealth() + amount;
        if (to > max && overheal) {
            try {
                target.setAbsorptionAmount(target.getAbsorptionAmount() + (to - max));
            } catch (IllegalArgumentException ignored) {
                // Past max_absorption; the overflow is lost.
            }
        }
        target.setHealth(Math.min(max, to));
        return true;
    }

    /** A percentage written as 0.25 or 25. */
    private static double fraction(double value) {
        return value > 1 ? value / 100 : value;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double attribute(LivingEntity body, String name) {
        Optional<Attribute> attribute = Vanilla.attribute(name);
        AttributeInstance instance = attribute.map(body::getAttribute).orElse(null);
        return instance == null ? 0 : instance.getValue();
    }

    /** Potion names, MythicMobs' old Bukkit spellings included. */
    private static final Map<String, String> LEGACY_POTIONS = Map.ofEntries(
            Map.entry("slow", "slowness"), Map.entry("fast_digging", "haste"),
            Map.entry("slow_digging", "mining_fatigue"), Map.entry("increase_damage", "strength"),
            Map.entry("heal", "instant_health"), Map.entry("harm", "instant_damage"),
            Map.entry("jump", "jump_boost"), Map.entry("confusion", "nausea"),
            Map.entry("damage_resistance", "resistance"));

    static Optional<PotionEffectType> effect(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        Optional<PotionEffectType> direct = Vanilla.effect(LEGACY_POTIONS.getOrDefault(key, key));
        if (direct.isPresent()) {
            return direct;
        }
        // 1.20.4 and older call it jump; newer call it jump_boost. Try the other spelling.
        if (key.equals("jump_boost")) {
            return Vanilla.effect("jump");
        }
        return Vanilla.effect(key);
    }

    private static PotionEffect potion(String name, int duration, int level, boolean ambient, boolean particles) {
        return new PotionEffect(effect(name).orElseThrow(), duration, level, ambient, particles);
    }

    /** The speed attribute times {@code multiplier} for {@code ticks}, then back. Overlaps keep the first base. */
    private static final Map<java.util.UUID, Double> BASE_SPEED = new ConcurrentHashMap<>();

    private static boolean speed(SkillRuntime runtime, LivingEntity target, double multiplier, int ticks) {
        AttributeInstance speed = Vanilla.attribute("movement_speed").map(target::getAttribute).orElse(null);
        if (speed == null || multiplier <= 0) {
            return false;
        }
        double base = BASE_SPEED.computeIfAbsent(target.getUniqueId(), id -> speed.getBaseValue());
        speed.setBaseValue(base * multiplier);
        if (ticks > 0) {
            Tasks.onLater(runtime.plugin, target, () -> {
                Double original = BASE_SPEED.remove(target.getUniqueId());
                if (original != null) {
                    speed.setBaseValue(original);
                }
            }, () -> BASE_SPEED.remove(target.getUniqueId()), ticks);
        }
        return true;
    }

    private static Optional<Location> coordinates(String text, World world) {
        String[] parts = text.split(",");
        if (parts.length < 3) {
            return Optional.empty();
        }
        try {
            World in = parts.length > 3 && org.bukkit.Bukkit.getWorld(parts[3].trim()) != null
                    ? org.bukkit.Bukkit.getWorld(parts[3].trim()) : world;
            return Optional.of(new Location(in, Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static String colour(String text) {
        return ChatColor.translateAlternateColorCodes('&',
                ai.resourcepack.engine.core.textfx.TextEffectCatalogue.formatInstalled(text));
    }

    /**
     * {@code summon{type=zoo:minion;amount=3;radius=4}}: a custom mob (its
     * id, or a bare name in the caster's pack) or a vanilla one, round the
     * target. What it summons remembers who did, for {@code @parent} and
     * {@code @children}.
     */
    private static boolean summon(SkillRuntime runtime, SkillCastImpl cast, Location place) {
        String type = cast.string("type", "", "t", "mob", "m");
        if (type.isBlank() || place.getWorld() == null) {
            return false;
        }
        int amount = Math.max(1, Math.min(32, cast.integer("amount", 1, "a")));
        double radius = cast.number("radius", cast.number("noise", 0, "n"), "r", "spread");
        Optional<ContentId> mob = customMob(runtime, type, cast.context.mob);
        EntityType vanilla = null;
        if (mob.isEmpty()) {
            try {
                vanilla = EntityType.valueOf(type.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
            } catch (IllegalArgumentException e) {
                runtime.warnOnce("summon:" + type, "summon{type=" + type + "} is no custom mob and no vanilla mob.");
                return false;
            }
        }
        for (int i = 0; i < amount; i++) {
            Location at = radius > 0 ? SkillMath.scatter(place, 0, radius, ThreadLocalRandom.current()) : place.clone();
            at.setY(place.getY() + cast.number("yoffset", 0, "y"));
            Entity spawned = mob.isPresent() ? runtime.host.mobs().spawn(at, mob.get()).orElse(null)
                    : place.getWorld().spawnEntity(at, vanilla);
            if (spawned == null) {
                continue;
            }
            spawned.getPersistentDataContainer().set(runtime.parentKey, PersistentDataType.STRING,
                    cast.caster().getUniqueId().toString());
            cast.context.state.children.add(spawned.getUniqueId());
            if (cast.context.state.children.size() > 256) {
                cast.context.state.children.remove(0);
            }
            if (spawned instanceof Mob && cast.caster() instanceof Mob && ((Mob) cast.caster()).getTarget() != null) {
                ((Mob) spawned).setTarget(((Mob) cast.caster()).getTarget());
            }
        }
        return true;
    }

    static Optional<ContentId> customMob(SkillRuntime runtime, String written, ContentId from) {
        String text = written.trim().toLowerCase(Locale.ROOT);
        if (text.indexOf(':') > 0) {
            Optional<ContentId> id = ContentId.parse(text);
            return id.filter(found -> runtime.host.mobs().info(found).isPresent());
        }
        if (from != null) {
            Optional<ContentId> own = ContentId.of(from.namespace(), text);
            if (own.isPresent() && runtime.host.mobs().info(own.get()).isPresent()) {
                return own;
            }
        }
        for (ContentId id : runtime.host.mobs().ids()) {
            if (id.path().equals(text)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** One projectile toward {@code place}, marked so the engine applies its damage and its hit is not ~onAttack. */
    private static boolean shoot(SkillRuntime runtime, SkillCastImpl cast, Location place, String type) {
        LivingEntity self = cast.caster();
        if (!self.isValid()) {
            return false;
        }
        Location eye = self.getEyeLocation();
        Vector direction = place.toVector().add(new Vector(0, 1, 0)).subtract(eye.toVector());
        if (direction.lengthSquared() < 1.0E-6) {
            direction = eye.getDirection();
        }
        String kind = type.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        double velocity = cast.number("velocity", 1.6, "v");
        boolean thrown = kind.equals("arrow") || kind.equals("snowball") || kind.equals("trident")
                || kind.equals("egg");
        if (thrown) {
            double flat = Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ());
            direction.setY(direction.getY() + flat * 0.2 * (1.6 / Math.max(0.5, velocity)));
        }
        direction.normalize();
        Projectile fired;
        switch (kind) {
            case "snowball":
                fired = self.launchProjectile(org.bukkit.entity.Snowball.class, direction.multiply(velocity));
                break;
            case "egg":
                fired = self.launchProjectile(org.bukkit.entity.Egg.class, direction.multiply(velocity));
                break;
            case "trident":
                fired = self.launchProjectile(org.bukkit.entity.Trident.class, direction.multiply(velocity));
                break;
            case "small_fireball":
            case "smallfireball":
                fired = self.launchProjectile(org.bukkit.entity.SmallFireball.class);
                ((Fireball) fired).setDirection(direction);
                break;
            case "fireball":
            case "large_fireball":
                fired = self.launchProjectile(org.bukkit.entity.LargeFireball.class);
                ((Fireball) fired).setDirection(direction);
                ((Fireball) fired).setYield((float) cast.number("yield", 1, "y"));
                ((Fireball) fired).setIsIncendiary(cast.bool("incendiary", false, "i", "fire"));
                break;
            case "wither_skull":
            case "witherskull":
            case "skull":
                fired = self.launchProjectile(org.bukkit.entity.WitherSkull.class);
                ((Fireball) fired).setDirection(direction);
                break;
            default:
                fired = self.launchProjectile(org.bukkit.entity.Arrow.class, direction.multiply(velocity));
                break;
        }
        if (fired instanceof org.bukkit.entity.AbstractArrow) {
            ((org.bukkit.entity.AbstractArrow) fired).setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        }
        fired.getPersistentDataContainer().set(runtime.shotKey, PersistentDataType.DOUBLE,
                cast.number("damage", 0, "d") * cast.power());
        fired.getPersistentDataContainer().set(runtime.skillShotKey, PersistentDataType.BYTE, (byte) 1);
        return true;
    }

    /** {@code vfx{id=studio:shockwave;scale=2;follow=true;loop=60}}: a Studio or content-folder VFX. */
    private static boolean vfx(SkillRuntime runtime, SkillCastImpl cast, Location place) {
        ai.resourcepack.engine.api.Vfx vfx = runtime.host.vfx();
        String name = cast.string("id", "", "vfx", "effect", "e", "name", "n", "");
        if (vfx == null || name.isBlank()) {
            return false;
        }
        Optional<VfxInfo> info = vfx.find(name);
        if (info.isEmpty()) {
            runtime.warnOnce("vfx:" + name, "No VFX " + name + " (a mob skill's vfx{id=...}).");
            return false;
        }
        VfxOptions.Builder options = VfxOptions.builder()
                .scale(Math.max(0.05, cast.number("scale", 1)))
                .speed(Math.max(0.05, cast.number("speed", 1)));
        int loop = cast.integer("loop", 0, "duration", "d");
        if (loop > 0) {
            options.loopFor(loop);
        }
        String tag = cast.string("tag", "");
        if (!tag.isBlank()) {
            options.tag(tag);
        }
        if (cast.bool("follow", false, "f")) {
            Entity follow = cast.entity() != null ? cast.entity() : cast.caster();
            options.follow(follow);
        }
        place.setYaw(cast.caster().getLocation().getYaw());
        return vfx.play(info.get().id(), place, null, options.build()).isPresent();
    }
}
