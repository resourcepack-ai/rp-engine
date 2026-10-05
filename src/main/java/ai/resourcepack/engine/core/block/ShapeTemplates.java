package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The game's own blockstate files for the shapes a custom block can take,
 * with each model replaced by the part it draws.
 *
 * <p>A shaped block is a vanilla block type taken over, and the game picks
 * which model to draw for each of that type's states from its blockstate file
 * - forty variants for a stair, thirty-two for a door, each with the turn and
 * the uvlock that make the corners line up. Those turns are the game's
 * knowledge, not ours, so they are transcribed rather than worked out: this is
 * vanilla's {@code waxed_cut_copper_stairs.json} and its siblings with every
 * model name turned into a role ({@code straight}, {@code inner},
 * {@code outer}), which {@link BlockAssets} then points at the block's own
 * models. Every block of a kind shares one layout, so the copper files stand
 * for any stair, slab, door or trapdoor a pack names.
 *
 * <p>Generated from the files themselves (1.21.x, unchanged in shape since
 * 1.19.4), by reading each variant's model and mapping its name to the role.
 * The properties a blockstate does not look at - a stair's
 * {@code waterlogged}, a door's {@code powered} - are absent here exactly as
 * they are there.
 */
final class ShapeTemplates {

    /** One variant: the state the game writes, the part drawn, and how it is turned. */
    record Variant(String state, String role, int x, int y, boolean uvlock) {
    }

    private static final Map<BlockInfo.Shape, List<Variant>> TEMPLATES = new EnumMap<>(BlockInfo.Shape.class);

    static {
        TEMPLATES.put(BlockInfo.Shape.STAIRS, List.of(
                v("facing=east,half=bottom,shape=inner_left", "inner", 0, 270, true),
                v("facing=east,half=bottom,shape=inner_right", "inner", 0, 0, false),
                v("facing=east,half=bottom,shape=outer_left", "outer", 0, 270, true),
                v("facing=east,half=bottom,shape=outer_right", "outer", 0, 0, false),
                v("facing=east,half=bottom,shape=straight", "straight", 0, 0, false),
                v("facing=east,half=top,shape=inner_left", "inner", 180, 0, true),
                v("facing=east,half=top,shape=inner_right", "inner", 180, 90, true),
                v("facing=east,half=top,shape=outer_left", "outer", 180, 0, true),
                v("facing=east,half=top,shape=outer_right", "outer", 180, 90, true),
                v("facing=east,half=top,shape=straight", "straight", 180, 0, true),
                v("facing=north,half=bottom,shape=inner_left", "inner", 0, 180, true),
                v("facing=north,half=bottom,shape=inner_right", "inner", 0, 270, true),
                v("facing=north,half=bottom,shape=outer_left", "outer", 0, 180, true),
                v("facing=north,half=bottom,shape=outer_right", "outer", 0, 270, true),
                v("facing=north,half=bottom,shape=straight", "straight", 0, 270, true),
                v("facing=north,half=top,shape=inner_left", "inner", 180, 270, true),
                v("facing=north,half=top,shape=inner_right", "inner", 180, 0, true),
                v("facing=north,half=top,shape=outer_left", "outer", 180, 270, true),
                v("facing=north,half=top,shape=outer_right", "outer", 180, 0, true),
                v("facing=north,half=top,shape=straight", "straight", 180, 270, true),
                v("facing=south,half=bottom,shape=inner_left", "inner", 0, 0, false),
                v("facing=south,half=bottom,shape=inner_right", "inner", 0, 90, true),
                v("facing=south,half=bottom,shape=outer_left", "outer", 0, 0, false),
                v("facing=south,half=bottom,shape=outer_right", "outer", 0, 90, true),
                v("facing=south,half=bottom,shape=straight", "straight", 0, 90, true),
                v("facing=south,half=top,shape=inner_left", "inner", 180, 90, true),
                v("facing=south,half=top,shape=inner_right", "inner", 180, 180, true),
                v("facing=south,half=top,shape=outer_left", "outer", 180, 90, true),
                v("facing=south,half=top,shape=outer_right", "outer", 180, 180, true),
                v("facing=south,half=top,shape=straight", "straight", 180, 90, true),
                v("facing=west,half=bottom,shape=inner_left", "inner", 0, 90, true),
                v("facing=west,half=bottom,shape=inner_right", "inner", 0, 180, true),
                v("facing=west,half=bottom,shape=outer_left", "outer", 0, 90, true),
                v("facing=west,half=bottom,shape=outer_right", "outer", 0, 180, true),
                v("facing=west,half=bottom,shape=straight", "straight", 0, 180, true),
                v("facing=west,half=top,shape=inner_left", "inner", 180, 180, true),
                v("facing=west,half=top,shape=inner_right", "inner", 180, 270, true),
                v("facing=west,half=top,shape=outer_left", "outer", 180, 180, true),
                v("facing=west,half=top,shape=outer_right", "outer", 180, 270, true),
                v("facing=west,half=top,shape=straight", "straight", 180, 180, true)
        ));
        TEMPLATES.put(BlockInfo.Shape.SLAB, List.of(
                v("type=bottom", "bottom", 0, 0, false),
                v("type=double", "double", 0, 0, false),
                v("type=top", "top", 0, 0, false)
        ));
        TEMPLATES.put(BlockInfo.Shape.DOOR, List.of(
                v("facing=east,half=lower,hinge=left,open=false", "bottom_left", 0, 0, false),
                v("facing=east,half=lower,hinge=left,open=true", "bottom_left_open", 0, 90, false),
                v("facing=east,half=lower,hinge=right,open=false", "bottom_right", 0, 0, false),
                v("facing=east,half=lower,hinge=right,open=true", "bottom_right_open", 0, 270, false),
                v("facing=east,half=upper,hinge=left,open=false", "top_left", 0, 0, false),
                v("facing=east,half=upper,hinge=left,open=true", "top_left_open", 0, 90, false),
                v("facing=east,half=upper,hinge=right,open=false", "top_right", 0, 0, false),
                v("facing=east,half=upper,hinge=right,open=true", "top_right_open", 0, 270, false),
                v("facing=north,half=lower,hinge=left,open=false", "bottom_left", 0, 270, false),
                v("facing=north,half=lower,hinge=left,open=true", "bottom_left_open", 0, 0, false),
                v("facing=north,half=lower,hinge=right,open=false", "bottom_right", 0, 270, false),
                v("facing=north,half=lower,hinge=right,open=true", "bottom_right_open", 0, 180, false),
                v("facing=north,half=upper,hinge=left,open=false", "top_left", 0, 270, false),
                v("facing=north,half=upper,hinge=left,open=true", "top_left_open", 0, 0, false),
                v("facing=north,half=upper,hinge=right,open=false", "top_right", 0, 270, false),
                v("facing=north,half=upper,hinge=right,open=true", "top_right_open", 0, 180, false),
                v("facing=south,half=lower,hinge=left,open=false", "bottom_left", 0, 90, false),
                v("facing=south,half=lower,hinge=left,open=true", "bottom_left_open", 0, 180, false),
                v("facing=south,half=lower,hinge=right,open=false", "bottom_right", 0, 90, false),
                v("facing=south,half=lower,hinge=right,open=true", "bottom_right_open", 0, 0, false),
                v("facing=south,half=upper,hinge=left,open=false", "top_left", 0, 90, false),
                v("facing=south,half=upper,hinge=left,open=true", "top_left_open", 0, 180, false),
                v("facing=south,half=upper,hinge=right,open=false", "top_right", 0, 90, false),
                v("facing=south,half=upper,hinge=right,open=true", "top_right_open", 0, 0, false),
                v("facing=west,half=lower,hinge=left,open=false", "bottom_left", 0, 180, false),
                v("facing=west,half=lower,hinge=left,open=true", "bottom_left_open", 0, 270, false),
                v("facing=west,half=lower,hinge=right,open=false", "bottom_right", 0, 180, false),
                v("facing=west,half=lower,hinge=right,open=true", "bottom_right_open", 0, 90, false),
                v("facing=west,half=upper,hinge=left,open=false", "top_left", 0, 180, false),
                v("facing=west,half=upper,hinge=left,open=true", "top_left_open", 0, 270, false),
                v("facing=west,half=upper,hinge=right,open=false", "top_right", 0, 180, false),
                v("facing=west,half=upper,hinge=right,open=true", "top_right_open", 0, 90, false)
        ));
        TEMPLATES.put(BlockInfo.Shape.TRAPDOOR, List.of(
                v("facing=east,half=bottom,open=false", "bottom", 0, 0, false),
                v("facing=east,half=bottom,open=true", "open", 0, 90, false),
                v("facing=east,half=top,open=false", "top", 0, 0, false),
                v("facing=east,half=top,open=true", "open", 0, 90, false),
                v("facing=north,half=bottom,open=false", "bottom", 0, 0, false),
                v("facing=north,half=bottom,open=true", "open", 0, 0, false),
                v("facing=north,half=top,open=false", "top", 0, 0, false),
                v("facing=north,half=top,open=true", "open", 0, 0, false),
                v("facing=south,half=bottom,open=false", "bottom", 0, 0, false),
                v("facing=south,half=bottom,open=true", "open", 0, 180, false),
                v("facing=south,half=top,open=false", "top", 0, 0, false),
                v("facing=south,half=top,open=true", "open", 0, 180, false),
                v("facing=west,half=bottom,open=false", "bottom", 0, 0, false),
                v("facing=west,half=bottom,open=true", "open", 0, 270, false),
                v("facing=west,half=top,open=false", "top", 0, 0, false),
                v("facing=west,half=top,open=true", "open", 0, 270, false)
        ));
        TEMPLATES.put(BlockInfo.Shape.GRATE, List.of(
                v("", "block", 0, 0, false)
        ));
        TEMPLATES.put(BlockInfo.Shape.BULB, List.of(
                v("lit=false,powered=false", "off", 0, 0, false),
                v("lit=false,powered=true", "off", 0, 0, false),
                v("lit=true,powered=false", "on", 0, 0, false),
                v("lit=true,powered=true", "on", 0, 0, false)
        ));
    }

    private ShapeTemplates() {
    }

    private static Variant v(String state, String role, int x, int y, boolean uvlock) {
        return new Variant(state, role, x, y, uvlock);
    }

    /** Every variant of a shape's blockstate file, or none for a cube. */
    static List<Variant> of(BlockInfo.Shape shape) {
        return TEMPLATES.getOrDefault(shape, List.of());
    }
}
