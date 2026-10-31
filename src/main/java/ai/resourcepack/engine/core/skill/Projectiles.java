package ai.resourcepack.engine.core.skill;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VfxInfo;
import ai.resourcepack.engine.api.VfxOptions;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MythicMobs' skill projectiles: {@code projectile} (flies straight, or
 * falls with {@code gravity}), {@code missile} (turns toward its target),
 * {@code totem} (stands still and pulses) and {@code chain} (jumps from
 * target to target).
 *
 * <p>A projectile is a point the engine moves, not a Minecraft entity: each
 * step it moves {@code velocity} blocks a second for {@code interval} ticks,
 * in half-block sub-steps so it cannot pass through a wall or a mob, runs
 * {@code onTick} at where it is, {@code onHit} on whatever living thing comes
 * within {@code hR}/{@code vR} of it, and {@code onEnd} where it stops (out of
 * range, out of time, into a block, or on its first hit unless it pierces).
 * It can be drawn as an item display ({@code bullettype=item;item=...}) or a
 * looping VFX ({@code bullettype=vfx;vfx=...}); otherwise it is whatever its
 * {@code onTick} draws, which is MythicMobs' default.
 *
 * <p><b>Each step runs on the region that owns the point</b>
 * ({@code Tasks.atLater} at its new place), so on Folia the hit check and the
 * skills it runs touch only that region's entities. A step whose caster has
 * died ends it, and a reload ends every one ({@code generation}).
 */
final class Projectiles {

    /** The most projectiles alive at once, server-wide. Past it a new one is refused. */
    static final int MAX_LIVE = 256;
    /** The longest any projectile lives, in ticks, whatever it was told. */
    static final long MAX_TICKS = 20 * 60;

    private static final Set<Flight> LIVE = ConcurrentHashMap.newKeySet();

    private Projectiles() {
    }

    /** One projectile in flight. Each step happens on the region that owns {@link #at}. */
    private static final class Flight {
        SkillRuntime runtime;
        SkillContext context;
        Location at;
        Vector velocity;
        double speed;
        long interval;
        double maxDistance;
        long maxTicks;
        double travelled;
        long lived;
        double hR;
        double vR;
        double gravity;
        double inertia;
        boolean stopAtEntity;
        boolean stopAtBlock;
        boolean hitPlayers;
        boolean hitNonPlayers;
        boolean hitSelf;
        boolean totem;
        int charges;
        Entity homeOn;
        /** What it was fired at, which it always hits (MythicMobs' HitTarget), player or not. */
        UUID aimed;
        List<SkillLine> onTick;
        List<SkillLine> onHit;
        List<SkillLine> onEnd;
        final Set<UUID> hit = new HashSet<>();
        ItemDisplay bullet;
        int generation;
        boolean over;
    }

    /** Ends every projectile now: a plugin stop. */
    static void stopAll() {
        for (Flight flight : List.copyOf(LIVE)) {
            flight.over = true;
            removeBullet(flight);
        }
        LIVE.clear();
    }

    static int live() {
        return LIVE.size();
    }

    /**
     * Fires one from the caster at {@code target} (an entity, homed on by a
     * missile, or a place).
     *
     * @param mode {@code projectile}, {@code missile} or {@code totem}
     */
    static boolean launch(SkillRuntime runtime, SkillCastImpl cast, Object target, String mode) {
        if (LIVE.size() >= MAX_LIVE) {
            runtime.warnOnce("projectiles", "More than " + MAX_LIVE + " skill projectiles at once; new ones are "
                    + "refused until some land.");
            return false;
        }
        LivingEntity caster = cast.caster();
        Flight flight = new Flight();
        flight.runtime = runtime;
        flight.generation = runtime.generation;
        flight.totem = mode.equals("totem");
        String source = cast.context.mob + " " + mode;
        flight.onTick = runtime.inline(cast.raw("ontick", "ot", "ontickskill"), source);
        flight.onHit = runtime.inline(cast.raw("onhit", "oh", "onhitskill"), source);
        flight.onEnd = runtime.inline(cast.raw("onend", "oe", "onendskill"), source);
        List<SkillLine> onStart = runtime.inline(cast.raw("onstart", "os", "onstartskill"), source);
        flight.interval = Math.max(1, Math.min(20, Math.round(cast.number("interval", 1, "i"))));
        flight.hR = Math.max(0.05, Math.min(16, cast.number("horizontalradius", 1, "hr", "hradius", "radius", "r")));
        flight.vR = Math.max(0.05, Math.min(16, cast.number("verticalradius", flight.hR, "vr", "vradius")));
        flight.stopAtEntity = !flight.totem && cast.bool("stopatentity", true, "se", "sae");
        flight.stopAtBlock = !flight.totem && cast.bool("stopatblock", true, "sb", "sab");
        flight.hitPlayers = cast.bool("hitplayers", true, "hp");
        flight.hitNonPlayers = cast.bool("hitnonplayers", false, "hnp");
        flight.hitSelf = cast.bool("hitself", false, "hs");
        flight.gravity = cast.number("gravity", 0, "g") / 20.0;
        flight.inertia = Math.max(0, cast.number("inertia", 1.5, "in"));
        flight.charges = cast.integer("charges", 0, "ch");
        flight.context = cast.context.child(List.of());

        Location start;
        if (flight.totem) {
            start = cast.location();
            start.add(0, cast.number("yoffset", 0, "yo"), 0);
            flight.velocity = new Vector();
            flight.speed = 0;
            flight.maxDistance = Double.MAX_VALUE;
            flight.maxTicks = Math.min(MAX_TICKS, Math.max(1, Math.round(cast.number("maxduration", 100,
                    "md", "duration", "d"))));
        } else {
            start = caster.getLocation().add(0, cast.number("startyoffset", caster.getHeight() * 0.75, "syo"), 0);
            double side = cast.number("startsideoffset", 0, "sso");
            double forward = cast.number("startforwardoffset", 0.5, "sfo");
            Vector facing = caster.getLocation().getDirection().setY(0);
            if (facing.lengthSquared() < 1.0E-6) {
                facing = new Vector(0, 0, 1);
            }
            facing.normalize();
            Vector right = new Vector(-facing.getZ(), 0, facing.getX());
            start.add(facing.clone().multiply(forward)).add(right.multiply(side));
            Location aim = target instanceof Entity ? aimAt((Entity) target) : ((Location) target).clone();
            if (target instanceof Entity && cast.bool("hittarget", true, "ht")) {
                flight.aimed = ((Entity) target).getUniqueId();
            }
            aim.add(0, cast.number("targetyoffset", 0, "tyo"), 0);
            Vector direction = aim.toVector().subtract(start.toVector());
            if (direction.lengthSquared() < 1.0E-6) {
                direction = caster.getLocation().getDirection();
            }
            double spread = Math.toRadians(cast.number("hspread", 0, "spread"));
            if (spread > 0) {
                direction.rotateAroundY((ThreadLocalRandom.current().nextDouble() * 2 - 1) * spread);
            }
            flight.speed = Math.max(0.01, Math.min(60, cast.number("velocity", 5, "v"))) / 20.0;
            flight.velocity = direction.normalize().multiply(flight.speed);
            flight.maxDistance = Math.max(1, Math.min(256, cast.number("maxdistance", 40, "md", "maxrange", "mr")));
            flight.maxTicks = Math.min(MAX_TICKS, Math.max(1, Math.round(cast.number("duration",
                    mode.equals("missile") ? 400 : MAX_TICKS, "maxduration", "d"))));
            if (mode.equals("missile") && target instanceof Entity) {
                flight.homeOn = (Entity) target;
            }
        }
        if (start.getWorld() == null) {
            return false;
        }
        flight.at = start;
        bullet(runtime, cast, flight);
        LIVE.add(flight);
        flight.context.inherited = List.of(start.clone());
        runtime.execute(flight.context, onStart, 0);
        Tasks.atLater(runtime.plugin, start, () -> step(flight), flight.interval);
        return true;
    }

    /** Where a projectile aimed at an entity flies to: the middle of its body. */
    private static Location aimAt(Entity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
    }

    /** The bullet's look: an item display, a looping VFX riding an empty one, or nothing. */
    private static void bullet(SkillRuntime runtime, SkillCastImpl cast, Flight flight) {
        String type = cast.string("bullettype", "", "bt", "bullet").toLowerCase(Locale.ROOT);
        String item = cast.string("item", "", "material", "mat", "bulletmaterial", "bm", "model");
        String vfx = cast.string("vfx", "", "bulletvfx");
        if (type.startsWith("vfx:")) {
            vfx = type.substring(4);
            type = "vfx";
        }
        if (type.isEmpty() && !item.isEmpty()) {
            type = "item";
        }
        if (type.isEmpty() && !vfx.isEmpty()) {
            type = "vfx";
        }
        if (!type.equals("item") && !type.equals("display") && !type.equals("vfx")) {
            return;
        }
        ItemStack stack = type.equals("vfx") ? null : stack(runtime, item.isEmpty() ? "minecraft:fire_charge" : item);
        float scale = (float) Math.max(0.05, Math.min(8, cast.number("bulletscale", 0.5, "scale", "bs")));
        Location at = flight.at.clone();
        flight.bullet = ai.resourcepack.engine.core.version.Spawning.spawn(at.getWorld(), at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            if (stack != null) {
                d.setItemStack(stack);
            }
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                    new org.joml.Quaternionf(), new org.joml.Vector3f(scale, scale, scale), new org.joml.Quaternionf()));
            teleportDuration(d, (int) Math.min(59, flight.interval));
        });
        if (type.equals("vfx") && !vfx.isEmpty() && runtime.host.vfx() != null) {
            Optional<VfxInfo> info = runtime.host.vfx().find(vfx);
            if (info.isPresent()) {
                runtime.host.vfx().play(info.get().id(), at, null, VfxOptions.builder()
                        .follow(flight.bullet).loopFor((int) Math.min(MAX_TICKS, flight.maxTicks))
                        .scale(Math.max(0.05, cast.number("vfxscale", 1))).build());
            } else {
                runtime.warnOnce("vfx:" + vfx, "No VFX " + vfx + " (a projectile's bullet).");
            }
        }
    }

    /** {@code setTeleportDuration}, which arrived in 1.20.2: by reflection, nothing before it. */
    private static void teleportDuration(ItemDisplay display, int ticks) {
        try {
            display.getClass().getMethod("setTeleportDuration", int.class).invoke(display, ticks);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // An older server jumps the bullet from step to step.
        }
    }

    private static ItemStack stack(SkillRuntime runtime, String written) {
        String text = written.trim();
        Optional<ContentId> id = ContentId.parse(text.toLowerCase(Locale.ROOT));
        if (id.isPresent() && !id.get().namespace().equals("minecraft")) {
            Optional<ItemStack> made = runtime.host.items().create(id.get());
            if (made.isPresent()) {
                return made.get();
            }
        }
        Material material = Material.matchMaterial(text);
        return material == null || !material.isItem() ? new ItemStack(Material.FIRE_CHARGE) : new ItemStack(material);
    }

    /** One step, on the region that owns {@code flight.at}. */
    private static void step(Flight flight) {
        SkillRuntime runtime = flight.runtime;
        if (flight.over) {
            return;
        }
        if (flight.generation != runtime.generation || !flight.context.alive()) {
            end(flight, false);
            return;
        }
        World world = flight.at.getWorld();
        if (world == null) {
            end(flight, false);
            return;
        }
        flight.lived += flight.interval;
        if (flight.homeOn != null && flight.homeOn.isValid() && flight.homeOn.getWorld() == world) {
            Vector to = aimAt(flight.homeOn).toVector().subtract(flight.at.toVector());
            if (to.lengthSquared() > 1.0E-6) {
                Vector turned = flight.velocity.clone().normalize().multiply(flight.inertia).add(to.normalize());
                if (turned.lengthSquared() > 1.0E-6) {
                    flight.velocity = turned.normalize().multiply(flight.speed);
                }
            }
        }
        flight.velocity.setY(flight.velocity.getY() - flight.gravity * flight.interval);
        Vector move = flight.velocity.clone().multiply(flight.interval);
        double length = move.length();
        int steps = Math.max(1, (int) Math.ceil(length / 0.5));
        Vector piece = move.clone().multiply(1.0 / steps);
        for (int i = 0; i < steps; i++) {
            if (length > 0) {
                Location next = flight.at.clone().add(piece);
                if (!Tasks.owns(next)) {
                    // Into another region: the rest of this step happens there.
                    flight.at = next;
                    break;
                }
                flight.at = next;
                flight.travelled += piece.length();
                if (flight.stopAtBlock && solid(next.getBlock())) {
                    end(flight, true);
                    return;
                }
            }
            if (hits(flight) && flight.over) {
                return;
            }
        }
        moveBullet(flight);
        if (!flight.onTick.isEmpty()) {
            flight.context.run.executed = 0;
            flight.context.inherited = List.of(flight.at.clone());
            runtime.execute(flight.context, flight.onTick, 0);
        }
        if (flight.totem && flight.charges > 0) {
            flight.charges--;
            if (flight.charges == 0) {
                end(flight, true);
                return;
            }
        }
        if (flight.travelled >= flight.maxDistance || flight.lived >= flight.maxTicks) {
            end(flight, true);
            return;
        }
        Location at = flight.at.clone();
        Tasks.atLater(runtime.plugin, at, () -> step(flight), flight.interval);
    }

    private static boolean solid(Block block) {
        return !block.isPassable();
    }

    /**
     * Runs {@code onHit} on everything newly within reach; whether anything
     * was hit. A projectile that stops at an entity ends on the first.
     */
    private static boolean hits(Flight flight) {
        World world = flight.at.getWorld();
        boolean any = false;
        LivingEntity caster = flight.context.caster;
        for (Entity entity : world.getNearbyEntities(flight.at, flight.hR, flight.vR, flight.hR)) {
            if (!(entity instanceof LivingEntity) || entity.isDead() || flight.hit.contains(entity.getUniqueId())) {
                continue;
            }
            if (entity == caster && !flight.hitSelf) {
                continue;
            }
            if (entity instanceof Player) {
                GameMode mode = ((Player) entity).getGameMode();
                if (!flight.hitPlayers || mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) {
                    continue;
                }
            } else if (!flight.hitNonPlayers && entity != caster && !entity.getUniqueId().equals(flight.aimed)) {
                continue;
            }
            if (entity instanceof org.bukkit.entity.ArmorStand || entity.getType().name().equals("MARKER")) {
                continue;
            }
            flight.hit.add(entity.getUniqueId());
            any = true;
            flight.context.run.executed = 0;
            flight.context.inherited = List.of(entity);
            flight.runtime.execute(flight.context, flight.onHit, 0);
            if (flight.context.mob != null) {
                flight.runtime.projectileHit(caster, flight.context.mob, entity);
            }
            if (flight.totem && flight.charges > 0) {
                flight.charges--;
                if (flight.charges == 0) {
                    end(flight, true);
                    return true;
                }
            }
            if (flight.stopAtEntity) {
                end(flight, true);
                return true;
            }
        }
        return any;
    }

    private static void moveBullet(Flight flight) {
        ItemDisplay bullet = flight.bullet;
        if (bullet == null || !bullet.isValid()) {
            return;
        }
        Tasks.teleport(bullet, flight.at.clone());
    }

    private static void end(Flight flight, boolean runEnd) {
        if (flight.over) {
            return;
        }
        flight.over = true;
        LIVE.remove(flight);
        removeBullet(flight);
        if (runEnd && !flight.onEnd.isEmpty() && flight.generation == flight.runtime.generation) {
            flight.context.run.executed = 0;
            flight.context.inherited = List.of(flight.at.clone());
            flight.runtime.execute(flight.context, flight.onEnd, 0);
        }
    }

    private static void removeBullet(Flight flight) {
        ItemDisplay bullet = flight.bullet;
        flight.bullet = null;
        if (bullet == null) {
            return;
        }
        if (Tasks.owns(bullet)) {
            bullet.remove();
        } else {
            Tasks.on(flight.runtime.plugin, bullet, bullet::remove);
        }
    }

    // --- chain ------------------------------------------------------------

    /**
     * {@code chain{bounces;radius;bounceDelay;onBounce}}: {@code onBounce} on
     * the first target, then on the nearest living thing within
     * {@code radius} of it that the chain has not touched, and so on,
     * {@code bounces} times. Each bounce waits {@code bounceDelay} ticks on
     * the next target's own thread.
     */
    static boolean chain(SkillRuntime runtime, SkillCastImpl cast, LivingEntity first) {
        int bounces = Math.max(0, Math.min(32, cast.integer("bounces", 2, "b")));
        double radius = Math.max(0.5, Math.min(32, cast.number("bounceradius", 5, "radius", "r", "br")));
        long delay = Math.max(1, Math.round(cast.number("bouncedelay", 4, "bd", "delay")));
        boolean players = cast.bool("hitplayers", true, "hp");
        boolean others = cast.bool("hitnonplayers", false, "hnp");
        List<SkillLine> onBounce = runtime.inline(cast.raw("onbounce", "ob", "onbounceskill", "onhit", "oh"),
                cast.context.mob + " chain");
        SkillContext context = cast.context.child(List.of());
        Set<UUID> touched = new HashSet<>();
        touched.add(cast.caster().getUniqueId());
        bounce(runtime, context, first, onBounce, bounces, radius, delay, players, others, touched,
                runtime.generation);
        return true;
    }

    private static void bounce(SkillRuntime runtime, SkillContext context, LivingEntity at, List<SkillLine> onBounce,
                               int left, double radius, long delay, boolean players, boolean others, Set<UUID> touched,
                               int generation) {
        if (generation != runtime.generation || !at.isValid() || at.isDead()) {
            return;
        }
        touched.add(at.getUniqueId());
        context.run.executed = 0;
        context.inherited = List.of(at);
        runtime.execute(context, onBounce, 0);
        if (left <= 0) {
            return;
        }
        LivingEntity next = null;
        double best = Double.MAX_VALUE;
        for (Entity entity : at.getWorld().getNearbyEntities(at.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity) || entity.isDead() || touched.contains(entity.getUniqueId())
                    || entity instanceof org.bukkit.entity.ArmorStand) {
                continue;
            }
            if (entity instanceof Player) {
                GameMode mode = ((Player) entity).getGameMode();
                if (!players || mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) {
                    continue;
                }
            } else if (!others) {
                continue;
            }
            double d = entity.getLocation().distanceSquared(at.getLocation());
            if (d <= radius * radius && d < best) {
                best = d;
                next = (LivingEntity) entity;
            }
        }
        if (next == null) {
            return;
        }
        LivingEntity target = next;
        Tasks.onLater(runtime.plugin, target, () -> bounce(runtime, context, target, onBounce, left - 1, radius, delay,
                players, others, touched, generation), () -> { }, delay);
    }
}
