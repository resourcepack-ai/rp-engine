package ai.resourcepack.engine.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The boxes a model is actually made of, for anything that has to know its
 * SHAPE rather than its size.
 *
 * <p><strong>A block model is already a list of boxes.</strong> Every element
 * in one is a cuboid, so a model's real collision volume needs no approximation
 * and no voxelisation — it is the elements themselves. That is the whole idea
 * here: the one thing a placed model was missing was a way to ask where its art
 * is, and the art is boxes.
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
 * <h2>A turned element stays turned</h2>
 *
 * <p>The format stores an element as an unrotated cuboid plus an angle of up to
 * 45 degrees about one axis. <strong>The first version of this folded that into
 * the enclosing box, and it was wrong in a way people can feel</strong>: a long
 * thin rail on the diagonal — a handrail, a roof edge, the slabs the builder
 * makes octagons out of — encloses in a box several times its own volume, so a
 * vehicle stopped a foot away from the art with nothing visible in between.
 *
 * <p>So the angle is kept and the QUERY is turned instead, the same trick the
 * placement's own yaw uses one level up: inverse-rotating a point about the
 * element's origin costs a sine and a cosine and is exact, where rotating a box
 * gives something that is not a box and has to be over-covered. The enclosing
 * box survives only as a per-element early-out.
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
    public static final ModelShape NONE = new ModelShape(new Box[0]);

    /**
     * One element of a model, in MODEL UNITS, as the file states it.
     *
     * <p>Deliberately the file's own vocabulary — corners, an axis name, an
     * angle in degrees — so that reading a model is a transcription rather than
     * a conversion. Everything that turns those into something a vehicle can
     * ask about happens here, once.
     */
    public static final class Element {

        private final float[] box;
        private final int axis;
        private final float angle;
        private final float[] origin;

        private Element(float[] box, int axis, float angle, float[] origin) {
            this.box = box;
            this.axis = axis;
            this.angle = angle;
            this.origin = origin;
        }

        /** An unturned cuboid: {@code from x,y,z} then {@code to x,y,z}. */
        public static Element of(float[] box) {
            return new Element(box, -1, 0, null);
        }

        /**
         * A cuboid turned about one axis.
         *
         * @param axis   {@code "x"}, {@code "y"} or {@code "z"}; anything else
         *               is read as y, matching the format's own default
         * @param angle  in degrees
         * @param origin the point it turns about, in model units
         */
        public static Element turned(float[] box, String axis, float angle, float[] origin) {
            if (origin == null || origin.length < 3 || angle == 0 || !Float.isFinite(angle)) {
                return of(box);
            }
            int index = "x".equals(axis) ? 0 : "z".equals(axis) ? 2 : 1;
            return new Element(box, index, angle, origin);
        }
    }

    /**
     * One element, converted into the local frame and ready to be asked about.
     *
     * <p>The bounds are the element's own enclosing box AFTER its turn, which
     * is what lets nearly every query stop at six comparisons without ever
     * doing the trigonometry.
     */
    private static final class Box {

        private final float minX;
        private final float minY;
        private final float minZ;
        private final float maxX;
        private final float maxY;
        private final float maxZ;

        /** -1 for an unturned element, else 0/1/2 for x/y/z. */
        private final int axis;
        private final double cos;
        private final double sin;
        private final double ox;
        private final double oy;
        private final double oz;

        private final float boundMinX;
        private final float boundMinY;
        private final float boundMinZ;
        private final float boundMaxX;
        private final float boundMaxY;
        private final float boundMaxZ;

        Box(float[] bounds, int axis, double radians, float[] origin) {
            this.minX = bounds[0];
            this.minY = bounds[1];
            this.minZ = bounds[2];
            this.maxX = bounds[3];
            this.maxY = bounds[4];
            this.maxZ = bounds[5];
            this.axis = axis;
            this.cos = Math.cos(radians);
            this.sin = Math.sin(radians);
            this.ox = origin == null ? 0 : origin[0];
            this.oy = origin == null ? 0 : origin[1];
            this.oz = origin == null ? 0 : origin[2];

            if (axis < 0) {
                this.boundMinX = minX;
                this.boundMinY = minY;
                this.boundMinZ = minZ;
                this.boundMaxX = maxX;
                this.boundMaxY = maxY;
                this.boundMaxZ = maxZ;
                return;
            }
            // Every corner turned, and the box around the result. Only ever
            // read as an early-out, so being generous here costs a little work
            // and never a wrong answer.
            float lowX = Float.MAX_VALUE;
            float lowY = Float.MAX_VALUE;
            float lowZ = Float.MAX_VALUE;
            float highX = -Float.MAX_VALUE;
            float highY = -Float.MAX_VALUE;
            float highZ = -Float.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? minX : maxX;
                double y = (i & 2) == 0 ? minY : maxY;
                double z = (i & 4) == 0 ? minZ : maxZ;
                double[] turned = forward(x, y, z);
                lowX = (float) Math.min(lowX, turned[0]);
                highX = (float) Math.max(highX, turned[0]);
                lowY = (float) Math.min(lowY, turned[1]);
                highY = (float) Math.max(highY, turned[1]);
                lowZ = (float) Math.min(lowZ, turned[2]);
                highZ = (float) Math.max(highZ, turned[2]);
            }
            this.boundMinX = lowX;
            this.boundMinY = lowY;
            this.boundMinZ = lowZ;
            this.boundMaxX = highX;
            this.boundMaxY = highY;
            this.boundMaxZ = highZ;
        }

        /**
         * The two axes the turn moves, in order.
         *
         * <p>x turns y then z, y turns z then x, z turns x then y — the
         * right-handed pairing, and the same one the model format uses. Getting
         * it wrong mirrors the element, which on anything symmetric is
         * invisible.
         */
        private int first() {
            return axis == 0 ? 1 : axis == 1 ? 2 : 0;
        }

        private int second() {
            return axis == 0 ? 2 : axis == 1 ? 0 : 1;
        }

        private double origin(int component) {
            return component == 0 ? ox : component == 1 ? oy : oz;
        }

        /** A point in the element's own frame, out into the model's. */
        private double[] forward(double x, double y, double z) {
            double[] point = {x, y, z};
            int u = first();
            int v = second();
            double du = point[u] - origin(u);
            double dv = point[v] - origin(v);
            point[u] = origin(u) + du * cos - dv * sin;
            point[v] = origin(v) + du * sin + dv * cos;
            return point;
        }

        /** A point in the model's frame, back into the element's. */
        private double[] inverse(double x, double y, double z) {
            double[] point = {x, y, z};
            int u = first();
            int v = second();
            double du = point[u] - origin(u);
            double dv = point[v] - origin(v);
            point[u] = origin(u) + du * cos + dv * sin;
            point[v] = origin(v) - du * sin + dv * cos;
            return point;
        }

        boolean contains(double x, double y, double z) {
            if (x < boundMinX || x > boundMaxX
                    || y < boundMinY || y >= boundMaxY
                    || z < boundMinZ || z > boundMaxZ) {
                return false;
            }
            if (axis < 0) {
                return true;
            }
            double[] local = inverse(x, y, z);
            return local[0] >= minX && local[0] <= maxX
                    && local[1] >= minY && local[1] < maxY
                    && local[2] >= minZ && local[2] <= maxZ;
        }

        /**
         * The highest point of this element over the column at {@code (x, z)},
         * or NaN if it does not stand over that column at all.
         *
         * <p>For a turned element that is a ray against a box: the vertical
         * line through the column, taken into the element's own frame — where
         * it is no longer vertical — and clipped against the three slabs. The
         * parameter IS the model-frame y, because the line is parametrised by
         * it and a rotation preserves length, so the far end of the surviving
         * interval is the answer with nothing to convert back.
         */
        double topAt(double x, double z) {
            if (x < boundMinX || x > boundMaxX || z < boundMinZ || z > boundMaxZ) {
                return Double.NaN;
            }
            if (axis < 0) {
                return maxY;
            }
            // Where the line sits, and which way it runs, both in element space.
            double[] at = inverse(x, 0, z);
            double[] along = direction();
            double enter = Double.NEGATIVE_INFINITY;
            double leave = Double.POSITIVE_INFINITY;
            for (int component = 0; component < 3; component++) {
                double low = component == 0 ? minX : component == 1 ? minY : minZ;
                double high = component == 0 ? maxX : component == 1 ? maxY : maxZ;
                double start = at[component];
                double step = along[component];
                if (Math.abs(step) < 1e-9) {
                    if (start < low || start > high) {
                        return Double.NaN;
                    }
                    continue;
                }
                double one = (low - start) / step;
                double two = (high - start) / step;
                enter = Math.max(enter, Math.min(one, two));
                leave = Math.min(leave, Math.max(one, two));
            }
            return enter > leave ? Double.NaN : leave;
        }

        /** Straight up, in the element's own frame. */
        private double[] direction() {
            switch (axis) {
                case 0:
                    return new double[] {0, cos, -sin};
                case 2:
                    return new double[] {sin, cos, 0};
                default:
                    return new double[] {0, 1, 0};
            }
        }

        double volume() {
            return (maxX - minX) * (double) (maxY - minY) * (maxZ - minZ);
        }
    }

    private final Box[] boxes;

    private final float minX;
    private final float minY;
    private final float minZ;
    private final float maxX;
    private final float maxY;
    private final float maxZ;

    private ModelShape(Box[] boxes) {
        this.boxes = boxes;
        float lowX = Float.MAX_VALUE;
        float lowY = Float.MAX_VALUE;
        float lowZ = Float.MAX_VALUE;
        float highX = -Float.MAX_VALUE;
        float highY = -Float.MAX_VALUE;
        float highZ = -Float.MAX_VALUE;
        for (Box box : boxes) {
            lowX = Math.min(lowX, box.boundMinX);
            lowY = Math.min(lowY, box.boundMinY);
            lowZ = Math.min(lowZ, box.boundMinZ);
            highX = Math.max(highX, box.boundMaxX);
            highY = Math.max(highY, box.boundMaxY);
            highZ = Math.max(highZ, box.boundMaxZ);
        }
        this.minX = lowX;
        this.minY = lowY;
        this.minZ = lowZ;
        this.maxX = highX;
        this.maxY = highY;
        this.maxZ = highZ;
    }

    /** As {@link #ofElements}, for callers with nothing turned. */
    public static ModelShape ofModelUnits(List<float[]> boxes) {
        if (boxes == null) {
            return NONE;
        }
        List<Element> elements = new ArrayList<>(boxes.size());
        for (float[] box : boxes) {
            elements.add(Element.of(box));
        }
        return ofElements(elements);
    }

    /**
     * Builds a shape out of elements given in MODEL UNITS — the numbers as they
     * appear in the model file, 16 to the block.
     *
     * <p>Degenerate and back-to-front entries are tolerated rather than
     * refused: this reads files written by other people's tools, and one bad
     * element should cost that element and not the model.
     */
    public static ModelShape ofElements(List<Element> elements) {
        if (elements == null || elements.isEmpty()) {
            return NONE;
        }
        List<Box> kept = new ArrayList<>(Math.min(elements.size(), MAX_BOXES));
        for (Element element : elements) {
            if (element == null || element.box == null || element.box.length < 6) {
                continue;
            }
            float[] raw = element.box;
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
            float[] local = {
                (x1 - 8f) / 16f, y1 / 16f, (z1 - 8f) / 16f,
                (x2 - 8f) / 16f, y2 / 16f, (z2 - 8f) / 16f};
            float[] origin = element.origin == null ? null : new float[] {
                (element.origin[0] - 8f) / 16f,
                element.origin[1] / 16f,
                (element.origin[2] - 8f) / 16f};
            kept.add(new Box(local, element.axis, Math.toRadians(element.angle), origin));
        }
        if (kept.isEmpty()) {
            return NONE;
        }
        if (kept.size() > MAX_BOXES) {
            kept.sort(Comparator.comparingDouble(Box::volume).reversed());
            kept = new ArrayList<>(kept.subList(0, MAX_BOXES));
        }
        return new ModelShape(kept.toArray(new Box[0]));
    }

    /** Whether there is any art to hit. */
    public boolean isEmpty() {
        return boxes.length == 0;
    }

    /** How many elements it is made of. */
    public int size() {
        return boxes.length;
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
        for (Box box : boxes) {
            if (box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest art above a point in the model's own frame, or
     * {@link Double#NaN} where the model has nothing over that spot.
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
        for (Box box : boxes) {
            double top = box.topAt(x, z);
            if (!Double.isNaN(top) && (Double.isNaN(best) || top > best)) {
                best = top;
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
