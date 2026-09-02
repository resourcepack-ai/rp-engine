package ai.resourcepack.engine.core.vehicle;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

/**
 * Moving an entity that somebody is sitting on.
 *
 * <p><strong>This is the hard part of seating somebody on a moving vehicle,
 * and getting it wrong looks like the rider being left behind.</strong>
 *
 * <p>A seat mount has to arrive EXACTLY where it is told, every tick, because
 * vanilla positions a passenger from its vehicle — so the rider is only as
 * accurate as the mount. {@code Entity#teleport} is exact and
 * {@code setVelocity} is not: velocity is a request the entity's own tick then
 * resolves, through friction, collision and whatever else its type does, and
 * an entity that is a little short every tick is a rider who falls further
 * behind the longer the drive goes on. That was the first implementation, and
 * it is what "the player gets left behind" was.
 *
 * <p>So: teleport. The complication is that CraftBukkit has historically
 * REFUSED to teleport an entity that is being ridden — {@code isVehicle()} is
 * checked and the call returns false having done nothing — which is why the
 * first version reached for velocity in the first place. Paper added a flag
 * for it; Spigot has no equivalent; and this engine supports both.
 *
 * <p>Three arms, tried in order, and the order is the point:
 *
 * <ol>
 *   <li><strong>Paper's {@code RETAIN_PASSENGERS}</strong>, looked up
 *       reflectively for the same reason the input arm is: it is Paper's, this
 *       plugin compiles against Spigot, and taking paper-api on for one
 *       overload would put a second Bukkit in front of the oldest-API audit.</li>
 *   <li><strong>A plain teleport</strong>, which is what modern CraftBukkit
 *       does happily and older CraftBukkit refuses. Asking is free, and its
 *       return value says which one this is.</li>
 *   <li><strong>Velocity</strong>, the lossy one, kept only so that a server
 *       where neither works still moves its riders approximately rather than
 *       not at all.</li>
 * </ol>
 *
 * <p>Which arm a server got is worth knowing when a rider drifts, so
 * {@link #describe()} says.
 */
final class PassengerTeleport {

    /** {@code Entity#teleport(Location, TeleportFlag...)}, or null off Paper. */
    private final Method withFlags;

    /** A one-element array holding {@code EntityState.RETAIN_PASSENGERS}. */
    private final Object retainPassengers;

    /** Set the first time a plain teleport is refused, so the fallback is explained once. */
    private volatile boolean refused;

    private PassengerTeleport(Method withFlags, Object retainPassengers) {
        this.withFlags = withFlags;
        this.retainPassengers = retainPassengers;
    }

    /**
     * The arm for this server.
     *
     * <p>Never throws and never returns null: a server with none of this still
     * gets an object, and it falls through to the plain teleport.
     */
    static PassengerTeleport forServer() {
        try {
            Class<?> flag = Class.forName("io.papermc.paper.entity.TeleportFlag");
            Class<?> entityState = Class.forName("io.papermc.paper.entity.TeleportFlag$EntityState");
            Object retain = null;
            for (Object constant : entityState.getEnumConstants()) {
                if ("RETAIN_PASSENGERS".equals(((Enum<?>) constant).name())) {
                    retain = constant;
                    break;
                }
            }
            if (retain == null) {
                return new PassengerTeleport(null, null);
            }
            Object array = Array.newInstance(flag, 1);
            Array.set(array, 0, retain);
            Method method = Entity.class.getMethod("teleport", Location.class,
                    Array.newInstance(flag, 0).getClass());
            return new PassengerTeleport(method, array);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not Paper, or a Paper too old to have it. The plain teleport is
            // still worth trying and is the common case on a modern server.
            return new PassengerTeleport(null, null);
        }
    }

    /**
     * Puts {@code entity} exactly at {@code target}, passengers and all.
     *
     * @return whether it arrived. False means the caller should fall back to
     *         something lossy, and means this server can do neither.
     */
    boolean move(Entity entity, Location target) {
        if (withFlags != null) {
            try {
                Object moved = withFlags.invoke(entity, target, retainPassengers);
                if (Boolean.TRUE.equals(moved)) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Fall through. A reflective failure here is not worth a log
                // line per tick per seat.
            }
        }
        if (entity.teleport(target)) {
            return true;
        }
        refused = true;
        return false;
    }

    /** A line for the startup report, once it is known which arm is in use. */
    String describe() {
        if (withFlags != null) {
            return "retaining passengers (Paper)";
        }
        return refused ? "velocity — this server refuses to teleport a ridden entity" : "teleport";
    }
}
