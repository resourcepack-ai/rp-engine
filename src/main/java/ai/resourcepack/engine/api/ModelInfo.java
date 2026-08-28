package ai.resourcepack.engine.api;

import java.util.Objects;

/**
 * What a content pack said about placing a model.
 *
 * <p>Placed model is an item you can put down. It renders as a display entity
 * rather than a block, so it is not limited to a cube and not limited to the
 * block grid — and pays for that by needing its own hitbox, because a display
 * entity cannot be clicked or collided with.
 *
 * <p>Free of Bukkit, so parsing is testable without a server.
 */
public final class ModelInfo {

    /** How a piece turns to face the player when it is put down. */
    /**
     * What a piece can be put on.
     *
     * <p>A torch goes on a wall, a chandelier hangs from a ceiling, and a
     * chair does neither. Refusing the wrong surface at placement time is the
     * difference between a lamp that reads as designed and one somebody has
     * stuck to the underside of a floor.
     */
    public enum Surface {

        /** The top of a block. Chairs, tables, statues. Everything, mostly. */
        FLOOR,

        /** The side of one. Torches, signs, brackets. */
        WALL,

        /** The underside. Chandeliers, hanging plants. */
        CEILING,

        /** Anywhere at all. */
        ANY;

        /** Whether this piece may be placed against {@code face}. */
        public boolean accepts(org.bukkit.block.BlockFace face) {
            switch (this) {
                case ANY:
                    return true;
                case WALL:
                    return face != org.bukkit.block.BlockFace.UP
                            && face != org.bukkit.block.BlockFace.DOWN;
                case CEILING:
                    return face == org.bukkit.block.BlockFace.DOWN;
                default:
                    return face == org.bukkit.block.BlockFace.UP;
            }
        }
    }

    public enum Facing {

        /** Snapped to north/east/south/west, like a placed furnace. */
        CARDINAL,

        /** Snapped to eight directions, for something that reads as angled. */
        DIAGONAL,

        /** Whatever the player was looking at. */
        FREE,

        /** Always the same way, for something with no front. */
        FIXED
    }

    private final ContentId id;
    private final ContentId item;
    private final Facing facing;
    private final float scale;
    private final float width;
    private final float height;
    private final boolean solid;
    private final float seat;
    private final int light;
    private final Surface surface;
    private final ContentId drop;

    private ModelInfo(ContentId id, ContentId item, Facing facing,
                          float scale, float width, float height, boolean solid, float seat,
                       int light, Surface surface, ContentId drop) {
        this.id = id;
        this.item = item;
        this.facing = facing;
        this.scale = scale;
        this.width = width;
        this.height = height;
        this.solid = solid;
        this.seat = seat;
        this.light = light;
        this.surface = surface;
        this.drop = drop;
    }

    /**
     * The light level it gives off, 0\u201315.
     *
     * <p>A display entity emits nothing, so this is a real vanilla light block
     * placed at the anchor and removed when the piece is broken \u2014 the same
     * trick as the barrier behind a solid piece, and the same cleanup problem
     * if it is ever skipped.
     */
    public int light() {
        return light;
    }

    /** Where it may be placed. */
    public Surface surface() {
        return surface;
    }

    /** What breaking it gives back, or empty for the item it was placed from. */
    public java.util.Optional<ContentId> drop() {
        return java.util.Optional.ofNullable(drop);
    }

    /** Engine internal; built by the model loader. */
    public static ModelInfo of(ContentId id, ContentId item, Facing facing,
                                   float scale, float width, float height, boolean solid, float seat) {
        return of(id, item, facing, scale, width, height, solid, seat, 0, Surface.FLOOR, null);
    }

    /**
     * @param light   0\u201315, the light level it gives off. 0 is none.
     * @param surface where it may be put: the floor, a wall, a ceiling, or any
     * @param drop    what breaking it gives back, or null for the item it was
     *                placed from
     */
    public static ModelInfo of(ContentId id, ContentId item, Facing facing,
                                   float scale, float width, float height, boolean solid, float seat,
                                   int light, Surface surface, ContentId drop) {
        return new ModelInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(item, "item"),
                facing == null ? Facing.CARDINAL : facing,
                scale, width, height, solid, seat,
                Math.max(0, Math.min(15, light)),
                surface == null ? Surface.FLOOR : surface,
                drop);
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /**
     * The custom item that places it, and that it drops when broken.
     *
     * <p>Placed model does not define its own look. It names an item, and the item
     * carries the model — so the thing in your hand and the thing on the floor
     * cannot disagree, and a pack that wants both only writes the model once.
     */
    public ContentId item() {
        return item;
    }

    /** How it turns to face a player putting it down. */
    public Facing facing() {
        return facing;
    }

    /** Size multiplier applied to the display. */
    public float scale() {
        return scale;
    }

    /** Hitbox width in blocks. */
    public float width() {
        return width;
    }

    /** Hitbox height in blocks. */
    public float height() {
        return height;
    }

    /**
     * How high off the block floor somebody sits on it, or 0 for a model
     * nobody sits on.
     *
     * <p>A chair, a bench, a car seat. Sitting is a right-click rather than a
     * separate kind of content: the model is already a model, and needing a
     * second id to make one of them sittable is the sort of tax that makes a
     * format feel like paperwork.
     */
    public float seat() {
        return seat;
    }

    /** Whether anybody can sit on it at all. */
    public boolean sittable() {
        return seat > 0f;
    }

    /**
     * Whether a player can walk through it.
     *
     * <p>A display entity has no collision at all, so a solid model needs a
     * real block behind it. That is a barrier at the anchor: invisible, solid,
     * and removed when the model is broken.
     */
    public boolean solid() {
        return solid;
    }

    @Override
    public String toString() {
        return id + " (" + item + ", " + facing + ")";
    }
}
