package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.VehicleEmitter;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleState;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Throwing a vehicle's particles.
 *
 * <p>Exhaust from a car, a wake behind a boat, dust off a wheel. Everything
 * about WHEN is decided by {@link VehicleState}, which the physics already
 * worked out; everything here is about turning a name and an offset into a
 * call the game will accept.
 *
 * <h2>The particle enum is not stable, and that is most of this class</h2>
 *
 * <p>Bukkit's {@code Particle} constants were renamed wholesale in 1.20.5 to
 * match vanilla's own — {@code SMOKE_NORMAL} became {@code SMOKE},
 * {@code REDSTONE} became {@code DUST}, and about twenty more. This engine
 * runs from 1.19.4 up, so <strong>both spellings are live</strong> and a pack
 * written on one server has to work on the other. So a name is resolved
 * through {@link #ALIASES}: whatever the author wrote, every spelling of that
 * effect is tried and the one this server has wins.
 *
 * <p>This is deliberately NOT a {@code Feature}. A feature constant describes a
 * capability the engine loses on an older server and reports at startup;
 * particles are not lost anywhere, they are merely spelled differently, and a
 * line in the startup report saying so would be noise on every server.
 *
 * <h2>What it refuses, and why it says so out loud</h2>
 *
 * <p>Some particles cannot be drawn without something to draw them FROM — a
 * block for {@code BLOCK}, an item for {@code ITEM} — and a vehicle emitter
 * has no way to name one. Asking for those throws inside the game rather than
 * drawing nothing, so they are skipped here with a line in the log. The same
 * line covers a name that is not a particle at all.
 *
 * <p><strong>Said once per name, ever.</strong> This runs twenty times a
 * second for every vehicle on the server, and a warning on that path is how a
 * log file becomes a gigabyte.
 */
public final class VehicleParticles {

    /**
     * Every spelling of one effect, newest first.
     *
     * <p>Only the ones that were RENAMED are here. A particle whose name never
     * changed resolves on its own and would be noise. Newest first so that a
     * modern server resolves in one attempt, which is the common case.
     */
    private static final String[][] ALIASES = {
        {"SMOKE", "SMOKE_NORMAL"},
        {"LARGE_SMOKE", "SMOKE_LARGE"},
        {"POOF", "EXPLOSION_NORMAL"},
        {"EXPLOSION", "EXPLOSION_LARGE"},
        {"EXPLOSION_EMITTER", "EXPLOSION_HUGE"},
        {"FIREWORK", "FIREWORKS_SPARK"},
        {"BUBBLE", "WATER_BUBBLE"},
        {"SPLASH", "WATER_SPLASH"},
        {"FISHING", "WATER_WAKE"},
        {"UNDERWATER", "SUSPENDED"},
        {"RAIN", "WATER_DROP"},
        {"ENCHANTED_HIT", "CRIT_MAGIC"},
        {"EFFECT", "SPELL"},
        {"INSTANT_EFFECT", "SPELL_INSTANT"},
        {"ENTITY_EFFECT", "SPELL_MOB"},
        {"WITCH", "SPELL_WITCH"},
        {"DRIPPING_WATER", "DRIP_WATER"},
        {"DRIPPING_LAVA", "DRIP_LAVA"},
        {"ANGRY_VILLAGER", "VILLAGER_ANGRY"},
        {"HAPPY_VILLAGER", "VILLAGER_HAPPY"},
        {"MYCELIUM", "TOWN_AURA"},
        {"ENCHANT", "ENCHANTMENT_TABLE"},
        {"DUST", "REDSTONE"},
        {"ITEM_SNOWBALL", "SNOWBALL"},
        {"ITEM_SLIME", "SLIME"},
        {"ELDER_GUARDIAN", "MOB_APPEARANCE"},
        {"TOTEM_OF_UNDYING", "TOTEM"},
    };

    /** Name -> every spelling of it, built once from {@link #ALIASES}. */
    private static final Map<String, String[]> SPELLINGS = new HashMap<>();

    static {
        for (String[] group : ALIASES) {
            for (String name : group) {
                SPELLINGS.put(name, group);
            }
        }
    }

    /** White, for a dust particle whose pack asked for no colour. */
    private static final Color DEFAULT_DUST = Color.WHITE;

    private final Logger log;

    /**
     * What each written name resolved to, or absent for one that resolved to
     * nothing.
     *
     * <p>Cached because this is asked on every emitting tick of every vehicle,
     * and {@code Particle.valueOf} on a miss is an exception being constructed
     * and thrown — which is thousands of stack traces a second for one
     * misspelt effect.
     */
    private final Map<String, Particle> resolved = new HashMap<>();
    private final Set<String> unusable = new HashSet<>();
    private final Set<String> warned = new HashSet<>();

    public VehicleParticles(Logger log) {
        this.log = log;
    }

    /**
     * Fires whichever of {@code info}'s emitters are due this tick.
     *
     * <p>Main thread only, like everything a vehicle does.
     *
     * @param base   where the vehicle is, on the ground — the same point every
     *               seat offset is measured from
     * @param yaw    which way it is facing, so an offset turns with the body
     * @param ticks  the vehicle's own age in ticks, which is what an interval
     *               is counted against. Per-vehicle rather than the server's
     *               clock, so two identical cars do not pulse in lockstep
     */
    public void emit(World world, Location base, double yaw, VehicleInfo info,
                     Collection<VehicleState> states, long ticks) {
        if (world == null || base == null || info.emitters().isEmpty()) {
            return;
        }
        for (VehicleEmitter emitter : info.emitters()) {
            if (!emitter.firesIn(states) || ticks % emitter.interval() != 0) {
                continue;
            }
            Particle particle = resolve(emitter.effect());
            if (particle == null) {
                continue;
            }
            // The same basis a seat uses, so an exhaust pipe placed against
            // the back of the model stays against the back of the model
            // whichever way the vehicle is parked.
            double[] offset = VehiclePhysics.seatOffset(yaw, emitter.x(), emitter.z());
            double x = base.getX() + offset[0];
            double y = base.getY() + emitter.y();
            double z = base.getZ() + offset[1];

            if (isDust(particle)) {
                world.spawnParticle(particle, x, y, z, emitter.count(),
                        emitter.spread(), emitter.spread(), emitter.spread(), emitter.speed(),
                        new Particle.DustOptions(
                                emitter.color().map(Color::fromRGB).orElse(DEFAULT_DUST),
                                (float) emitter.size()));
            } else {
                world.spawnParticle(particle, x, y, z, emitter.count(),
                        emitter.spread(), emitter.spread(), emitter.spread(), emitter.speed());
            }
        }
    }

    /**
     * The particle this name means on THIS server, or null if it means none.
     *
     * <p>Null covers three different failures on purpose — a name that is not
     * a particle, one this version spells differently and does not have under
     * either spelling, and one that needs a block or an item to draw. They are
     * one answer here because the caller does the same thing with all three,
     * and the log line tells them apart.
     */
    private Particle resolve(String written) {
        if (written == null || written.isEmpty()) {
            return null;
        }
        Particle cached = resolved.get(written);
        if (cached != null) {
            return cached;
        }
        if (unusable.contains(written)) {
            return null;
        }

        Particle found = null;
        for (String spelling : SPELLINGS.getOrDefault(written, new String[] {written})) {
            try {
                found = Particle.valueOf(spelling);
                break;
            } catch (IllegalArgumentException ignored) {
                // This server spells it the other way, or not at all.
            }
        }

        if (found == null) {
            unusable.add(written);
            warnOnce(written, "is not a particle this server has. Check the spelling - note "
                    + "that several were renamed in 1.20.5, and the engine already tries both.");
            return null;
        }
        if (!drawsItself(found)) {
            unusable.add(written);
            warnOnce(written, "needs a block or an item to draw itself with, and a vehicle "
                    + "emitter has no way to name one. Pick a particle that draws itself, "
                    + "such as smoke or dust.");
            return null;
        }
        resolved.put(written, found);
        return found;
    }

    /**
     * Whether this particle can be spawned with no extra data.
     *
     * <p>{@code getDataType()} is {@code Void.class} for the ones that draw
     * themselves. Dust is the one exception worth handling, because a coloured
     * plume is exactly what somebody building a vehicle wants and its data is
     * something an emitter CAN state.
     */
    private static boolean drawsItself(Particle particle) {
        return particle.getDataType() == Void.class || isDust(particle);
    }

    private static boolean isDust(Particle particle) {
        return particle.getDataType() == Particle.DustOptions.class;
    }

    private void warnOnce(String written, String why) {
        if (!warned.add(written.toUpperCase(Locale.ROOT))) {
            return;
        }
        log.warning("A vehicle asks for the particle " + written + ", which " + why);
    }
}
