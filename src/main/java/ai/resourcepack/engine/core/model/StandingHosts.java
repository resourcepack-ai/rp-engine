package ai.resourcepack.engine.core.model;

import org.bukkit.entity.Entity;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one host whose passenger point the client and the server disagree
 * about: a polar bear rearing up.
 *
 * <p>{@code RigAnimator.applyGroundLift} measures a worn part's height on the
 * server, which is right because the client places a passenger by the same
 * arithmetic. A polar bear is the exception. While it stands (it rears up to
 * swipe, every attack) the CLIENT grows its hitbox to up to twice its height
 * over six ticks, off a counter it keeps itself, and the passenger point is
 * the top of that box; the server never grows it. So in the first real-client
 * test a POLAR_BEAR mob's model leapt two blocks into the air at every swipe
 * and came back down.
 *
 * <p>So the client's counter is kept here as well, one step a tick toward six
 * while the bear stands and toward zero while it does not, exactly as the
 * client steps it, and its share of the bear's height is taken back off. It
 * trails the client by up to a send (two ticks), which is a fraction of a
 * block for a moment instead of two blocks for a second.
 *
 * <p>Whether a bear stands is Paper's {@code PolarBear.isStanding()}, reached
 * by reflection because Spigot has no such method. On Spigot this answers
 * zero and a rearing bear's model still rises.
 */
final class StandingHosts {

    /** The client's {@code clientSideStandAnimation} ceiling: six ticks to stand fully. */
    static final int STAND_TICKS = 6;

    private static final Method IS_STANDING = find();

    private static final class Stand {
        long tick;
        int steps;
    }

    private final Map<UUID, Stand> stands = new ConcurrentHashMap<>();

    private static Method find() {
        try {
            Class<?> bear = Class.forName("org.bukkit.entity.PolarBear");
            return bear.getMethod("isStanding");
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Blocks the client has raised {@code host}'s passengers by that the server has not. */
    float rise(Entity host) {
        if (IS_STANDING == null || !IS_STANDING.getDeclaringClass().isInstance(host)) return 0f;
        boolean standing;
        try {
            standing = Boolean.TRUE.equals(IS_STANDING.invoke(host));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0f;
        }
        long now = host.getWorld().getGameTime();
        Stand stand = stands.computeIfAbsent(host.getUniqueId(), id -> new Stand());
        int elapsed = stand.tick == 0 ? STAND_TICKS : (int) Math.min(STAND_TICKS, Math.max(0, now - stand.tick));
        stand.tick = now;
        stand.steps = step(stand.steps, standing, elapsed);
        if (stand.steps == 0 && !standing) {
            stands.remove(host.getUniqueId());
        }
        return rise(host.getHeight(), stand.steps);
    }

    /** The client's counter after {@code elapsed} ticks of standing or not. */
    static int step(int steps, boolean standing, int elapsed) {
        int next = steps + (standing ? elapsed : -elapsed);
        return Math.max(0, Math.min(STAND_TICKS, next));
    }

    /** How far a box {@code height} tall has grown at {@code steps} of the counter. */
    static float rise(double height, int steps) {
        return (float) (height * steps / STAND_TICKS);
    }
}
