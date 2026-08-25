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

    private ModelInfo(ContentId id, ContentId item, Facing facing,
                          float scale, float width, float height, boolean solid) {
        this.id = id;
        this.item = item;
        this.facing = facing;
        this.scale = scale;
        this.width = width;
        this.height = height;
        this.solid = solid;
    }

    /** Engine internal; built by the model loader. */
    public static ModelInfo of(ContentId id, ContentId item, Facing facing,
                                   float scale, float width, float height, boolean solid) {
        return new ModelInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(item, "item"),
                facing == null ? Facing.CARDINAL : facing,
                scale, width, height, solid);
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
