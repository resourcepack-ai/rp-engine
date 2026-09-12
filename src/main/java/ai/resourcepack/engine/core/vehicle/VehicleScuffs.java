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
import java.util.Locale;

/**
 * Scrapes on the bodywork: where a vehicle has been hit, drawn on it.
 *
 * <p>Bounded, and only the coordinates persist — the displays are rebuilt
 * whenever the vehicle is, like everything else it is made of.
 *
 * <h2>What was wrong with these, because it is instructive</h2>
 *
 * <p>They were four separate faults that all read as "the scratches are in the
 * wrong place", and each of them is a thing worth not doing again:
 *
 * <ul>
 *   <li><strong>Drawn about the wrong pivot.</strong> The decals hung off the
 *   vehicle's POSITION plus half a block, and the model hangs off the position
 *   plus half a block plus the sprung RIDE HEIGHT — so every scrape floated
 *   above the paint by however far the body was sitting into its springs, and
 *   visibly swam off it over every bump. There is now one number
 *   ({@link VehicleRuntime#MODEL_LIFT}) and one anchor ({@code modelAnchor}),
 *   passed in.</li>
 *   <li><strong>Placed at the bottom of the vehicle.</strong> A world impact's
 *   contact point had no height in it — a horizontal normal has no y — so every
 *   scrape from a wall landed at a fifth of the way up the body, along the
 *   sill, which is the one part of a car that has no paint on it. The engine
 *   reports the middle of the panel now, and a mark with no height at all is
 *   put on the waistline rather than the floor.</li>
 *   <li><strong>A hairline.</strong> Twelve hundredths of a block tall and four
 *   thousandths thick, in two strips a fortieth apart, which at any distance is
 *   a pair of flickering dark lines rather than a scrape. One mark is now one
 *   display, an eighth of a block tall, of a length and a colour that say how
 *   hard the hit was.</li>
 *   <li><strong>Pinned to the shell's own face.</strong> The hitbox is what a
 *   player collides with and is nearly always a shade bigger than the art, and
 *   a decal exactly on its face therefore hangs in the air beside the vehicle.
 *   Each mark now reaches {@link #REACH} INTO the shell and {@link #PROUD}
 *   outside it, so it lands on paint that is anywhere within a pixel or two of
 *   the box. Past that the hitbox is simply wrong for the model and the
 *   authoring is what to fix.</li>
 * </ul>
 *
 * <p>Repeated contact in one place DEEPENS that mark — longer, taller, darker —
 * rather than spending another display on it. Which is both cheaper and more
 * truthful: a corner somebody keeps putting into a wall ends up visibly ruined,
 * and a car driven carefully into twenty different things does not accumulate
 * more scrapes than a car can carry.
 */
final class VehicleScuffs {

    /**
     * As many marks as one vehicle may carry.
     *
     * <p>One display each now rather than two, so this is ten entities on the
     * most battered vehicle on the server and none at all on every vehicle
     * nobody has hit.
     */
    private static final int LIMIT = 10;

    /**
     * How far a mark reaches INTO the collision shell, in blocks.
     *
     * <p>The whole of why a scrape lands on the paint. A vehicle's hitbox is
     * stated in blocks by its author and its art is drawn in pixels by the
     * modeller, so the two agree to within a pixel or two at best — and a
     * decal on the shell's exact face is one that hangs beside every vehicle
     * whose art is a hair narrower than its box. Two pixels' worth of reach
     * covers that; more would start to show INSIDE an open-topped vehicle.
     */
    private static final double REACH = 0.14;

    /** And how far it stands out from the shell, so it is never inside the paint. */
    private static final double PROUD = 0.012;

    /** How tall a scrape is, blocks: a graze, and a gouge. */
    private static final double MIN_TALL = 0.05;
    private static final double MAX_TALL = 0.17;

    /** How long, blocks, before the body's own size caps it. */
    private static final double MIN_LENGTH = 0.22;
    private static final double MAX_LENGTH = 0.95;

    /** As much of the side it runs along as one mark may take. */
    private static final double LENGTH_SHARE = 0.55;

    /**
     * The band of the bodywork a scrape may sit in, as fractions of the
     * hitbox's height.
     *
     * <p>Neither the floor nor the roofline: a scrape is on the panels. The
     * bottom bound is what stops a contact point the engine could not place
     * from drawing a line along the road under the vehicle.
     */
    private static final double BAND_LOW = 0.22;
    private static final double BAND_HIGH = 0.86;

    /** Where a mark goes when the impact carried no height at all: the waistline. */
    private static final double BAND_DEFAULT = 0.55;

    /** How near, in blocks, two contacts have to be to be the same scrape. */
    private static final double MERGE = 0.22;

    /** How deep a mark has to be to be bare metal, and to be a hole. */
    private static final double SCUFFED = 0.3;
    private static final double GOUGED = 0.62;

    /**
     * One scrape.
     *
     * @param right,up,forward where it sits in the body frame, in blocks, with
     *                         the thin axis on the SHELL'S FACE rather than
     *                         where the box is finally drawn — see {@link #REACH}
     * @param side             whether it runs along the vehicle (a side impact)
     *                         or across it (a front or rear one)
     * @param length           how far it runs, blocks
     * @param depth            how bad it is, 0 to 1, which decides its height
     *                         and its colour
     */
    private record Mark(double right, double up, double forward, boolean side,
                        double length, double depth) {

        Material material() {
            if (depth >= GOUGED) {
                return Material.BLACK_CONCRETE;
            }
            return depth >= SCUFFED ? Material.GRAY_CONCRETE : Material.LIGHT_GRAY_CONCRETE;
        }

        double height() {
            return MIN_TALL + (MAX_TALL - MIN_TALL) * Math.max(0, Math.min(1, depth));
        }
    }

    private final List<Mark> marks = new ArrayList<>();
    private final List<BlockDisplay> displays = new ArrayList<>();
    private boolean loaded;
    private boolean dirty;
    private Location last;
    private double lastPitch;
    private double lastRoll;

    /**
     * Records a contact.
     *
     * <p>{@code point} is in the body frame, in blocks, as
     * {@code Vehicle.partOffset} is — and its {@code y} is measured up from the
     * vehicle's base, so a zero means the engine had no height to report rather
     * than "on the floor". {@code amount} runs 0 to 1.
     */
    void add(Entity chassis, NamespacedKey key, VehicleHitbox box, VehicleImpactArea area,
             Vector point, double amount) {
        load(chassis, key);
        if (point == null || !Double.isFinite(amount) || amount <= 0
                || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY())
                || !Double.isFinite(point.getZ())) return;
        boolean side = area == VehicleImpactArea.LEFT || area == VehicleImpactArea.RIGHT;
        if (!side && area != VehicleImpactArea.FRONT && area != VehicleImpactArea.REAR) return;

        double depth = Math.min(1, amount);
        double span = side ? box.length() : box.width();
        double length = Math.min(span * LENGTH_SHARE,
                Math.min(MAX_LENGTH, MIN_LENGTH + depth * (MAX_LENGTH - MIN_LENGTH)));
        // On the shell's face across the impact, and wherever along it the
        // engine said — kept clear of the ends by its own half length, so a
        // scrape on a corner does not run off it into the air.
        double half = length / 2;
        double right = side
                ? (area == VehicleImpactArea.LEFT ? -1 : 1) * box.width() / 2
                : clamp(point.getX(), -box.width() / 2 + half, box.width() / 2 - half);
        double forward = side
                ? clamp(point.getZ(), -box.length() / 2 + half, box.length() / 2 - half)
                : (area == VehicleImpactArea.REAR ? -1 : 1) * box.length() / 2;
        // An impact the engine could not place vertically lands on the
        // waistline, which is where the paint is. Zero is that case and not a
        // vehicle scraped along its floor.
        double up = Math.abs(point.getY()) < 1e-6
                ? box.height() * BAND_DEFAULT
                : clamp(point.getY(), box.height() * BAND_LOW, box.height() * BAND_HIGH);

        // The same place again deepens what is already there. A merged mark
        // keeps the longer run and the worse of the two depths, so a corner
        // somebody keeps hitting ends up visibly ruined rather than covered in
        // identical short lines.
        int found = -1;
        for (int i = 0; i < marks.size(); i++) {
            Mark mark = marks.get(i);
            if (mark.side() != side) continue;
            if (Math.abs(mark.right() - right) + Math.abs(mark.up() - up)
                    + Math.abs(mark.forward() - forward) < MERGE) {
                found = i;
                break;
            }
        }
        Mark next;
        if (found >= 0) {
            Mark was = marks.remove(found);
            next = new Mark(was.right(), was.up(), was.forward(), side,
                    Math.min(span * LENGTH_SHARE, Math.max(was.length(), length)),
                    Math.min(1, was.depth() + depth * 0.75));
        } else {
            if (marks.size() >= LIMIT) marks.remove(0);
            next = new Mark(right, up, forward, side, length, depth);
        }
        marks.add(next);
        dirty = true;
        StringBuilder encoded = new StringBuilder(marks.size() * 32);
        for (Mark mark : marks) {
            encoded.append(round(mark.right())).append(',').append(round(mark.up())).append(',')
                    .append(round(mark.forward())).append(',').append(mark.side() ? 1 : 0)
                    .append(',').append(round(mark.length())).append(',')
                    .append(round(mark.depth())).append('\n');
        }
        chassis.getPersistentDataContainer().set(key, PersistentDataType.STRING, encoded.toString());
    }

    /**
     * Reads what the chassis remembers, once.
     *
     * <p>Five fields is the format before depth was one of them; those marks
     * come back as an ordinary scuff rather than being discarded, because the
     * alternative is a vehicle somebody has been driving for a week coming back
     * from a restart with its paint good as new.
     */
    private void load(Entity chassis, NamespacedKey key) {
        if (loaded) return;
        loaded = true;
        String value = chassis.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value == null || value.length() > 8192) return;
        for (String line : value.split("\n")) {
            if (marks.size() == LIMIT) break;
            String[] fields = line.split(",");
            if (fields.length != 5 && fields.length != 6) continue;
            try {
                double right = Double.parseDouble(fields[0]);
                double up = Double.parseDouble(fields[1]);
                double forward = Double.parseDouble(fields[2]);
                double length = Double.parseDouble(fields[4]);
                double depth = fields.length == 6 ? Double.parseDouble(fields[5]) : SCUFFED;
                if (Double.isFinite(right) && Double.isFinite(up) && Double.isFinite(forward)
                        && Double.isFinite(length) && Double.isFinite(depth)
                        && Math.abs(right) <= 16 && Math.abs(up) <= 16 && Math.abs(forward) <= 16
                        && length > 0) {
                    marks.add(new Mark(right, up, forward, fields[3].equals("1"),
                            Math.min(MAX_LENGTH, length), Math.max(0, Math.min(1, depth))));
                }
            } catch (NumberFormatException ignored) { }
        }
        dirty = !marks.isEmpty();
    }

    /**
     * Puts the marks where the body is.
     *
     * @param anchor the point the MODEL is drawn about — its own rotation pivot,
     *               ride height included. Getting this wrong by so much as the
     *               suspension travel is a scrape that floats off the paint.
     */
    void draw(Entity chassis, NamespacedKey key, Location anchor, double yaw,
              double pitch, double roll, boolean hidden, DisplayCarry carry) {
        load(chassis, key);
        if (hidden) {
            clearDisplays();
            return;
        }
        if (marks.isEmpty()) return;
        Location at = anchor.clone();
        at.setYaw((float) yaw);
        at.setPitch(0);

        // Only the difference, so one new scrape does not despawn and respawn
        // every other one — which was a visible blink of the whole set on every
        // impact, exactly when a player is looking at the vehicle.
        while (displays.size() > marks.size()) {
            BlockDisplay extra = displays.remove(displays.size() - 1);
            extra.remove();
            dirty = true;
        }
        for (int i = displays.size(); i < marks.size(); i++) {
            displays.add(spawn(at, carry));
            dirty = true;
        }
        if (displays.removeIf(display -> !display.isValid())) {
            // A chunk took some of them. Whatever is left is no longer keyed to
            // its mark, so start the set again.
            clearDisplays();
            for (int i = 0; i < marks.size(); i++) displays.add(spawn(at, carry));
            dirty = true;
        }
        if (!dirty && at.equals(last) && pitch == lastPitch && roll == lastRoll) return;

        for (int i = 0; i < displays.size(); i++) {
            Mark mark = marks.get(i);
            BlockDisplay display = displays.get(i);
            if (dirty) display.setBlock(mark.material().createBlockData());
            display.teleport(at);
            display.setInterpolationDelay(0);
            display.setTransformation(RigMath.toTransformation(pose(mark, pitch, roll)));
        }
        last = at;
        lastPitch = pitch;
        lastRoll = roll;
        dirty = false;
    }

    private BlockDisplay spawn(Location at, DisplayCarry carry) {
        BlockDisplay display = at.getWorld().spawn(at, BlockDisplay.class, d -> {
            d.setBlock(Material.GRAY_CONCRETE.createBlockData());
            d.setPersistent(false);
            d.setGravity(false);
            d.setInterpolationDuration(2);
            d.setViewRange(0.5f);
        });
        carry.carry(display);
        return display;
    }

    /**
     * One mark as a transformation of the block display it is drawn with.
     *
     * <p>Display-local {@code x} points opposite the body's right and local
     * {@code z} is body-forward, because the display carries the vehicle's own
     * yaw. Roll is applied before pitch, which is the order the model's own
     * attitude uses — {@code VehicleRuntime.attitude}, in the model's frame,
     * which is this one turned half a turn, hence the two flipped signs.
     */
    private static Matrix4f pose(Mark mark, double pitch, double roll) {
        double thickness = REACH + PROUD;
        // The thin axis runs from inside the shell to a hair outside it, so the
        // mark's stored face coordinate is the OUTER edge of the box.
        double inset = thickness / 2 - PROUD;
        double right = mark.right() - (mark.side() ? Math.signum(mark.right()) * inset : 0);
        double forward = mark.forward() - (mark.side() ? 0 : Math.signum(mark.forward()) * inset);

        Matrix4f pose = new Matrix4f()
                .rotateX((float) Math.toRadians(-pitch))
                .rotateZ((float) Math.toRadians(roll))
                .translate((float) -right, (float) (mark.up() - VehicleRuntime.MODEL_LIFT),
                        (float) forward);
        float across = (float) (mark.side() ? thickness : mark.length());
        float along = (float) (mark.side() ? mark.length() : thickness);
        float tall = (float) mark.height();
        return pose.translate(-across / 2, -tall / 2, -along / 2).scale(across, tall, along);
    }

    void clearDisplays() {
        displays.forEach(Entity::remove);
        displays.clear();
        last = null;
        dirty = !marks.isEmpty();
    }

    /** Three decimals is a twentieth of a pixel and keeps the blob small. */
    private static String round(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static double clamp(double n, double min, double max) {
        return Math.max(min, Math.min(max, n));
    }
}
