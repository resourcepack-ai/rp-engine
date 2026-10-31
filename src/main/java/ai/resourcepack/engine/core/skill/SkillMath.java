package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.core.version.Vanilla;
import org.bukkit.Location;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Geometry and numbers the targeters and mechanics share. Pure where it can be, and tested. */
final class SkillMath {

    private SkillMath() {
    }

    static double maxHealth(LivingEntity body) {
        AttributeInstance max = Vanilla.maxHealth().map(body::getAttribute).orElse(null);
        return max == null ? body.getHealth() : max.getValue();
    }

    /** Its follow range attribute, or 16 when it has none. */
    static double followRange(LivingEntity body) {
        AttributeInstance range = Vanilla.attribute("follow_range").map(body::getAttribute).orElse(null);
        return range == null ? 16 : range.getValue();
    }

    /** {@code points} spots on a flat ring of {@code radius} round {@code centre}, starting in front of it. */
    static List<Location> ring(Location centre, double radius, int points) {
        List<Location> out = new ArrayList<>();
        int n = Math.max(1, points);
        double start = Math.toRadians(centre.getYaw());
        for (int i = 0; i < n; i++) {
            double angle = start + 2 * Math.PI * i / n;
            out.add(centre.clone().add(-Math.sin(angle) * radius, 0, Math.cos(angle) * radius));
        }
        return out;
    }

    /** Spots evenly over a sphere's surface (a Fibonacci lattice). */
    static List<Location> sphere(Location centre, double radius, int points) {
        List<Location> out = new ArrayList<>();
        int n = Math.max(1, points);
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = n == 1 ? 0 : 1 - (i / (double) (n - 1)) * 2;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double theta = golden * i;
            out.add(centre.clone().add(Math.cos(theta) * r * radius, y * radius, Math.sin(theta) * r * radius));
        }
        return out;
    }

    /**
     * Spots filling a flat cone in front of {@code apex}: rows every block
     * out to {@code range}, spread across {@code angle} degrees, about
     * {@code points} in all.
     */
    static List<Location> cone(Location apex, double angle, double range, int points) {
        List<Location> out = new ArrayList<>();
        int rows = Math.max(1, (int) Math.round(range));
        int perRow = Math.max(1, points / rows);
        double yaw = Math.toRadians(apex.getYaw());
        double half = Math.toRadians(angle) / 2;
        for (int row = 1; row <= rows; row++) {
            double distance = range * row / rows;
            for (int i = 0; i < perRow; i++) {
                double a = perRow == 1 ? 0 : -half + 2 * half * i / (perRow - 1);
                double heading = yaw + a;
                out.add(apex.clone().add(-Math.sin(heading) * distance, 0, Math.cos(heading) * distance));
            }
        }
        return out;
    }

    /** The point {@code ahead} blocks in front of {@code from} and {@code side} to its right, flat. */
    static Location forward(Location from, double ahead, double side) {
        double yaw = Math.toRadians(from.getYaw());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        // Right of a heading (fx, fz) is (-fz, fx) in Minecraft's left-handed x/z.
        return from.clone().add(fx * ahead - fz * side, 0, fz * ahead + fx * side);
    }

    /** Whether {@code to} is within {@code angle} degrees of where {@code from} faces, flat. */
    static boolean inCone(Location from, Location to, double angle) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6) {
            return true;
        }
        double yaw = Math.toRadians(from.getYaw());
        double dot = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / length;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot)))) <= angle / 2;
    }

    /** A spot within {@code max} (and at least {@code min}) blocks of {@code centre}, flat. */
    static Location scatter(Location centre, double min, double max, Random random) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = min + random.nextDouble() * Math.max(0, max - min);
        return centre.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
    }

    /**
     * A leap from {@code from} that lands near {@code to}: a fixed-time arc
     * under vanilla's gravity and drag, its speed capped by {@code cap}
     * blocks a tick. MythicMobs' velocity is "the most it may go", in hundredths.
     */
    static Vector leap(Location from, Location to, double cap) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        double ticks = Math.max(8, Math.min(30, flat * 1.2));
        // Horizontal drag is about 0.91 a tick on the ground and 0.98 in the
        // air; 0.98 over the flight is what the speed has to beat.
        double drag = (1 - Math.pow(0.98, ticks)) / 0.02;
        Vector v = new Vector(dx / drag, 0, dz / drag);
        // Gravity 0.08 a tick: up enough to arrive at dy after `ticks`.
        double up = (dy + 0.04 * ticks * ticks) / ticks;
        v.setY(Math.max(0.3, Math.min(1.6, up)));
        double length = v.length();
        if (cap > 0 && length > cap) {
            v.multiply(cap / length);
        }
        return v;
    }
}
