package ai.resourcepack.engine.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The boxes a model is actually made of, for anything that has to know its
 * SHAPE rather than its size.
 *
 * <p><strong>A block model is already a list of boxes.</strong> Every element
 * in one is a cuboid with a {@code from} and a {@code to}, so a model's real
 * collision volume needs no approximation and no voxelisation — it is the
 * elements themselves. That is the whole idea here: the one thing a placed
 * model was missing was a way to ask where its art is, and the art is boxes.
 *
 * <p>Everything is in <strong>local block units</strong>, converted once at
 * construction:
 *
 * <ul>
 *   <li><strong>x and z are measured from the model's centre column.</strong>
 *       A block model runs 0–16 across, and 8 is the middle — which is where a
 *       display entity is anchored. So {@code (m - 8) / 16}, and a cube filling
 *       the block runs -0.5 to 0.5.</li>
 *   <li><strong>y is measured from the block floor.</strong> {@code m / 16}, so
 *       a model built from y=0 upward starts at 0 and a two-block statue
 *       reaches 2.</li>
 * </ul>
 *
 * <p>That is not an arbitrary frame: it is exactly what the placement does. A
 * display sits at the block centre and Minecraft renders its model centred on
 * it, so model unit {@code m} lands at {@code block + m/16} — and the scale
 * transform ({@code RigMath.scaledTransformation}) lifts by {@code 0.5(s-1)}
 * precisely so a scaled model still stands on the floor rather than sinking
 * into it. Both halves of that are folded into these two conversions, which is
 * why a consumer only needs an origin, a yaw and a scale.
 *
 * <p><strong>Element rotation is folded in as an enclosing box.</strong> A
 * model may turn an element up to 45 degrees, and the axis-aligned
 * {@code from}/{@code to} of such an element is smaller than what you can see —
 * the rotated-slab wheels the builder makes are all of them. Under-covering art
 * is the failure that brought this whole feature back, so a rotated element
 * contributes the box that ENCLOSES it. Slightly generous beats slightly
 * see-through.
 */
public final class ModelShape {

    /**
     * As many boxes as one model may contribute.
     *
     * <p>A detailed model can run to several hundred elements and a vehicle
     * asks about several models many times a tick. The cap is high enough that
     * nothing normal reaches it and low enough that one pathological model
     * cannot make the world expensive; past it the LARGEST boxes are kept,
     * because a box you can see from across the street is the one you drive
     * into and a four-pixel detail is not.
     */
    private static final int MAX_BOXES = 96;

    /** Nothing to collide with, which is what an unreadable model gets. */
    public static final ModelShape NONE = new ModelShape(new float[0][]);

    /** minX, minY, minZ, maxX, maxY, maxZ, in local block units. */
    private final float[][] boxes;

    private final float minX;
    private final float minY;
    private final float minZ;
    private final float maxX;
    private final float maxY;
    private final float maxZ;

    private ModelShape(float[][] boxes) {
        this.boxes = boxes;
        float lowX = Float.MAX_VALUE;
        float lowY = Float.MAX_VALUE;
        float lowZ = Float.MAX_VALUE;
        float highX = -Float.MAX_VALUE;
        float highY = -Float.MAX_VALUE;
        float highZ = -Float.MAX_VALUE;
        for (float[] box : boxes) {
            lowX = Math.min(lowX, box[0]);
            lowY = Math.min(lowY, box[1]);
            lowZ = Math.min(lowZ, box[2]);
            highX = Math.max(highX, box[3]);
            highY = Math.max(highY, box[4]);
            highZ = Math.max(highZ, box[5]);
        }
        this.minX = lowX;
        this.minY = lowY;
        this.minZ = lowZ;
        this.maxX = highX;
        this.maxY = highY;
        this.maxZ = highZ;
    }

    /**
     * Builds a shape out of elements given in MODEL UNITS — the numbers as
     * they appear in the model file, 16 to the block.
     *
     * <p>Each entry is {@code from x,y,z} then {@code to x,y,z}. Degenerate
     * and back-to-front entries are tolerated rather than refused: this reads
     * files written by other people's tools, and one bad element should cost
     * that element and not the model.
     *
     * @param elements the raw boxes, six numbers each
     */
    public static ModelShape ofModelUnits(List<float[]> elements) {
        if (elements == null || elements.isEmpty()) {
            return NONE;
        }
        List<float[]> kept = new ArrayList<>(Math.min(elements.size(), MAX_BOXES));
        for (float[] raw : elements) {
            if (raw == null || raw.length < 6) {
                continue;
            }
            float x1 = Math.min(raw[0], raw[3]);
            float y1 = Math.min(raw[1], raw[4]);
            float z1 = Math.min(raw[2], raw[5]);
            float x2 = Math.max(raw[0], raw[3]);
            float y2 = Math.max(raw[1], raw[4]);
            float z2 = Math.max(raw[2], raw[5]);
            // A zero-thickness element is real and common — a flat panel is
            // drawn as one — and it collides with nothing at all, so it is
            // given the thinnest box that can be hit rather than dropped.
            if (x2 - x1 < 0.01f) {
                x1 -= 0.005f;
                x2 += 0.005f;
            }
            if (y2 - y1 < 0.01f) {
                y1 -= 0.005f;
                y2 += 0.005f;
            }
            if (z2 - z1 < 0.01f) {
                z1 -= 0.005f;
                z2 += 0.005f;
            }
            kept.add(new float[] {
                    (x1 - 8f) / 16f, y1 / 16f, (z1 - 8f) / 16f,
                    (x2 - 8f) / 16f, y2 / 16f, (z2 - 8f) / 16f});
        }
        if (kept.isEmpty()) {
            return NONE;
        }
        if (kept.size() > MAX_BOXES) {
            kept.sort(Comparator.comparingDouble(ModelShape::volumeOf).reversed());
            kept = new ArrayList<>(kept.subList(0, MAX_BOXES));
        }
        return new ModelShape(kept.toArray(new float[0][]));
    }

    private static double volumeOf(float[] box) {
        return (box[3] - box[0]) * (double) (box[4] - box[1]) * (box[5] - box[2]);
    }

    /** Whether there is any art to hit. */
    public boolean isEmpty() {
        return boxes.length == 0;
    }

    /** The boxes, local block units, six numbers each. Do not modify. */
    public float[][] boxes() {
        return boxes;
    }

    /**
     * Whether a point in the model's OWN frame is inside any of them.
     *
     * <p>Half open at the top in y, like a block's collision box, so a vehicle
     * resting on the model is not also inside it — see
     * {@code ModelObstacles.solidAt}, which is where that matters.
     */
    public boolean contains(double x, double y, double z) {
        if (x < minX || x > maxX || y < minY || y >= maxY || z < minZ || z > maxZ) {
            return false;
        }
        for (float[] box : boxes) {
            if (x >= box[0] && x <= box[3]
                    && y >= box[1] && y < box[4]
                    && z >= box[2] && z <= box[5]) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest box top above a point in the model's own frame, or
     * {@link Float#NaN} where the model has nothing over that spot.
     *
     * <p>This is what makes a model something a vehicle can stand ON as well as
     * bump into: the answer is per COLUMN, so a table is a surface at its top
     * over the table top and nothing at all beside a leg.
     */
    public double topAt(double x, double z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return Double.NaN;
        }
        double best = Double.NaN;
        for (float[] box : boxes) {
            if (x < box[0] || x > box[3] || z < box[2] || z > box[5]) {
                continue;
            }
            if (Double.isNaN(best) || box[4] > best) {
                best = box[4];
            }
        }
        return best;
    }

    /** Its widest horizontal reach from the centre, for a broad-phase bound. */
    public double reach() {
        if (isEmpty()) {
            return 0;
        }
        return Math.max(Math.max(Math.abs(minX), Math.abs(maxX)),
                Math.max(Math.abs(minZ), Math.abs(maxZ)));
    }

    /** How tall it stands above the block floor. */
    public double height() {
        return isEmpty() ? 0 : Math.max(0, maxY);
    }
}
