package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The built-in targeters, under MythicMobs' names and aliases, and the options
 * every targeter shares: {@code limit}, {@code sort}, offsets and
 * {@code conditions}.
 */
final class Targeters {

    private Targeters() {
    }

    /** The most entities a radius targeter returns before {@code limit}: a server-wide @PIW is still bounded. */
    static final int CAP = 128;

    static List<Object> resolve(SkillRuntime runtime, SkillContext context, SkillLine.Ref ref) {
        SkillRegistry.Targeter targeter = runtime.registry.findTargeter(ref.name);
        if (targeter == null) {
            runtime.warnOnce("targeter:" + ref.name, "No targeter @" + ref.name + "; that line aims at nothing.");
            return List.of();
        }
        SkillCastImpl cast = new SkillCastImpl(runtime, context, ref.args, null);
        List<Object> found;
        try {
            found = new ArrayList<>(targeter.find(cast));
        } catch (RuntimeException e) {
            runtime.warnOnce("tthrew:" + ref.name, "Targeter @" + ref + " failed: " + e);
            return List.of();
        }
        return options(runtime, context, cast, found);
    }

    /** The shared options, in MythicMobs' order: filter, sort, limit, then offsets. */
    private static List<Object> options(SkillRuntime runtime, SkillContext context, SkillCastImpl cast,
                                        List<Object> found) {
        String conditions = cast.raw("conditions", "cond");
        if (conditions != null && found.size() > 0) {
            List<SkillLine.Cond> parsed = new ArrayList<>();
            for (String text : SkillParser.inline(conditions.trim().startsWith("[") ? conditions : "[ - " + conditions + " ]")) {
                SkillParser.Parsed p = SkillParser.condition(text, false);
                if (p.ok()) {
                    parsed.add(p.line.conditions.get(0));
                }
            }
            found.removeIf(target -> {
                for (SkillLine.Cond cond : parsed) {
                    boolean pass = runtime.test(context, cond, target) != cond.negate;
                    if (pass == cond.action.equals("false")) {
                        return true;
                    }
                }
                return false;
            });
        }
        String ignore = cast.raw("ignore", "ignoretypes");
        if (ignore != null) {
            List<String> types = List.of(ignore.toUpperCase(Locale.ROOT).split(","));
            found.removeIf(target -> target instanceof Entity && types.contains(((Entity) target).getType().name()));
        }
        String sort = cast.string("sort", "", "sortby").toUpperCase(Locale.ROOT);
        Location from = context.casterLocation();
        switch (sort) {
            case "NEAREST":
                found.sort(Comparator.comparingDouble(t -> distance(from, t)));
                break;
            case "FURTHEST":
                found.sort(Comparator.comparingDouble((Object t) -> distance(from, t)).reversed());
                break;
            case "RANDOM":
                Collections.shuffle(found, ThreadLocalRandom.current());
                break;
            case "LOWEST_HEALTH":
                found.sort(Comparator.comparingDouble(Targeters::health));
                break;
            case "HIGHEST_HEALTH":
                found.sort(Comparator.comparingDouble(Targeters::health).reversed());
                break;
            default:
                break;
        }
        int limit = cast.integer("limit", 0, "lim");
        if (limit > 0 && found.size() > limit) {
            found = new ArrayList<>(found.subList(0, limit));
        }
        double xo = cast.number("xoffset", 0, "xo");
        double yo = cast.number("yoffset", 0, "yo");
        double zo = cast.number("zoffset", 0, "zo");
        double fo = cast.number("forwardoffset", 0, "fo", "foffset");
        double so = cast.number("sideoffset", 0, "so", "soffset");
        if (xo != 0 || yo != 0 || zo != 0 || fo != 0 || so != 0) {
            List<Object> moved = new ArrayList<>();
            for (Object target : found) {
                if (target instanceof Location) {
                    Location at = (Location) target;
                    if (fo != 0 || so != 0) {
                        Location facing = at.clone();
                        facing.setYaw(from.getYaw());
                        at = SkillMath.forward(facing, fo, so);
                    }
                    moved.add(at.add(xo, yo, zo));
                } else {
                    moved.add(target);
                }
            }
            found = moved;
        }
        return found;
    }

    private static double distance(Location from, Object target) {
        Location at = target instanceof Entity ? ((Entity) target).getLocation() : (Location) target;
        return at.getWorld() != from.getWorld() ? Double.MAX_VALUE : at.distanceSquared(from);
    }

    private static double health(Object target) {
        return target instanceof LivingEntity ? ((LivingEntity) target).getHealth() : Double.MAX_VALUE;
    }

    private static boolean targetable(Entity entity) {
        if (!entity.isValid() || entity.isDead()) {
            return false;
        }
        return !(entity instanceof Player) || ((Player) entity).getGameMode() != GameMode.SPECTATOR;
    }

    /** Living things within {@code radius} of {@code centre} that pass {@code keep}, never the caster. */
    private static List<Object> near(SkillCastImpl cast, Location centre, double radius, Predicate<Entity> keep) {
        List<Object> out = new ArrayList<>();
        World world = centre.getWorld();
        if (world == null || radius <= 0) {
            return out;
        }
        double r = Math.min(radius, 256);
        boolean self = cast.bool("targetself", false, "self", "ts");
        for (Entity entity : world.getNearbyEntities(centre, r, r, r)) {
            if ((entity == cast.caster() && !self) || !targetable(entity) || !keep.test(entity)) {
                continue;
            }
            if (entity.getLocation().distanceSquared(centre) <= r * r) {
                out.add(entity);
                if (out.size() >= CAP) {
                    break;
                }
            }
        }
        return out;
    }

    private static Optional<Entity> parent(SkillRuntime runtime, Entity child) {
        String written = child.getPersistentDataContainer().get(runtime.parentKey, PersistentDataType.STRING);
        if (written == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(runtime.plugin.getServer().getEntity(UUID.fromString(written)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static LivingEntity mobTarget(SkillCastImpl cast) {
        return cast.caster() instanceof Mob ? ((Mob) cast.caster()).getTarget() : null;
    }

    private static List<Object> one(Object target) {
        return target == null ? new ArrayList<>() : new ArrayList<>(List.of(target));
    }

    static void install(SkillRuntime runtime) {
        SkillRegistry r = runtime.registry;

        // --- single entities ---------------------------------------------
        r.targeter(cast -> one(cast.caster()), "self", "caster", "boss", "mob");
        r.targeter(cast -> one(mobTarget(cast)), "target", "t");
        r.targeter(cast -> one(cast.context.trigger), "trigger");
        r.targeter(cast -> {
            List<Object> players = near(cast, cast.context.casterLocation(), cast.number("radius", 32, "r"),
                    entity -> entity instanceof Player);
            Location from = cast.context.casterLocation();
            players.sort(Comparator.comparingDouble(t -> distance(from, t)));
            return players.isEmpty() ? new ArrayList<>() : one(players.get(0));
        }, "nearestplayer", "np");
        r.targeter(cast -> one(parent(runtime, cast.caster()).orElse(null)), "parent", "summoner", "owner");
        r.targeter(cast -> {
            List<Object> out = new ArrayList<>();
            for (UUID child : cast.context.state.children) {
                Entity entity = runtime.plugin.getServer().getEntity(child);
                if (entity != null && targetable(entity)) {
                    out.add(entity);
                }
            }
            return out;
        }, "children", "child", "summons");

        // --- many entities ------------------------------------------------
        r.targeter(cast -> near(cast, cast.context.casterLocation(), cast.number("radius", 5, "r"),
                entity -> entity instanceof Player), "playersinradius", "pir", "pinr");
        r.targeter(cast -> near(cast, cast.context.casterLocation(), cast.number("radius", 5, "r"),
                entity -> true), "entitiesinradius", "eir", "einr", "e");
        r.targeter(cast -> near(cast, cast.context.casterLocation(), cast.number("radius", 5, "r"),
                entity -> entity instanceof LivingEntity), "livinginradius", "lir", "livingentitiesinradius", "leir");
        r.targeter(cast -> {
            String types = cast.string("types", "", "type", "t", "mobtypes").toLowerCase(Locale.ROOT);
            List<String> wanted = types.isBlank() ? List.of() : List.of(types.split(","));
            return near(cast, cast.context.casterLocation(), cast.number("radius", 5, "r"), entity -> {
                if (!(entity instanceof LivingEntity) || entity instanceof Player) {
                    return false;
                }
                if (wanted.isEmpty()) {
                    return true;
                }
                Optional<ContentId> id = runtime.host.mobs().idOf(entity);
                for (String type : wanted) {
                    String t = type.trim();
                    if (id.isPresent() && (id.get().toString().equals(t) || id.get().path().equals(t))) {
                        return true;
                    }
                    if (entity.getType().name().equalsIgnoreCase(t)) {
                        return true;
                    }
                }
                return false;
            });
        }, "mobsinradius", "mir", "minr");
        r.targeter(cast -> {
            Location from = cast.context.casterLocation();
            List<Object> out = new ArrayList<>();
            if (from.getWorld() != null) {
                for (Player player : from.getWorld().getPlayers()) {
                    if (targetable(player)) {
                        out.add(player);
                    }
                }
            }
            return out;
        }, "playersinworld", "world", "piw");
        r.targeter(cast -> {
            List<Object> out = new ArrayList<>();
            for (Player player : runtime.plugin.getServer().getOnlinePlayers()) {
                if (targetable(player)) {
                    out.add(player);
                }
            }
            return out;
        }, "playersonserver", "server", "everyone");
        r.targeter(cast -> {
            Location from = cast.context.casterLocation();
            double angle = cast.number("angle", 45, "a");
            List<Object> found = near(cast, from, cast.number("range", 8, "r", "radius"),
                    entity -> entity instanceof LivingEntity);
            found.removeIf(t -> !SkillMath.inCone(from, ((Entity) t).getLocation(), angle));
            return found;
        }, "livingincone", "lic", "entitiesincone", "eic", "playersincone");
        r.targeter(cast -> {
            Location from = cast.context.casterLocation();
            double min = cast.number("min", 0, "minradius", "minr");
            List<Object> found = near(cast, from, cast.number("max", 10, "maxradius", "maxr", "radius", "r"),
                    entity -> entity instanceof LivingEntity);
            found.removeIf(t -> ((Entity) t).getLocation().distanceSquared(from) < min * min);
            return found;
        }, "entitiesinring", "eirr", "livinginring");
        r.targeter(cast -> {
            Location from = cast.context.casterLocation();
            double min = cast.number("min", 0, "minradius", "minr");
            List<Object> found = near(cast, from, cast.number("max", 10, "maxradius", "maxr", "radius", "r"),
                    entity -> entity instanceof Player);
            found.removeIf(t -> ((Entity) t).getLocation().distanceSquared(from) < min * min);
            return found;
        }, "playersinring", "pirr");
        r.targeter(cast -> one(mobTarget(cast)), "threattable", "tt", "randomthreattarget", "rtt");

        // --- places -------------------------------------------------------
        r.targeter(cast -> one(cast.context.casterLocation()), "selflocation", "casterlocation", "selfloc",
                "bosslocation", "moblocation");
        r.targeter(cast -> one(cast.caster().isValid() ? cast.caster().getEyeLocation()
                : cast.context.casterLocation().add(0, 1.6, 0)), "selfeyelocation", "eyelocation",
                "castereyelocation", "sel");
        r.targeter(cast -> one(SkillMath.forward(cast.context.casterLocation(), cast.number("forward", 5, "f"),
                cast.number("side", 0, "s")).add(0, cast.number("y", 0), 0)), "forward");
        r.targeter(cast -> {
            LivingEntity target = mobTarget(cast);
            return target == null ? new ArrayList<>() : one(target.getLocation());
        }, "targetlocation", "tl", "targetloc");
        r.targeter(cast -> cast.context.trigger == null ? new ArrayList<>()
                : one(cast.context.trigger.getLocation()), "triggerlocation", "triggerloc");
        r.targeter(cast -> {
            String coordinates = cast.string("coordinates", "", "c", "coords", "location", "l");
            String[] parts = coordinates.split(",");
            if (parts.length < 3) {
                return new ArrayList<>();
            }
            try {
                Location from = cast.context.casterLocation();
                return one(new Location(from.getWorld(), Double.parseDouble(parts[0].trim()),
                        Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim())));
            } catch (NumberFormatException e) {
                return new ArrayList<>();
            }
        }, "location", "loc", "l");
        r.targeter(cast -> one(cast.context.casterLocation()), "origin", "source");
        r.targeter(cast -> {
            Object[] at = cast.context.state.spawnedAt;
            Location here = cast.context.casterLocation();
            if (at == null || at[0] == null || here.getWorld() == null || !here.getWorld().getName().equals(at[0])) {
                return one(here);
            }
            return one(new Location(here.getWorld(), (double) at[1], (double) at[2], (double) at[3]));
        }, "spawnlocation", "casterspawnlocation", "spawnloc");
        r.targeter(cast -> new ArrayList<>(SkillMath.ring(cast.context.casterLocation(),
                cast.number("radius", 5, "r"), Math.min(128, cast.integer("points", 8, "p")))), "ring");
        r.targeter(cast -> new ArrayList<>(SkillMath.cone(cast.context.casterLocation(),
                cast.number("angle", 45, "a"), cast.number("range", 8, "r"),
                Math.min(128, cast.integer("points", 16, "p")))), "cone");
        r.targeter(cast -> new ArrayList<>(SkillMath.sphere(cast.context.casterLocation(),
                cast.number("radius", 5, "r"), Math.min(128, cast.integer("points", 20, "p", "amount", "a")))),
                "sphere");
        r.targeter(cast -> scatter(cast, cast.context.casterLocation()), "randomlocationsnearcaster", "rlnc",
                "randomlocationsnearself");
        r.targeter(cast -> {
            LivingEntity target = mobTarget(cast);
            Entity around = target != null ? target : cast.context.trigger;
            return around == null ? new ArrayList<>() : scatter(cast, around.getLocation());
        }, "randomlocationsneartargets", "rlnt", "randomlocationsneartarget");
        r.targeter(cast -> {
            List<Object> out = new ArrayList<>();
            for (Object player : near(cast, cast.context.casterLocation(), cast.number("radius", 5, "r"),
                    entity -> entity instanceof Player)) {
                out.add(((Entity) player).getLocation());
            }
            return out;
        }, "playerlocationsinradius", "plir");
    }

    private static List<Object> scatter(SkillCastImpl cast, Location centre) {
        List<Object> out = new ArrayList<>();
        int amount = Math.max(1, Math.min(64, cast.integer("amount", 1, "a")));
        double min = cast.number("minradius", 0, "minr");
        double max = cast.number("radius", 5, "r", "maxradius", "maxr");
        for (int i = 0; i < amount; i++) {
            out.add(SkillMath.scatter(centre, min, max, ThreadLocalRandom.current()));
        }
        return out;
    }

    /** For tests: every entity/location in {@code found} that is an entity. */
    static List<Entity> entities(Collection<Object> found) {
        List<Entity> out = new ArrayList<>();
        for (Object o : found) {
            if (o instanceof Entity) {
                out.add((Entity) o);
            }
        }
        return out;
    }
}
