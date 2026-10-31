package ai.resourcepack.engine.core.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.function.Function;

/**
 * An inventory icon drawn from a Java block model, for the Bedrock twin of an
 * item that has a model and no flat sprite.
 *
 * <p>Bedrock draws every custom item in a slot as a flat picture, so an item
 * shaped by a 3D model (a chair, a lamp, a sword built from cubes) needs one.
 * Without it the item was left out of the Bedrock pack altogether and Bedrock
 * players saw its base material: a stack of paper. This draws what the Java
 * inventory shows: the model's own {@code display.gui} turn (vanilla's block
 * turn, 30 and 225 degrees, when it has none), lit the way the world shades a
 * block's faces, fitted to the picture.
 *
 * <p>A software rasteriser with a depth buffer, nothing more: one-sided faces,
 * cutout transparency (an alpha under half is a hole), nearest-neighbour
 * texels. What it ignores, because no inventory icon needs it: tints, the
 * {@code rescale} flag and texture animation (the first frame is drawn).
 */
public final class ModelIcon {

    private ModelIcon() {
    }

    /** The side of the picture. Bedrock scales an icon to its slot. */
    public static final int SIZE = 32;

    // Corners of each face as seen from outside it: top-left, top-right,
    // bottom-right, bottom-left, matching the face's uv rectangle corners.
    // Each corner picks min (0) or max (1) of the element on x, y, z.
    private static final Map<String, int[][]> CORNERS = Map.of(
            "north", new int[][]{{1, 1, 0}, {0, 1, 0}, {0, 0, 0}, {1, 0, 0}},
            "south", new int[][]{{0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {0, 0, 1}},
            "east", new int[][]{{1, 1, 1}, {1, 1, 0}, {1, 0, 0}, {1, 0, 1}},
            "west", new int[][]{{0, 1, 0}, {0, 1, 1}, {0, 0, 1}, {0, 0, 0}},
            "up", new int[][]{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}},
            "down", new int[][]{{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}});

    private static final Map<String, double[]> NORMALS = Map.of(
            "north", new double[]{0, 0, -1}, "south", new double[]{0, 0, 1},
            "east", new double[]{1, 0, 0}, "west", new double[]{-1, 0, 0},
            "up", new double[]{0, 1, 0}, "down", new double[]{0, -1, 0});


    /**
     * Draws {@code model} (a block model with its parents already merged in:
     * {@code elements}, {@code textures} and optionally {@code display}), or
     * returns null when it has no element with a face that resolves to a picture.
     *
     * @param textures a texture reference ({@code ns:block/oak}) to its image,
     *                 or null when there is none
     */
    public static BufferedImage render(JsonObject model, Function<String, BufferedImage> textures) {
        if (model == null || !model.has("elements") || !model.get("elements").isJsonArray()) return null;
        JsonObject textureMap = model.has("textures") && model.get("textures").isJsonObject()
                ? model.getAsJsonObject("textures") : new JsonObject();
        double[] turn = guiRotation(model);

        java.util.List<Quad> quads = new java.util.ArrayList<>();
        for (JsonElement el : model.getAsJsonArray("elements")) {
            if (!el.isJsonObject()) continue;
            JsonObject element = el.getAsJsonObject();
            double[] from = vec(element.get("from"));
            double[] to = vec(element.get("to"));
            if (from == null || to == null || !element.has("faces")) continue;
            JsonObject faces = element.getAsJsonObject("faces");
            for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
                int[][] corners = CORNERS.get(f.getKey());
                if (corners == null || !f.getValue().isJsonObject()) continue;
                JsonObject face = f.getValue().getAsJsonObject();
                BufferedImage image = face.has("texture")
                        ? resolve(face.get("texture").getAsString(), textureMap, textures, 0) : null;
                if (image == null) continue;
                double[] uv = faceUv(face, f.getKey(), from, to);
                int rotation = face.has("rotation") ? Math.floorMod(face.get("rotation").getAsInt() / 90, 4) : 0;
                double[][] points = new double[4][];
                double[][] uvs = new double[4][];
                double[][] uvCorners = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
                for (int i = 0; i < 4; i++) {
                    double[] p = {corners[i][0] == 0 ? from[0] : to[0], corners[i][1] == 0 ? from[1] : to[1],
                            corners[i][2] == 0 ? from[2] : to[2]};
                    points[i] = view(rotateElement(p, element), turn);
                    uvs[i] = uvCorners[Math.floorMod(i - rotation, 4)];
                }
                double[] n = rotateDirection(NORMALS.get(f.getKey()), element);
                double[] normal = turn(n[0], n[1], n[2], turn);
                if (normal[2] <= 1e-6) continue;
                quads.add(new Quad(points, uvs, image, shade(normal)));
            }
        }
        if (quads.isEmpty()) return null;

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Quad q : quads) {
            for (double[] p : q.points) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        double span = Math.max(maxX - minX, maxY - minY);
        if (span <= 0) return null;
        double scale = (SIZE - 2) / span;
        double offX = (SIZE - (maxX - minX) * scale) / 2;
        double offY = (SIZE - (maxY - minY) * scale) / 2;

        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        double[] depth = new double[SIZE * SIZE];
        java.util.Arrays.fill(depth, -Double.MAX_VALUE);
        for (Quad q : quads) {
            double[][] s = new double[4][];
            for (int i = 0; i < 4; i++) {
                // Screen y grows downwards; model y grows upwards.
                s[i] = new double[]{offX + (q.points[i][0] - minX) * scale,
                        offY + (maxY - q.points[i][1]) * scale, q.points[i][2]};
            }
            triangle(out, depth, s[0], s[1], s[2], q.uvs[0], q.uvs[1], q.uvs[2], q);
            triangle(out, depth, s[0], s[2], s[3], q.uvs[0], q.uvs[2], q.uvs[3], q);
        }
        return out;
    }

    /**
     * How lit a face is, from where it points once turned: the inventory's own
     * look, top brightest, the left side a little darker and the right darker
     * again, which is what makes a block read as solid.
     */
    private static double shade(double[] normal) {
        if (normal[1] > 0.5) return 1.0;
        if (normal[1] < -0.5) return 0.5;
        return normal[0] <= 0 ? 0.8 : 0.6;
    }

    private record Quad(double[][] points, double[][] uvs, BufferedImage image, double shade) {
    }

    private static void triangle(BufferedImage out, double[] depth, double[] a, double[] b, double[] c,
                                 double[] ua, double[] ub, double[] uc, Quad q) {
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (Math.abs(area) < 1e-9) return;
        int x0 = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int x1 = Math.min(SIZE - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int y1 = Math.min(SIZE - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        int texW = q.image.getWidth();
        // An animated texture is a vertical strip of square frames; draw the first.
        int texH = Math.min(q.image.getHeight(), texW);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
                double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) continue;
                double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int i = y * SIZE + x;
                if (z <= depth[i]) continue;
                double u = w0 * ua[0] + w1 * ub[0] + w2 * uc[0];
                double v = w0 * ua[1] + w1 * ub[1] + w2 * uc[1];
                int tx = Math.min(texW - 1, Math.max(0, (int) Math.floor(u / 16.0 * texW)));
                int ty = Math.min(texH - 1, Math.max(0, (int) Math.floor(v / 16.0 * texH)));
                int argb = q.image.getRGB(tx, ty);
                if ((argb >>> 24) < 128) continue;
                depth[i] = z;
                int r = (int) Math.round(((argb >> 16) & 0xFF) * q.shade);
                int g = (int) Math.round(((argb >> 8) & 0xFF) * q.shade);
                int bl = (int) Math.round((argb & 0xFF) * q.shade);
                out.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | bl);
            }
        }
    }

    /** The face's uv rectangle, or the one the game derives from the element when it has none. */
    private static double[] faceUv(JsonObject face, String dir, double[] from, double[] to) {
        double[] uv = face.has("uv") && face.get("uv").isJsonArray() && face.getAsJsonArray("uv").size() == 4
                ? new double[]{face.getAsJsonArray("uv").get(0).getAsDouble(), face.getAsJsonArray("uv").get(1).getAsDouble(),
                face.getAsJsonArray("uv").get(2).getAsDouble(), face.getAsJsonArray("uv").get(3).getAsDouble()}
                : null;
        if (uv != null) return uv;
        return switch (dir) {
            case "north" -> new double[]{16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case "south" -> new double[]{from[0], 16 - to[1], to[0], 16 - from[1]};
            case "east" -> new double[]{16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
            case "west" -> new double[]{from[2], 16 - to[1], to[2], 16 - from[1]};
            case "up" -> new double[]{from[0], from[2], to[0], to[2]};
            default -> new double[]{from[0], 16 - to[2], to[0], 16 - from[2]};
        };
    }

    /** {@code #key} through the model's texture map, to a picture. */
    private static BufferedImage resolve(String ref, JsonObject map, Function<String, BufferedImage> textures, int depth) {
        if (ref == null || depth > 8) return null;
        if (ref.startsWith("#")) {
            String key = ref.substring(1);
            return map.has(key) && map.get(key).isJsonPrimitive()
                    ? resolve(map.get(key).getAsString(), map, textures, depth + 1) : null;
        }
        return textures.apply(ref);
    }

    /** The gui display turn in degrees, or vanilla's block one. */
    private static double[] guiRotation(JsonObject model) {
        if (model.has("display") && model.get("display").isJsonObject()) {
            JsonObject display = model.getAsJsonObject("display");
            if (display.has("gui") && display.get("gui").isJsonObject()) {
                double[] r = vec(display.getAsJsonObject("gui").get("rotation"));
                if (r != null) return r;
            }
        }
        return new double[]{30, 225, 0};
    }

    /** Centres on the block and applies the display turn the way the game does: Z, then Y, then X. */
    private static double[] view(double[] p, double[] turn) {
        double x = p[0] - 8, y = p[1] - 8, z = p[2] - 8;
        return turn(x, y, z, turn);
    }

    private static double[] turn(double x, double y, double z, double[] turn) {
        double[] v = rotate(new double[]{x, y, z}, 2, turn[2]);
        v = rotate(v, 1, turn[1]);
        return rotate(v, 0, turn[0]);
    }

    private static double[] rotateDirection(double[] n, JsonObject element) {
        double[] out = n.clone();
        JsonObject rotation = element.has("rotation") && element.get("rotation").isJsonObject()
                ? element.getAsJsonObject("rotation") : null;
        if (rotation != null && rotation.has("axis") && rotation.has("angle")) {
            out = rotate(out, axis(rotation.get("axis").getAsString()), rotation.get("angle").getAsDouble());
        }
        return out;
    }

    private static double[] rotateElement(double[] p, JsonObject element) {
        JsonObject rotation = element.has("rotation") && element.get("rotation").isJsonObject()
                ? element.getAsJsonObject("rotation") : null;
        if (rotation == null || !rotation.has("axis") || !rotation.has("angle")) return p;
        double[] origin = vec(rotation.get("origin"));
        if (origin == null) origin = new double[]{8, 8, 8};
        double[] local = {p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]};
        double[] r = rotate(local, axis(rotation.get("axis").getAsString()), rotation.get("angle").getAsDouble());
        return new double[]{r[0] + origin[0], r[1] + origin[1], r[2] + origin[2]};
    }

    private static int axis(String name) {
        return switch (name) {
            case "x" -> 0;
            case "y" -> 1;
            default -> 2;
        };
    }

    /** Right-handed rotation about one axis, in degrees. */
    private static double[] rotate(double[] v, int axis, double degrees) {
        if (degrees == 0) return v;
        double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
        return switch (axis) {
            case 0 -> new double[]{v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c};
            case 1 -> new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
            default -> new double[]{v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]};
        };
    }

    private static double[] vec(JsonElement e) {
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        if (a.size() != 3) return null;
        try {
            return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
        } catch (RuntimeException bad) {
            return null;
        }
    }
}
