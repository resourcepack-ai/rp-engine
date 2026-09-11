package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleImpactArea;
import ai.resourcepack.engine.core.animation.RigMath;
import ai.resourcepack.engine.core.model.DisplayCarry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Bounded scrape decals on the collision shell. Only their coordinates persist. */
final class VehicleScuffs {
    private static final int LIMIT = 12;
    private record Mark(double x, double y, double z, boolean side, double length) {}
    private final List<Mark> marks = new ArrayList<>();
    private final List<BlockDisplay> displays = new ArrayList<>();
    private boolean loaded;
    private Location last;
    private double lastPitch, lastRoll;

    void add(Entity chassis, NamespacedKey key, VehicleHitbox box, VehicleImpactArea area,
             Vector point, double amount) {
        load(chassis, key);
        if (point == null || !Double.isFinite(amount) || amount <= 0
                || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY())
                || !Double.isFinite(point.getZ())) return;
        boolean side = area == VehicleImpactArea.LEFT || area == VehicleImpactArea.RIGHT;
        if (!side && area != VehicleImpactArea.FRONT && area != VehicleImpactArea.REAR) return;
        double length = Math.min(side ? box.length() * 0.7 : box.width() * 0.7,
                0.15 + Math.min(1, amount) * 0.6);
        double x = side ? (area == VehicleImpactArea.LEFT ? -1 : 1) * (box.width() / 2 + 0.003)
                : clamp(point.getX(), -box.width() / 2 + length / 2, box.width() / 2 - length / 2);
        double z = !side ? (area == VehicleImpactArea.REAR ? -1 : 1) * (box.length() / 2 + 0.003)
                : clamp(point.getZ(), -box.length() / 2 + length / 2, box.length() / 2 - length / 2);
        double y = clamp(point.getY(), box.height() * 0.2, box.height() * 0.8);
        Mark mark = new Mark(x, y, z, side, length);
        // Repeated contact deepens the same mark rather than spawning unlimited entities.
        marks.removeIf(m -> m.side == side && Math.abs(m.x - x) + Math.abs(m.y - y) + Math.abs(m.z - z) < 0.15);
        if (marks.size() == LIMIT) marks.remove(0);
        marks.add(mark);
        clearDisplays();
        StringBuilder encoded = new StringBuilder();
        for (Mark m : marks) encoded.append(m.x).append(',').append(m.y).append(',').append(m.z)
                .append(',').append(m.side ? 1 : 0).append(',').append(m.length).append('\n');
        chassis.getPersistentDataContainer().set(key, PersistentDataType.STRING, encoded.toString());
    }

    private void load(Entity chassis, NamespacedKey key) {
        if (loaded) return;
        loaded = true;
        String value = chassis.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value == null || value.length() > 8192) return;
        for (String line : value.split("\n")) {
            if (marks.size() == LIMIT) break;
            String[] fields = line.split(",");
            if (fields.length != 5) continue;
            try {
                double x = Double.parseDouble(fields[0]), y = Double.parseDouble(fields[1]),
                        z = Double.parseDouble(fields[2]), length = Double.parseDouble(fields[4]);
                if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Double.isFinite(length)
                        && Math.abs(x) <= 16 && Math.abs(y) <= 16 && Math.abs(z) <= 16 && length > 0 && length <= 1)
                    marks.add(new Mark(x, y, z, fields[3].equals("1"), length));
            } catch (NumberFormatException ignored) { }
        }
    }

    void draw(Entity chassis, NamespacedKey key, Location at, double yaw, double pitch, double roll,
              boolean hidden, DisplayCarry carry) {
        load(chassis, key);
        if (hidden) { clearDisplays(); return; }
        if (marks.isEmpty()) return;
        Location anchor = at.clone();
        anchor.setYaw((float) yaw);
        anchor.setPitch(0);
        if (anchor.equals(last) && pitch == lastPitch && roll == lastRoll
                && displays.size() == marks.size() * 2 && displays.stream().allMatch(Entity::isValid)) return;
        if (displays.size() != marks.size() * 2 || displays.stream().anyMatch(d -> !d.isValid())) {
            clearDisplays();
            for (int i = 0; i < marks.size() * 2; i++) {
                final boolean metal = i % 2 == 0;
                BlockDisplay display = at.getWorld().spawn(at, BlockDisplay.class, d -> {
                    d.setBlock((metal ? Material.GRAY_CONCRETE : Material.BLACK_CONCRETE).createBlockData());
                    d.setPersistent(false);
                    d.setGravity(false);
                    d.setInterpolationDuration(2);
                    d.setViewRange(0.5f);
                });
                carry.carry(display);
                displays.add(display);
            }
        }
        for (int i = 0; i < displays.size(); i++) {
            Mark mark = marks.get(i / 2);
            double right = mark.x, up = mark.y + (i % 2) * 0.025, forward = mark.z;
            // Display-local X points opposite body-right; body-forward is local Z.
            Matrix4f pose = new Matrix4f().translate(0, 0.5f, 0)
                    .rotateX((float) Math.toRadians(-pitch)).rotateZ((float) Math.toRadians(roll))
                    .translate((float) -right, (float) (up - 0.5), (float) forward);
            float width = mark.side ? 0.004f : (float) mark.length;
            float depth = mark.side ? (float) mark.length : 0.004f;
            pose.translate(-width / 2, 0, -depth / 2).scale(width, 0.012f, depth);
            BlockDisplay display = displays.get(i);
            display.teleport(anchor);
            display.setInterpolationDelay(0);
            display.setTransformation(RigMath.toTransformation(pose));
        }
        last = anchor;
        lastPitch = pitch;
        lastRoll = roll;
    }

    void clearDisplays() {
        displays.forEach(Entity::remove);
        displays.clear();
        last = null;
    }

    private static double clamp(double n, double min, double max) {
        return Math.max(min, Math.min(max, n));
    }
}
