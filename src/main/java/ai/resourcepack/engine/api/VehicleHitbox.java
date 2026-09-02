package ai.resourcepack.engine.api;

/**
 * How big the vehicle is to click on and to stand in front of.
 *
 * <p>A vehicle's chassis is a marker armour stand, which has no bounding box
 * at all — deliberately, because that is what lets it be moved exactly. So the
 * body a player can actually interact with is made of {@code Interaction}
 * entities, and this says how big to make it.
 *
 * <p><strong>An {@code Interaction} has a square footprint.</strong> Its
 * width applies to both horizontal axes and there is no separate length, which
 * is fine for a chair and useless for a bus. So a vehicle's body is TILED: as
 * many boxes of {@code width} as it takes to cover {@code length}, laid along
 * the vehicle's forward axis and turned with it. That is the whole reason this
 * type has three numbers where the entity has two.
 */
public final class VehicleHitbox {

    /**
     * What a vehicle gets if its pack says nothing: a one-block cube.
     *
     * <p>Not "nothing", and not the model's own size. Nothing would leave a
     * vehicle with no way in but a seat marker, and the model's size is not
     * knowable here — the geometry is in the pack, and the server never reads
     * it. A block is small enough to be obviously wrong for a lorry and big
     * enough that every vehicle is clickable out of the box.
     */
    public static final VehicleHitbox DEFAULT = new VehicleHitbox(1, 1, 1);

    /** Bounds, so a bad number is a diagnostic rather than a car-park-sized box. */
    public static final double MIN = 0.25;
    public static final double MAX = 16;

    private final double width;
    private final double height;
    private final double length;

    private VehicleHitbox(double width, double height, double length) {
        this.width = width;
        this.height = height;
        this.length = length;
    }

    /** Clamped to {@link #MIN}..{@link #MAX}; anything unreadable falls back to a block. */
    public static VehicleHitbox of(double width, double height, double length) {
        return new VehicleHitbox(clamp(width), clamp(height), clamp(length));
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 1;
        }
        return Math.min(MAX, Math.max(MIN, value));
    }

    /** Side to side, in blocks. Also the size of each tile. */
    public double width() {
        return width;
    }

    /** Up from the vehicle's base, in blocks. */
    public double height() {
        return height;
    }

    /** Front to back, in blocks. */
    public double length() {
        return length;
    }

    /**
     * How many boxes it takes to cover the length.
     *
     * <p>At least one, and capped: a very long, very narrow vehicle would
     * otherwise ask for dozens of entities that all have to be moved every
     * tick. Sixteen covers anything anybody should be building, and a vehicle
     * that hits the cap gets slightly overlapping tiles rather than a gap.
     */
    public int tiles() {
        return Math.max(1, Math.min(16, (int) Math.ceil(length / width)));
    }

    /**
     * How far along the vehicle's forward axis tile {@code index} sits.
     *
     * <p>Centred on the vehicle, so a three-tile body runs from behind the
     * middle to in front of it rather than growing forwards out of the nose.
     */
    public double tileOffset(int index) {
        int count = tiles();
        if (count == 1) {
            return 0;
        }
        double step = length / count;
        return -length / 2 + step / 2 + step * index;
    }

    @Override
    public String toString() {
        return width + "x" + height + "x" + length;
    }
}
