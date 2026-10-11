package ai.resourcepack.engine.core.armor3d;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A 3D armour set as armour LAYERS that obj³'s core shaders turn back into
 * the set's own cubes, glued to the body by the client itself.
 *
 * <p><strong>A port of Studio's shader encoder, line for line, and it has to
 * stay one.</strong> Studio's is itself a port of the encoder in obj³
 * (github.com/JagerMeistars/obj-cubed, MIT, (c) 2022 Godlander and
 * contributors), from the commit whose shaders both ship. The shader decodes a
 * layer texture by byte position, so a header pixel written one column over is
 * a suit that renders as nothing, or as noise, with no error anywhere. So every
 * number, every rounding and every iteration order below is Studio's, and
 * {@code Armor3dShaderTest} holds the textures to hashes computed by Studio's
 * code over the same fixture. Change it only together with the shaders.
 *
 * <p>What the format is. The whole set (every face's corners relative to its
 * bone's pivot, every UV, and the set's own atlas) is packed into ONE texture
 * per armour layer type. Each piece gets a list of equipment layers, and each
 * layer names, through its dye colour ({@code color_when_undyed} = row + 1, the
 * one per-layer value the game hands a shader exactly), a row of a small table
 * under the model saying which of the set's faces this draw should become. The
 * game draws each layer as the vanilla armour model (a box per limb); the shader
 * moves each carrier face of each box onto one of the set's faces, in that box's
 * own frame, so the result swings, turns and crouches with the limb. This is
 * Studio's COMPACT form, the one it ships; obj³'s own one-texture-per-layer form
 * is not ported.
 *
 * <p>Pure apart from PNG encoding: no Bukkit, no disk.
 */
final class Armor3dShaderEncoder {

    /**
     * One face's art.
     *
     * @param rect     {x0, y0, x1, y1} in atlas pixels
     * @param rotation the face's UV rotation, 0, 90, 180 or 270. Studio's
     *                 sets never have one; a Blockbench face can, and turning
     *                 the corner list is what the game does with it
     */
    record FaceArt(double[] rect, int rotation) {
    }

    /**
     * One cube.
     *
     * @param rotation degrees about x, y and z, z applied first, about origin, or null
     * @param faces    by face name, in the order they are to be encoded
     */
    record Part(String bone, double[] from, double[] to, double[] rotation, double[] origin,
                Map<String, FaceArt> faces) {
    }

    /**
     * @param baseFiles    the layer textures, by zip path; harmless in the base pack
     *                     (nothing names them outside an overlay)
     * @param overlayFiles the equipment assets, by path relative to the overlay
     *                     directory; in the base pack they would make a client
     *                     with no shader draw the encoded layers as noise
     * @param equipment    the asset id per piece that has faces
     */
    record Encoded(Map<String, byte[]> baseFiles, Map<String, byte[]> overlayFiles,
                   Map<Armor3dSet.Piece, String> equipment, int nfaces) {
    }

    // obj³'s body-part ids. Waist (8) rides the leggings layer's body box.
    private static final Map<String, Integer> PART = Map.of(
            "body", 0, "head", 1, "right_arm", 2, "left_arm", 3, "right_leg", 4, "left_leg", 5,
            "right_foot", 6, "left_foot", 7, "waist", 8);

    // Each bone's pivot: vanilla's shoulder, hip and neck. A foot is measured
    // from its hip, the waist from the neck.
    private static final double[][] PIVOT = {
            {0, 24, 0}, {0, 24, 0}, {-5, 22, 0}, {5, 22, 0},
            {-1.9, 12, 0}, {1.9, 12, 0}, {-1.9, 12, 0}, {1.9, 12, 0}, {0, 24, 0},
    };

    /** Per face: normal, the texture's right and its up. */
    private static final Map<String, double[][]> FACE = Map.of(
            "south", new double[][]{{0, 0, 1}, {1, 0, 0}, {0, 1, 0}},
            "north", new double[][]{{0, 0, -1}, {-1, 0, 0}, {0, 1, 0}},
            "east", new double[][]{{1, 0, 0}, {0, 0, -1}, {0, 1, 0}},
            "west", new double[][]{{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
            "up", new double[][]{{0, 1, 0}, {1, 0, 0}, {0, 0, -1}},
            "down", new double[][]{{0, -1, 0}, {1, 0, 0}, {0, 0, 1}});

    /** Piece to armour layer, its carrier boxes (box index to part) and the tag each box collects. */
    private record PieceMap(Armor3dSet.Piece piece, String layer, int[] allowed, int[] carrier, int[] tags,
                            int nboxes) {
    }

    private static final PieceMap[] PIECES = {
            new PieceMap(Armor3dSet.Piece.HELMET, "humanoid", new int[]{1}, new int[]{1}, null, 2),
            new PieceMap(Armor3dSet.Piece.CHESTPLATE, "humanoid", new int[]{0, 2, 3}, new int[]{2, 3, 0}, null, 3),
            new PieceMap(Armor3dSet.Piece.LEGGINGS, "humanoid_leggings", new int[]{4, 5, 8}, new int[]{5, 4, 0},
                    new int[]{5, 4, 8}, 3),
            new PieceMap(Armor3dSet.Piece.BOOTS, "humanoid", new int[]{6, 7}, new int[]{7, 6}, null, 2),
    };

    /** Display slots obj³ gives a "dynamic" marker with identity transforms. */
    private static final int DYNAMIC_SLOTS = 2;

    private static final int HEADER_FROM = 8;
    private static final int HEADER_TEXELS = 15;

    private Armor3dShaderEncoder() {
    }

    /**
     * Encodes a set.
     *
     * @param namespace owns every file written
     * @param prefix    names every asset: {@code <prefix>_<piece>}, and the
     *                  layer texture {@code <prefix>}
     * @param parts     the cubes, already separated (see {@link Armor3dSeparation})
     * @param atlasW    the atlas's width, and with atlasH what face rects are
     *                  measured against (Studio's {@code atlasSize})
     * @param rgba      the atlas, RGBA8 rows top to bottom
     * @throws IllegalArgumentException when the set cannot be encoded, with why
     */
    static Encoded encode(String namespace, String prefix, List<Part> parts, int atlasW, int atlasH, byte[] rgba) {
        if (!prefix.matches("[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Bad armour asset prefix: " + prefix);
        }

        // ---- the faces, in the order the reference emits them --------------
        // Values go through toFixed(6) because the reference passes them
        // through OBJ text; the bytes only match if the numbers do.
        List<Integer> faceParts = new ArrayList<>();
        List<double[][]> faceCorners = new ArrayList<>();
        List<double[][]> faceUvs = new ArrayList<>();
        for (Part p : parts) {
            Integer part = PART.get(p.bone());
            if (part == null) {
                continue;
            }
            double[] c = new double[3];
            double[] h = new double[3];
            for (int k = 0; k < 3; k++) {
                c[k] = (p.from()[k] + p.to()[k]) / 2;
                h[k] = (p.to()[k] - p.from()[k]) / 2;
            }
            for (Map.Entry<String, FaceArt> entry : p.faces().entrySet()) {
                double[][] f = FACE.get(entry.getKey());
                if (entry.getValue() == null || f == null) {
                    continue;
                }
                double[] n = f[0];
                double[] right = f[1];
                double[] up = f[2];
                double[] centre = new double[3];
                for (int k = 0; k < 3; k++) {
                    centre[k] = c[k] + n[k] * h[k];
                }
                double hr = Math.abs(right[0] * h[0] + right[1] * h[1] + right[2] * h[2]);
                double hu = Math.abs(up[0] * h[0] + up[1] * h[1] + up[2] * h[2]);
                double[][] signs = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};
                double[][] corners = new double[4][];
                for (int i = 0; i < 4; i++) {
                    double[] pt = new double[3];
                    for (int k = 0; k < 3; k++) {
                        pt[k] = centre[k] + right[k] * hr * signs[i][0] + up[k] * hu * signs[i][1];
                    }
                    if (p.rotation() != null && p.origin() != null) {
                        double[] o = p.origin();
                        double[] turned = rotXYZ(new double[]{pt[0] - o[0], pt[1] - o[1], pt[2] - o[2]}, p.rotation());
                        pt = new double[]{turned[0] + o[0], turned[1] + o[1], turned[2] + o[2]};
                    }
                    // Turned half round about Y: obj³ builds a set facing -Z.
                    pt = new double[]{-pt[0], pt[1], -pt[2]};
                    corners[i] = new double[]{fixed6(pt[0] / 16), fixed6(pt[1] / 16), fixed6(pt[2] / 16)};
                }
                double[] rect = entry.getValue().rect();
                double u0 = rect[0], v0 = rect[1], u1 = rect[2], v1 = rect[3];
                double[][] uvList = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                int turns = Math.floorMod(entry.getValue().rotation() / 90, 4);
                double[][] uvs = new double[4][];
                for (int i = 0; i < 4; i++) {
                    // A face turned clockwise by n quarters shows at corner i
                    // what was at corner i - n.
                    double[] uv = uvList[Math.floorMod(i - turns, 4)];
                    uvs[i] = new double[]{fixed6(uv[0] / atlasW), fixed6(1 - uv[1] / atlasH)};
                }
                faceParts.add(part);
                faceCorners.add(corners);
                faceUvs.add(uvs);
            }
        }
        int nfaces = faceParts.size();
        if (nfaces == 0) {
            throw new IllegalArgumentException("the set has no faces to encode");
        }

        // Each part is measured from its pivot, turned the same half round.
        Map<Integer, double[]> partRef = new HashMap<>();
        Set<Integer> present = new HashSet<>(faceParts);
        for (int id = 0; id < PIVOT.length; id++) {
            if (!present.contains(id)) {
                continue;
            }
            double[] o = PIVOT[id];
            double[] turned = {-o[0], o[1], -o[2]};
            partRef.put(id, new double[]{turned[0] / 16, turned[1] / 16, turned[2] / 16});
        }

        // ---- positions, uvs and vertices, deduplicated as the reference does
        List<double[]> positions = new ArrayList<>();
        List<double[]> uvs = new ArrayList<>();
        List<int[]> vertices = new ArrayList<>();
        Map<String, Integer> posIndex = new HashMap<>();
        Map<String, Integer> uvIndex = new HashMap<>();
        for (int fi = 0; fi < nfaces; fi++) {
            double[] r = partRef.get(faceParts.get(fi));
            for (int i = 0; i < 4; i++) {
                double[] pos = faceCorners.get(fi)[i];
                if (r != null) {
                    pos = new double[]{pos[0] - r[0], pos[1] - r[1], pos[2] - r[2]};
                }
                double[] uv = faceUvs.get(fi)[i];
                // JavaScript's join prints -0 as 0, so the two are one key there.
                String pk = (pos[0] + 0.0) + "," + (pos[1] + 0.0) + "," + (pos[2] + 0.0);
                String uk = toFixed(uv[0], 8) + "," + toFixed(uv[1], 8);
                Integer pi = posIndex.get(pk);
                if (pi == null) {
                    pi = positions.size();
                    posIndex.put(pk, pi);
                    positions.add(pos);
                }
                Integer ui = uvIndex.get(uk);
                if (ui == null) {
                    ui = uvs.size();
                    uvIndex.put(uk, ui);
                    uvs.add(uv);
                }
                vertices.add(new int[]{pi, ui});
            }
        }

        // ---- the texture -----------------------------------------------------
        int tw = atlasW;
        int th = atlasH;
        if (tw < 23) {
            throw new IllegalArgumentException("an armour atlas must be at least 23px wide (got " + tw + ")");
        }
        if (tw > 65535 || th > 65535) {
            throw new IllegalArgumentException("armour atlas too large: " + tw + "x" + th);
        }
        for (double[] p : positions) {
            for (int a = 0; a < 3; a++) {
                double w = p[a] - (a == 1 ? 0.5 : 0);
                if (!(w >= -128 && w < 128)) {
                    throw new IllegalArgumentException("a cube is too far from its bone to encode");
                }
            }
        }
        int nvertices = nfaces * 4;
        int headerRows = 2;
        int uvH = ceilDiv(nfaces, tw);
        int vpH = ceilDiv(positions.size() * 3, tw);
        int vtH = ceilDiv(uvs.size() * 2, tw);
        int vH = ceilDiv(vertices.size() * 2, tw);
        int usedRows = headerRows + uvH + th + vpH + vtH + vH;
        int ty = powerOfTwoAtLeast(Math.max(usedRows, 1));

        Texture buf = new Texture(tw, ty);

        // Row 0: the header. One frame, one texture, no animation; the GUI
        // block is identity.
        int nframes = 1;
        int frameH = th;
        buf.put(0, 0, 12, 34, 56, 255);
        buf.put(1, 0, tw / 256, tw % 256, frameH / 256, 255);
        buf.put(2, 0, (nvertices / 16777216) % 256, (nvertices / 65536) % 256, (nvertices / 256) % 256, 255);
        buf.put(3, 0, (nframes / 65536) % 256, (nframes / 256) % 256, nframes % 256, 1);
        buf.put(4, 0, 0, 0, 1, 128); // duration 1, version bit, no autoplay/easing/interpolation
        buf.put(5, 0, (vpH / 256) % 256, vpH % 256, (vtH / 256) % 256, 255);
        buf.put(6, 0, 7 << 2, 0, 2, 255); // visibility 7, colour behaviour direct x3, GUI header v2
        buf.put(7, 0, frameH % 256, nvertices % 256, vtH % 256, 255);
        buf.put(8, 0, 64, 0, 64, 255);
        buf.put(9, 0, 0, 64, 0, 255);
        buf.put(10, 0, 128, 0, 128, 255);
        buf.put(11, 0, 0, 128, 0, 255);
        buf.put(12, 0, 0, 0, 0, 255);
        buf.put(13, 0, 0, 0, 0, 255);
        buf.put(14, 0, 128, 0, 128, 255);
        buf.put(15, 0, 0, 128, 0, 255);
        // Row 1: zeroed, then the two lifted display slots' markers.
        for (int i = 0; i < tw; i++) {
            buf.put(i, 1, 0, 0, 0, 255);
        }
        buf.put(5, 1, DYNAMIC_SLOTS << 1, 0, 1, 255);
        for (int i = 0; i < DYNAMIC_SLOTS; i++) {
            buf.put(i, 1, 0, 0, 1, 255);
        }

        // The per-face UV header.
        for (int i = 0; i < nfaces; i++) {
            int px = i % tw;
            int py = i / tw + headerRows;
            buf.put(px, py, (px / 256) % 256, px % 256, (py / 256) % 256, py % 256);
        }
        // The atlas, flipped.
        for (int py = 0; py < th; py++) {
            int srcY = th - 1 - py;
            for (int px = 0; px < tw; px++) {
                int si = (srcY * tw + px) * 4;
                buf.put(px, headerRows + uvH + py, rgba[si] & 255, rgba[si + 1] & 255, rgba[si + 2] & 255,
                        rgba[si + 3] & 255);
            }
        }
        // Positions, with obj³'s -0.5 block-centre Y bake; then UVs; then vertices.
        int ybase = headerRows + uvH + th;
        double[] bakeOffset = {0, -0.5, 0};
        for (int i = 0; i < positions.size(); i++) {
            for (int j = 0; j < 3; j++) {
                int p = i * 3 + j;
                int[] v = u24(8388608 + positions.get(i)[j] * 65536 + bakeOffset[j] * 65536);
                buf.put(p % tw, ybase + p / tw, v[0], v[1], v[2], 255);
            }
        }
        ybase += vpH;
        for (int i = 0; i < uvs.size(); i++) {
            for (int j = 0; j < 2; j++) {
                int p = i * 2 + j;
                int[] v = u24(Math.max(0, Math.min(1, uvs.get(i)[j])) * 65535);
                buf.put(p % tw, ybase + p / tw, v[0], v[1], v[2], 255);
            }
        }
        ybase += vtH;
        for (int i = 0; i < vertices.size(); i++) {
            int[] poi = u24(vertices.get(i)[0]);
            int[] uvi = u24(vertices.get(i)[1]);
            buf.put((i * 2) % tw, ybase + (i * 2) / tw, poi[0], poi[1], poi[2], 255);
            buf.put((i * 2 + 1) % tw, ybase + (i * 2 + 1) / tw, uvi[0], uvi[1], uvi[2], 255);
        }

        // ---- one packed layer per four faces per box -------------------------
        Map<String, List<byte[]>> tables = new LinkedHashMap<>();
        Map<String, byte[]> baseFiles = new TreeMap<>();
        Map<String, byte[]> overlayFiles = new TreeMap<>();
        Map<Armor3dSet.Piece, String> equipment = new EnumMap<>(Armor3dSet.Piece.class);
        for (PieceMap piece : PIECES) {
            String eqName = prefix + "_" + piece.piece().wire();
            int[] tags = piece.tags() != null ? piece.tags() : piece.carrier();
            List<List<Integer>> boxFaces = new ArrayList<>();
            for (int tag : tags) {
                List<Integer> found = new ArrayList<>();
                if (contains(piece.allowed(), tag)) {
                    for (int k = 0; k < faceParts.size(); k++) {
                        if (faceParts.get(k) == tag) {
                            found.add(k);
                        }
                    }
                }
                boxFaces.add(found);
            }
            int layerCount = 1;
            for (List<Integer> f : boxFaces) {
                layerCount = Math.max(layerCount, ceilDiv(f.size(), 4));
            }
            List<byte[]> table = tables.computeIfAbsent(piece.layer(), k -> new ArrayList<>());
            List<Integer> rows = new ArrayList<>();
            for (int layer = 0; layer < layerCount; layer++) {
                Integer[][] slots = new Integer[piece.carrier().length][];
                boolean any = false;
                for (int abody = 0; abody < piece.carrier().length; abody++) {
                    Integer[] slot = new Integer[5];
                    boolean filled = false;
                    for (int j = 0; j < 4; j++) {
                        int at = 4 * layer + j;
                        slot[j] = at < boxFaces.get(abody).size() ? boxFaces.get(abody).get(at) : null;
                        filled |= slot[j] != null;
                    }
                    slot[4] = piece.carrier()[abody];
                    slots[abody] = filled ? slot : null;
                    any |= filled;
                }
                if (!any) {
                    continue;
                }
                rows.add(table.size());
                table.add(layerHeader(buf, piece.nboxes(), slots, piece.carrier()));
            }
            if (rows.isEmpty()) {
                continue;
            }
            StringBuilder json = new StringBuilder("{\n  \"layers\": {\n    \"" + piece.layer() + "\": [\n");
            for (int i = 0; i < rows.size(); i++) {
                json.append("      {\n        \"texture\": \"").append(namespace).append(':').append(prefix)
                        .append("\",\n        \"dyeable\": {\n          \"color_when_undyed\": ")
                        .append(rows.get(i) + 1).append("\n        }\n      }")
                        .append(i + 1 < rows.size() ? ",\n" : "\n");
            }
            json.append("    ]\n  }\n}");
            overlayFiles.put("assets/" + namespace + "/equipment/" + eqName + ".json",
                    json.toString().getBytes(StandardCharsets.UTF_8));
            equipment.put(piece.piece(), namespace + ":" + eqName);
        }
        // The model once per layer type, marker 252, the headers as a table
        // from row usedRows, whose first row is written into texel 23 of row 0.
        for (Map.Entry<String, List<byte[]>> entry : tables.entrySet()) {
            List<byte[]> headers = entry.getValue();
            if (headers.isEmpty()) {
                continue;
            }
            int height = powerOfTwoAtLeast(usedRows + headers.size());
            byte[] data = new byte[tw * height * 4];
            System.arraycopy(buf.data, 0, data, 0, usedRows * tw * 4);
            data[3] = (byte) 252;
            int t23 = 23 * 4;
            data[t23] = (byte) ((usedRows / 256) % 256);
            data[t23 + 1] = (byte) (usedRows % 256);
            data[t23 + 2] = 0;
            data[t23 + 3] = (byte) 255;
            for (int row = 0; row < headers.size(); row++) {
                byte[] header = headers.get(row);
                System.arraycopy(header, 0, data, ((usedRows + row) * tw + HEADER_FROM) * 4, header.length);
            }
            baseFiles.put("assets/" + namespace + "/textures/entity/equipment/" + entry.getKey() + "/" + prefix + ".png",
                    png(tw, height, data));
        }
        return new Encoded(baseFiles, overlayFiles, equipment, nfaces);
    }

    /**
     * A layer's own header: texels 8..22 of row 0 as obj³'s writePackedLayer
     * leaves them: box count, per-box face indices and part, emissive.
     *
     * @param slots per box, {north, south, west, east, part} or null
     */
    private static byte[] layerHeader(Texture buf, int nboxes, Integer[][] slots, int[] parts) {
        byte[] h = new byte[HEADER_TEXELS * 4];
        System.arraycopy(buf.data, HEADER_FROM * 4, h, 0, h.length);
        h[at(8) + 3] = (byte) (nboxes & 255);
        for (int abody = 0; abody < nboxes; abody++) {
            Integer[] sl = abody < slots.length ? slots[abody] : null;
            put16(h, 8 + abody, sl != null ? sl[0] : null);
            int part = sl != null ? sl[4] : (abody < parts.length ? parts[abody] : 0);
            h[at(8 + abody) + 2] = (byte) (part & 255);
            put16(h, 11 + abody, sl != null ? sl[1] : null);
            put16(h, 14 + abody, sl != null ? sl[2] : null);
            put16(h, 17 + abody, sl != null ? sl[3] : null);
            // Emissive levels: none of our faces glow.
            int eo = at(20 + abody);
            h[eo] = h[eo + 1] = h[eo + 2] = h[eo + 3] = 0;
        }
        return h;
    }

    private static int at(int texel) {
        return (texel - HEADER_FROM) * 4;
    }

    private static void put16(byte[] h, int texel, Integer k) {
        int o = at(texel);
        if (k == null) {
            h[o] = (byte) 255;
            h[o + 1] = (byte) 255;
        } else {
            h[o] = (byte) ((k / 256) % 256);
            h[o + 1] = (byte) (k % 256);
        }
    }

    /** RGBA8, out-of-range writes dropped as Studio's {@code put} drops them. */
    private static final class Texture {
        final int width;
        final int height;
        final byte[] data;

        Texture(int width, int height) {
            this.width = width;
            this.height = height;
            this.data = new byte[width * height * 4];
        }

        void put(int x, int y, int r, int g, int b, int a) {
            if (x < 0 || x >= width || y < 0 || y >= height) {
                return;
            }
            int i = (y * width + x) * 4;
            data[i] = (byte) (r & 255);
            data[i + 1] = (byte) (g & 255);
            data[i + 2] = (byte) (b & 255);
            data[i + 3] = (byte) (a & 255);
        }
    }

    /** Rx . Ry . Rz applied to v (Z first): the set's rotation order, arithmetic kept verbatim. */
    private static double[] rotXYZ(double[] v, double[] a) {
        double x = v[0], y = v[1], z = v[2];
        double ax = a[0] * Math.PI / 180, ay = a[1] * Math.PI / 180, az = a[2] * Math.PI / 180;
        double x1 = x * Math.cos(az) - y * Math.sin(az);
        double y1 = x * Math.sin(az) + y * Math.cos(az);
        double x2 = x1 * Math.cos(ay) + z * Math.sin(ay);
        double z1 = -x1 * Math.sin(ay) + z * Math.cos(ay);
        double y2 = y1 * Math.cos(ax) - z1 * Math.sin(ax);
        double z2 = y1 * Math.sin(ax) + z1 * Math.cos(ax);
        return new double[]{x2, y2, z2};
    }

    private static int[] u24(double v) {
        return new int[]{(int) (((long) (v / 65536)) & 255), (int) (((long) (v / 256)) & 255), (int) (((long) v) & 255)};
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static int powerOfTwoAtLeast(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /** JavaScript's {@code +x.toFixed(6)}. */
    private static double fixed6(double x) {
        return Double.parseDouble(toFixed(x, 6));
    }

    /**
     * JavaScript's {@code Number.prototype.toFixed}: the exact value rounded
     * half away from zero, with a minus sign whenever the number is below zero
     * (so a tiny negative prints {@code -0.000}, and negative zero does not).
     */
    static String toFixed(double x, int digits) {
        String body = new BigDecimal(Math.abs(x)).setScale(digits, RoundingMode.HALF_UP).toPlainString();
        return x < 0 ? "-" + body : body;
    }

    static byte[] png(int width, int height, byte[] rgba) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = ((rgba[i * 4 + 3] & 255) << 24) | ((rgba[i * 4] & 255) << 16)
                    | ((rgba[i * 4 + 1] & 255) << 8) | (rgba[i * 4 + 2] & 255);
        }
        image.setRGB(0, 0, width, height, argb, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
