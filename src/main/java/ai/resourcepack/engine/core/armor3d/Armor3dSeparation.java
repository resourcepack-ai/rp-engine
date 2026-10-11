package ai.resourcepack.engine.core.armor3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No two faces of a shader-drawn set may share a plane, because the game draws
 * armour with back faces ON.
 *
 * <p>Every humanoid armour layer renders through a no-cull cutout render type,
 * and the shader-drawn set is an armour layer, so each quad is drawn from both
 * sides. That turns two things a modeller does all the time into z-fighting:
 * a FLAT cube (zero thickness on one axis: a fin, a leaf card) has its two
 * faces in one plane, and two faces in one plane facing the same way (a trim
 * flush on a plate) cannot be ordered by the depth buffer.
 *
 * <p>So a flat is given {@link #FLAT_THICKNESS}, split evenly about where it
 * was drawn, and then every group of same-facing overlapping faces in one
 * plane is fanned out along the normal, the LARGEST keeping the plane and each
 * smaller one a {@code STEP} further out than the last, so a detail always
 * sits in front of whatever it was painted onto. Nothing ever moves inward.
 *
 * <p><strong>A port of Studio's separation pass, line for line</strong>, and
 * it has to stay one: a set authored here and the same set pushed from Studio
 * must reach the shader as the same geometry. The bucket keys, the iteration
 * orders and the stable sort are all Studio's, because they decide which face
 * of a tied group moves. {@code Armor3dShaderTest} holds both to numbers
 * computed by Studio's code.
 *
 * <p>Only the shader path uses it. The displays cull back faces like any
 * item, so they are baked from the cubes as drawn.
 *
 * <p>Pure: no Bukkit, no disk.
 */
final class Armor3dSeparation {

    /** How thick a flat cube is made, in pixels: twice the step, so both faces clear the depth buffer. */
    static final double FLAT_THICKNESS = 0.12;
    private static final double STEP = 0.06;
    private static final double EPS_PLANE = 0.01;
    private static final double MIN_AREA = 0.05;
    private static final double FLAT = 1e-6;

    /** Face name to {axis, side}, in Studio's order. */
    private static final Map<String, int[]> DIRS = dirs();

    private static Map<String, int[]> dirs() {
        Map<String, int[]> map = new LinkedHashMap<>();
        map.put("west", new int[]{0, -1});
        map.put("east", new int[]{0, 1});
        map.put("down", new int[]{1, -1});
        map.put("up", new int[]{1, 1});
        map.put("north", new int[]{2, -1});
        map.put("south", new int[]{2, 1});
        return map;
    }

    private Armor3dSeparation() {
    }

    /**
     * A cube as this pass sees it: its box, its own turn and which faces it
     * has. {@code from} and {@code to} are moved in place.
     *
     * @param rotation degrees about x, y and z, z applied first, or null
     * @param origin   where it turns, or null for its centre
     */
    record Part(double[] from, double[] to, double[] rotation, double[] origin, Set<String> faces) {
    }

    /** One face of one part, in the wearer's frame. */
    private record Face(int idx, int ax, int side, double[] n, double off, double[][] poly, double area) {
    }

    private interface Turn {
        double[] apply(double[] p);
    }

    private static Turn turnOf(Part part) {
        double[] r = part.rotation();
        if (r == null || (r[0] == 0 && r[1] == 0 && r[2] == 0)) {
            return p -> p;
        }
        double x = r[0] * Math.PI / 180;
        double y = r[1] * Math.PI / 180;
        double z = r[2] * Math.PI / 180;
        double cx = Math.cos(x), sx = Math.sin(x), cy = Math.cos(y), sy = Math.sin(y), cz = Math.cos(z), sz = Math.sin(z);
        // Rx . Ry . Rz: z applied first.
        double[][] m = {
                {cy * cz, -cy * sz, sy},
                {cx * sz + sx * sy * cz, cx * cz - sx * sy * sz, -sx * cy},
                {sx * sz - cx * sy * cz, sx * cz + cx * sy * sz, cx * cy},
        };
        double[] o = originOf(part);
        return p -> {
            double[] d = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) {
                out[i] = m[i][0] * d[0] + m[i][1] * d[1] + m[i][2] * d[2] + o[i];
            }
            return out;
        };
    }

    private static double[] originOf(Part part) {
        if (part.origin() != null) {
            return part.origin();
        }
        return new double[]{(part.from()[0] + part.to()[0]) / 2, (part.from()[1] + part.to()[1]) / 2,
                (part.from()[2] + part.to()[2]) / 2};
    }

    private static List<Face> facesOf(int idx, Part part) {
        Turn turn = turnOf(part);
        double[] o = originOf(part);
        double[] f = part.from();
        double[] t = part.to();
        List<Face> out = new ArrayList<>();
        for (Map.Entry<String, int[]> dir : DIRS.entrySet()) {
            if (!part.faces().contains(dir.getKey())) {
                continue;
            }
            int ax = dir.getValue()[0];
            int side = dir.getValue()[1];
            int u = (ax + 1) % 3;
            int v = (ax + 2) % 3;
            double[] p = new double[3];
            p[ax] = side > 0 ? t[ax] : f[ax];
            double[][] corners = {{f[u], f[v]}, {t[u], f[v]}, {t[u], t[v]}, {f[u], t[v]}};
            double[][] poly = new double[4][];
            for (int i = 0; i < 4; i++) {
                double[] q = p.clone();
                q[u] = corners[i][0];
                q[v] = corners[i][1];
                poly[i] = turn.apply(q);
            }
            double[] tip = o.clone();
            tip[ax] += side;
            double[] turnedTip = turn.apply(tip);
            double[] turnedO = turn.apply(o);
            double[] n = {turnedTip[0] - turnedO[0], turnedTip[1] - turnedO[1], turnedTip[2] - turnedO[2]};
            out.add(new Face(idx, ax, side, n, dot(n, poly[0]), poly, (t[u] - f[u]) * (t[v] - f[v])));
        }
        return out;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[][] basis(double[] n) {
        double[] u0 = cross(n, Math.abs(n[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0});
        double l = Math.sqrt(u0[0] * u0[0] + u0[1] * u0[1] + u0[2] * u0[2]);
        double[] u = {u0[0] / l, u0[1] / l, u0[2] / l};
        return new double[][]{u, cross(n, u)};
    }

    private static double signedArea(List<double[]> poly) {
        double s = 0;
        for (int i = 0; i < poly.size(); i++) {
            double[] a = poly.get(i);
            double[] b = poly.get((i + 1) % poly.size());
            s += a[0] * b[1] - b[0] * a[1];
        }
        return s / 2;
    }

    private static List<double[]> ccw(List<double[]> poly) {
        if (signedArea(poly) >= 0) {
            return poly;
        }
        List<double[]> reversed = new ArrayList<>(poly);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    /** Sutherland-Hodgman, convex clipper. */
    private static List<double[]> clip(List<double[]> subject, List<double[]> clipper) {
        List<double[]> out = subject;
        for (int i = 0; i < clipper.size() && !out.isEmpty(); i++) {
            double[] a = clipper.get(i);
            double[] b = clipper.get((i + 1) % clipper.size());
            List<double[]> input = out;
            out = new ArrayList<>();
            double[] prev = input.get(input.size() - 1);
            for (double[] e : input) {
                if (inside(a, b, e)) {
                    if (!inside(a, b, prev)) {
                        out.add(intersect(a, b, prev, e));
                    }
                    out.add(e);
                } else if (inside(a, b, prev)) {
                    out.add(intersect(a, b, prev, e));
                }
                prev = e;
            }
        }
        return out;
    }

    private static boolean inside(double[] a, double[] b, double[] p) {
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= -1e-9;
    }

    private static double[] intersect(double[] a, double[] b, double[] p, double[] q) {
        double dx1 = q[0] - p[0], dy1 = q[1] - p[1], dx2 = b[0] - a[0], dy2 = b[1] - a[1];
        double den = dx1 * dy2 - dy1 * dx2;
        if (Math.abs(den) < 1e-12) {
            return p;
        }
        double s = ((a[0] - p[0]) * dy2 - (a[1] - p[1]) * dx2) / den;
        return new double[]{p[0] + s * dx1, p[1] + s * dy1};
    }

    private static List<double[]> project(double[][] poly, double[] u, double[] v) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : poly) {
            out.add(new double[]{dot(p, u), dot(p, v)});
        }
        return out;
    }

    /** Every pair of faces of different parts that share a plane, face the same way and overlap. */
    private static List<Face[]> coplanarPairs(List<Part> parts) {
        Map<String, List<Face>> buckets = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            for (Face f : facesOf(i, parts.get(i))) {
                String key = Armor3dShaderEncoder.toFixed(f.n()[0], 3) + "," + Armor3dShaderEncoder.toFixed(f.n()[1], 3)
                        + "," + Armor3dShaderEncoder.toFixed(f.n()[2], 3) + "|" + Math.round(f.off() / EPS_PLANE);
                buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            }
        }
        List<Face[]> pairs = new ArrayList<>();
        for (Map.Entry<String, List<Face>> bucket : buckets.entrySet()) {
            String key = bucket.getKey();
            int bar = key.lastIndexOf('|');
            String nk = key.substring(0, bar);
            long ok = Long.parseLong(key.substring(bar + 1));
            List<Face> group = bucket.getValue();
            // The neighbouring bucket too, so two offsets either side of a
            // rounding boundary still meet.
            List<Face> cand = new ArrayList<>(group);
            cand.addAll(buckets.getOrDefault(nk + "|" + (ok + 1), List.of()));
            for (int a = 0; a < group.size(); a++) {
                for (int b = a + 1; b < cand.size(); b++) {
                    Face fa = cand.get(a);
                    Face fb = cand.get(b);
                    if (fa.idx() == fb.idx() || Math.abs(fa.off() - fb.off()) > EPS_PLANE) {
                        continue;
                    }
                    double[][] uv = basis(fa.n());
                    List<double[]> pa = ccw(project(fa.poly(), uv[0], uv[1]));
                    List<double[]> pb = ccw(project(fb.poly(), uv[0], uv[1]));
                    List<double[]> ov = clip(pa, pb);
                    if (ov.size() >= 3 && Math.abs(signedArea(ov)) >= MIN_AREA) {
                        pairs.add(new Face[]{fa, fb});
                    }
                }
            }
        }
        return pairs;
    }

    /** Gives every flat thickness and moves faces apart until none share a plane, in place. */
    static void separate(List<Part> parts) {
        for (Part p : parts) {
            for (int ax = 0; ax < 3; ax++) {
                if (Math.abs(p.to()[ax] - p.from()[ax]) > FLAT) {
                    continue;
                }
                double mid = (p.from()[ax] + p.to()[ax]) / 2;
                p.from()[ax] = mid - FLAT_THICKNESS / 2;
                p.to()[ax] = mid + FLAT_THICKNESS / 2;
            }
        }

        for (int pass = 0; pass < 3; pass++) {
            List<Face[]> pairs = coplanarPairs(parts);
            if (pairs.isEmpty()) {
                break;
            }
            Map<String, String> parent = new LinkedHashMap<>();
            Map<String, Face> faceOf = new LinkedHashMap<>();
            for (Face[] pair : pairs) {
                for (Face f : pair) {
                    parent.putIfAbsent(key(f), key(f));
                    faceOf.put(key(f), f);
                }
                String ra = find(parent, key(pair[0]));
                String rb = find(parent, key(pair[1]));
                if (!ra.equals(rb)) {
                    parent.put(ra, rb);
                }
            }
            Map<String, List<Face>> groups = new LinkedHashMap<>();
            for (String k : parent.keySet()) {
                groups.computeIfAbsent(find(parent, k), r -> new ArrayList<>()).add(faceOf.get(k));
            }
            for (List<Face> members : groups.values()) {
                // Largest keeps the plane; each smaller one sits a step further
                // out. List.sort is stable, as Studio's sort is.
                members.sort((a, b) -> {
                    int byArea = Double.compare(b.area(), a.area());
                    return byArea != 0 ? byArea : Integer.compare(a.idx(), b.idx());
                });
                for (int i = 1; i < members.size(); i++) {
                    Face f = members.get(i);
                    Part p = parts.get(f.idx());
                    if (f.side() > 0) {
                        p.to()[f.ax()] += i * STEP;
                    } else {
                        p.from()[f.ax()] -= i * STEP;
                    }
                }
            }
        }
    }

    private static String key(Face f) {
        return f.idx() + ":" + f.ax() + ":" + f.side();
    }

    private static String find(Map<String, String> parent, String k) {
        while (!parent.get(k).equals(k)) {
            k = parent.get(k);
        }
        return k;
    }
}
