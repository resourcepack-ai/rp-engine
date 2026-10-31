package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The built-in conditions, under MythicMobs' names and aliases. Each tests
 * {@code cast.target}: the caster for a {@code Conditions:} list or an inline
 * {@code ?}, the trigger for {@code ?~} and {@code TriggerConditions:}, each
 * candidate for {@code TargetConditions:} and a targeter's {@code conditions}.
 */
final class Conditions {

    private Conditions() {
    }

    /** Dusk and dawn as the engine's spawning reads them, so "night" means one thing everywhere. */
    static final long DUSK = 12300;
    static final long DAWN = 23850;

    private static Entity subject(SkillCastImpl cast) {
        return cast.entity();
    }

    private static boolean ranged(SkillCastImpl cast, double value, String... keys) {
        String written = cast.raw(keys[0], java.util.Arrays.copyOfRange(keys, 1, keys.length));
        if (written == null) {
            return true;
        }
        Optional<Ranged> range = Ranged.parse(cast.fill(written));
        return range.isPresent() && range.get().test(value);
    }

    /** The "other end" of a distance: the target being tested, else the caster's target. */
    private static Location other(SkillCastImpl cast) {
        if (cast.target instanceof Location) {
            return (Location) cast.target;
        }
        Entity subject = subject(cast);
        if (subject != null && subject != cast.caster()) {
            return subject.getLocation();
        }
        if (cast.caster() instanceof Mob && ((Mob) cast.caster()).getTarget() != null) {
            return ((Mob) cast.caster()).getTarget().getLocation();
        }
        return null;
    }

    private static double distance(Location a, Location b) {
        if (a == null || b == null || a.getWorld() != b.getWorld()) {
            return Double.MAX_VALUE;
        }
        return a.distance(b);
    }

    private static long dayTime(SkillCastImpl cast) {
        World world = cast.location().getWorld();
        return world == null ? 6000 : ((world.getTime() % 24000) + 24000) % 24000;
    }

    private static boolean playerNear(SkillCastImpl cast, double radius) {
        Location at = cast.location();
        if (at.getWorld() == null) {
            return false;
        }
        for (Player player : at.getWorld().getPlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR && player.getLocation().distanceSquared(at) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    /** {@code d=10}, {@code d=<10}: the reach a within-condition is given. */
    private static double reach(SkillCastImpl cast) {
        String written = cast.string("distance", "10", "d", "radius", "r");
        Optional<Ranged> range = Ranged.parse(written);
        if (range.isEmpty()) {
            return 10;
        }
        return range.get().op == '>' ? range.get().low : range.get().high;
    }

    static boolean glob(String pattern, String text) {
        String p = pattern.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (p.indexOf('*') < 0) {
            return p.equals(text);
        }
        String regex = java.util.regex.Pattern.quote(p).replace("*", "\\E.*\\Q");
        return text.matches(regex);
    }

    /**
     * The variable a condition names on its subject (or, with
     * {@code var=caster.x}, on the caster): null when it is unset.
     */
    private static String variableOf(SkillRuntime runtime, SkillCastImpl cast) {
        String written = cast.string("variable", "", "var", "name", "n", "key", "k").trim().toLowerCase(Locale.ROOT);
        Entity holder = subject(cast);
        if (written.startsWith("caster.")) {
            holder = cast.caster();
        } else if (written.startsWith("target.")) {
            written = written.substring("target.".length());
        }
        return runtime.variable(holder, written);
    }

    static void install(SkillRuntime runtime) {
        SkillRegistry r = runtime.registry;

        r.condition(cast -> subject(cast) instanceof LivingEntity
                && ranged(cast, ((LivingEntity) subject(cast)).getHealth(), "health", "h", "amount", "a"), "health", "hp");
        r.condition(cast -> {
            if (!(subject(cast) instanceof LivingEntity)) {
                return false;
            }
            LivingEntity body = (LivingEntity) subject(cast);
            double max = SkillMath.maxHealth(body);
            double share = max <= 0 ? 0 : body.getHealth() / max;
            String written = cast.raw("health", "h", "percent", "p", "amount", "a");
            if (written == null) {
                return true;
            }
            Optional<Ranged> range = Ranged.parse(cast.fill(written));
            if (range.isEmpty()) {
                return false;
            }
            // 50% and 0.5 both mean half.
            boolean percent = range.get().percent || Math.abs(range.get().op == '>' ? range.get().low : range.get().high) > 1;
            return range.get().test(percent ? share * 100 : share);
        }, "healthpercent", "hppercent", "percenthealth", "hp%");
        r.condition(cast -> ranged(cast, distance(cast.context.casterLocation(), other(cast)), "distance", "d"),
                "distance");
        r.condition(cast -> {
            LivingEntity target = cast.caster() instanceof Mob ? ((Mob) cast.caster()).getTarget() : null;
            return target != null && distance(cast.context.casterLocation(), target.getLocation()) <= reach(cast);
        }, "targetwithin", "targetinrange");
        r.condition(cast -> {
            LivingEntity target = cast.caster() instanceof Mob ? ((Mob) cast.caster()).getTarget() : null;
            return target != null && distance(cast.context.casterLocation(), target.getLocation()) > reach(cast);
        }, "targetnotwithin");
        r.condition(cast -> playerNear(cast, reach(cast)), "playerwithin", "playerswithin", "playersnearby");
        r.condition(cast -> !playerNear(cast, reach(cast)), "playernotwithin", "playersnotwithin");
        r.condition(cast -> {
            Location at = cast.location();
            double radius = cast.number("radius", 10, "r");
            int count = 0;
            if (at.getWorld() != null) {
                for (Player player : at.getWorld().getPlayers()) {
                    if (player.getGameMode() != GameMode.SPECTATOR && player.getLocation().distanceSquared(at) <= radius * radius) {
                        count++;
                    }
                }
            }
            return ranged(cast, count, "amount", "a");
        }, "playersinradius", "pir");
        r.condition(cast -> ThreadLocalRandom.current().nextDouble() < cast.number("chance", 0.5, "c"), "chance");
        r.condition(cast -> {
            Entity subject = subject(cast);
            String stance = subject == cast.caster() ? cast.context.state.stance : runtime.stanceOf(subject);
            String wanted = cast.string("stance", "", "s");
            if (wanted.isBlank()) {
                return stance == null || stance.isBlank();
            }
            for (String one : wanted.split(",")) {
                if (one.trim().equalsIgnoreCase(stance == null ? "" : stance)) {
                    return true;
                }
            }
            return false;
        }, "stance");
        r.condition(cast -> {
            long t = dayTime(cast);
            return t < DUSK || t >= DAWN;
        }, "day", "isday");
        r.condition(cast -> {
            long t = dayTime(cast);
            return t >= DUSK && t < DAWN;
        }, "night", "isnight");
        r.condition(cast -> {
            long t = dayTime(cast);
            return t >= 12000 && t < 14000;
        }, "dusk");
        r.condition(cast -> {
            long t = dayTime(cast);
            return t >= 22000 || t < 1000;
        }, "dawn");
        r.condition(cast -> ranged(cast, dayTime(cast), "time", "t"), "worldtime", "time");
        r.condition(cast -> {
            Location at = cast.location();
            if (at.getWorld() == null) {
                return false;
            }
            Object biome = at.getWorld().getBiome(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            String name = biome instanceof Keyed ? ((Keyed) biome).getKey().getKey() : String.valueOf(biome);
            String bare = name.toLowerCase(Locale.ROOT);
            for (String one : cast.string("biome", "", "b").split(",")) {
                if (!one.isBlank() && glob(one, bare)) {
                    return true;
                }
            }
            return false;
        }, "biome");
        r.condition(cast -> {
            World world = cast.location().getWorld();
            if (world == null) {
                return false;
            }
            for (String one : cast.string("world", "", "w").split(",")) {
                if (one.trim().equalsIgnoreCase(world.getName())) {
                    return true;
                }
            }
            return false;
        }, "world");
        r.condition(cast -> subject(cast) != null && subject(cast).isOnGround(), "onground", "grounded");
        r.condition(cast -> {
            Block block = cast.location().getBlock();
            String wanted = cast.string("material", "", "m", "type", "t");
            if (wanted.isBlank()) {
                return !block.isPassable();
            }
            for (String one : wanted.split(",")) {
                Material material = Material.matchMaterial(one.trim());
                if (material != null && block.getType() == material) {
                    return true;
                }
            }
            return false;
        }, "inblock");
        r.condition(cast -> {
            Location at = cast.location();
            return at.getWorld() != null && at.getWorld().getHighestBlockYAt(at) <= at.getBlockY();
        }, "outside", "insky");
        r.condition(cast -> {
            Location at = cast.location();
            return at.getWorld() != null && at.getWorld().getHighestBlockYAt(at) > at.getBlockY();
        }, "inside");
        r.condition(cast -> {
            if (!(subject(cast) instanceof LivingEntity)) {
                return false;
            }
            LivingEntity body = (LivingEntity) subject(cast);
            String type = cast.string("type", "", "t", "potion", "p");
            if (type.isBlank()) {
                return !body.getActivePotionEffects().isEmpty();
            }
            return Mechanics.effect(type).map(body::hasPotionEffect).orElse(false);
        }, "haspotioneffect", "haspotion", "haspotioneffects");
        r.condition(cast -> {
            Entity subject = subject(cast);
            return subject != null && subject != cast.caster() && cast.caster().isValid()
                    && cast.caster().hasLineOfSight(subject);
        }, "lineofsight", "inlineofsight", "haslineofsight");
        r.condition(cast -> {
            Entity subject = subject(cast);
            if (subject == null) {
                return false;
            }
            for (String one : cast.string("types", "", "type", "t").split(",")) {
                if (subject.getType().name().equalsIgnoreCase(one.trim().replace("minecraft:", ""))) {
                    return true;
                }
            }
            return false;
        }, "entitytype", "mobtype.vanilla", "istype");
        r.condition(cast -> {
            Entity subject = subject(cast);
            Optional<ContentId> id = subject == null ? Optional.empty() : runtime.host.mobs().idOf(subject);
            if (id.isEmpty()) {
                return false;
            }
            for (String one : cast.string("types", "", "type", "t", "mobtypes").split(",")) {
                String t = one.trim().toLowerCase(Locale.ROOT);
                if (id.get().toString().equals(t) || id.get().path().equals(t)) {
                    return true;
                }
            }
            return false;
        }, "mobtype", "mythicmobtype", "custommob");
        r.condition(cast -> subject(cast) != null
                && ranged(cast, runtime.host.level(subject(cast)), "level", "l", "lvl"), "level", "moblevel");
        r.condition(cast -> {
            String value = variableOf(runtime, cast);
            String wanted = cast.string("value", "", "val", "v");
            if (value == null) {
                return false;
            }
            try {
                return Double.parseDouble(value.trim()) == Double.parseDouble(wanted.trim());
            } catch (NumberFormatException e) {
                return value.trim().equalsIgnoreCase(wanted.trim());
            }
        }, "variableequals", "varequals", "variableeq", "vareq");
        r.condition(cast -> {
            String value = variableOf(runtime, cast);
            if (value == null) {
                return false;
            }
            Optional<Ranged> range = Ranged.parse(cast.string("value", "", "val", "v", "range", "r"));
            try {
                return range.isPresent() && range.get().test(Double.parseDouble(value.trim()));
            } catch (NumberFormatException e) {
                return false;
            }
        }, "variableinrange", "varinrange", "variablerange", "varrange");
        r.condition(cast -> variableOf(runtime, cast) != null, "variableisset", "varisset", "variableset");
        r.condition(cast -> {
            ThreatTable table = runtime.threatOf(cast.caster());
            Entity subject = subject(cast);
            return table != null && subject != null && subject != cast.caster()
                    && ranged(cast, table.get(subject.getUniqueId()), "threat", "amount", "a", "t");
        }, "threat", "hasthreat", "onthreattable");
        r.condition(cast -> {
            String faction = subject(cast) == null ? null : runtime.host.faction(subject(cast));
            String wanted = cast.string("faction", "", "f");
            if (wanted.isBlank()) {
                return faction != null;
            }
            for (String one : wanted.split(",")) {
                if (one.trim().equalsIgnoreCase(faction == null ? "" : faction)) {
                    return true;
                }
            }
            return false;
        }, "faction", "hasfaction");
        r.condition(cast -> {
            Entity subject = subject(cast);
            String mine = runtime.host.faction(cast.caster());
            return subject != null && mine != null && mine.equals(runtime.host.faction(subject));
        }, "samefaction", "ally");
        r.condition(cast -> subject(cast) instanceof Player, "isplayer", "player");
        r.condition(cast -> subject(cast) instanceof org.bukkit.entity.Enemy, "ismonster", "monster");
        r.condition(cast -> subject(cast) instanceof LivingEntity, "isliving", "living");
        r.condition(cast -> subject(cast) instanceof Mob && ((Mob) subject(cast)).getTarget() != null,
                "incombat", "hastarget", "ontarget");
        r.condition(cast -> {
            World world = cast.location().getWorld();
            return world != null && world.hasStorm();
        }, "raining", "israining");
        r.condition(cast -> {
            World world = cast.location().getWorld();
            return world != null && world.isThundering();
        }, "thundering", "isthundering");
        r.condition(cast -> {
            String skill = cast.string("skill", "", "s");
            return runtime.metaskill(skill, cast.context.mob)
                    .map(found -> cast.context.state.cooling("skill:" + found.id, System.currentTimeMillis()))
                    .orElse(false);
        }, "skilloncooldown", "oncooldown", "cooldown");
        r.condition(cast -> subject(cast) != null && subject(cast).getScoreboardTags()
                .contains(cast.string("tag", "", "t")), "hastag", "scoreboardtag", "tag");
        r.condition(cast -> subject(cast) instanceof Player
                && ((Player) subject(cast)).hasPermission(cast.string("permission", "", "p")),
                "haspermission", "permission");
        r.condition(cast -> {
            if (!(subject(cast) instanceof LivingEntity) || ((LivingEntity) subject(cast)).getEquipment() == null) {
                return false;
            }
            ItemStack held = ((LivingEntity) subject(cast)).getEquipment().getItemInMainHand();
            String wanted = cast.string("material", "", "m", "item", "i", "type", "t");
            for (String one : wanted.split(",")) {
                String t = one.trim();
                if (t.isEmpty()) {
                    continue;
                }
                Optional<ContentId> own = runtime.host.items().idOf(held);
                if (own.isPresent() && own.get().toString().equalsIgnoreCase(t)) {
                    return true;
                }
                Material material = Material.matchMaterial(t);
                if (material != null && held.getType() == material) {
                    return true;
                }
            }
            return false;
        }, "holding", "isholding");
        r.condition(cast -> subject(cast) != null && ranged(cast, subject(cast).getLocation().getY(),
                "height", "h", "y"), "height", "altitude", "y");
        r.condition(cast -> subject(cast) instanceof Player && ((Player) subject(cast)).isSneaking(),
                "sneaking", "crouching", "issneaking");
        r.condition(cast -> subject(cast) instanceof Player && ((Player) subject(cast)).isSprinting(),
                "sprinting", "issprinting");
        r.condition(cast -> subject(cast) instanceof Player && ((Player) subject(cast)).isFlying(), "flying", "isflying");
        r.condition(cast -> {
            if (!(subject(cast) instanceof Player)) {
                return false;
            }
            String mode = ((Player) subject(cast)).getGameMode().name();
            for (String one : cast.string("mode", "", "m", "gamemode").split(",")) {
                if (one.trim().equalsIgnoreCase(mode)) {
                    return true;
                }
            }
            return false;
        }, "gamemode");
        r.condition(cast -> subject(cast) != null && subject(cast).getFireTicks() > 0, "burning", "isburning", "onfire");
        r.condition(cast -> subject(cast) instanceof Player && ((Player) subject(cast)).isBlocking(),
                "blocking", "isblocking");
        r.condition(cast -> subject(cast) != null && (subject(cast).isDead() || !subject(cast).isValid()), "dead", "isdead");
        r.condition(cast -> subject(cast) == cast.caster(), "iscaster", "isself");
        r.condition(cast -> subject(cast) != null && subject(cast).isInsideVehicle(), "mounted", "isriding", "inside.vehicle");
        r.condition(cast -> subject(cast) != null && subject(cast).isInWater(), "inwater", "isinwater");
    }
}
