package ai.resourcepack.engine.core.cape;

import java.math.BigDecimal;

/**
 * The block model a cape is drawn with, and its item definition.
 *
 * <p><strong>Studio builds the identical model for a pushed cape</strong>
 * (its cape layout's {@code capeModel}); this is the copy for a cape in a
 * content folder. Change one, change the other.
 *
 * <p>Vanilla's cape is one cube, {@code addBox(-5, 0, -1, 10, 16, 1)}, and its
 * texture is that cube's box unwrap in a 64x32 file. Model (8, 8, 8) is the
 * HINGE, the top edge against the middle of the back, in the wearer's frame
 * ({@code WornPose}): +Z their front, their right hand at -X. So the cape hangs
 * down from it and stands one pixel behind it. It is drawn a little larger
 * than vanilla's ({@link #INFLATE}) so that a player who also owns a Mojang
 * cape has theirs enclosed rather than z-fighting with ours.
 */
public final class CapeModel {

    /** Pixels the model stands proud of a vanilla cape on every side. */
    static final double INFLATE = 0.15;

    private CapeModel() {
    }

    /** The model, for a texture at {@code texture} ({@code namespace:path} under textures/). */
    public static String json(String texture) {
        // UVs are in sixteenths of the texture on each axis: 4 texels a unit
        // across a 64-wide file, 2 down a 32-tall one, at any resolution.
        String outer = uv(1, 1, 11, 17);
        String inner = uv(12, 1, 22, 17);
        String left = uv(0, 1, 1, 17);
        String right = uv(11, 1, 12, 17);
        // An up or down face runs u along +X, the wearer's right to left here;
        // flipped so column c of an edge sits over column c of the outer face.
        String top = uv(11, 0, 1, 1);
        String bottom = uv(21, 0, 11, 1);
        double i = INFLATE;
        return "{\"textures\":{\"cape\":\"" + texture + "\",\"particle\":\"" + texture + "\"},"
                + "\"elements\":[{"
                + "\"from\":[" + n(3 - i) + "," + n(-8 - i) + "," + n(7 - i) + "],"
                + "\"to\":[" + n(13 + i) + "," + n(8 + i) + "," + n(8 + i) + "],"
                + "\"faces\":{"
                + "\"north\":{\"uv\":" + outer + ",\"texture\":\"#cape\"},"
                + "\"south\":{\"uv\":" + inner + ",\"texture\":\"#cape\"},"
                + "\"east\":{\"uv\":" + left + ",\"texture\":\"#cape\"},"
                + "\"west\":{\"uv\":" + right + ",\"texture\":\"#cape\"},"
                + "\"up\":{\"uv\":" + top + ",\"texture\":\"#cape\"},"
                + "\"down\":{\"uv\":" + bottom + ",\"texture\":\"#cape\"}"
                + "}}]}";
    }

    /** The item definition naming {@code model}. */
    public static String itemDefinition(String model) {
        return "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + model + "\"}}";
    }

    private static String uv(double x0, double y0, double x1, double y1) {
        return "[" + n(x0 / 4) + "," + n(y0 / 2) + "," + n(x1 / 4) + "," + n(y1 / 2) + "]";
    }

    /** Four decimals at most, no trailing zeros: what Studio's writer prints for the same number. */
    static String n(double v) {
        return BigDecimal.valueOf(Math.round(v * 10000) / 10000.0).stripTrailingZeros().toPlainString();
    }
}
