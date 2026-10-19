package ai.resourcepack.engine.core.vfx;

import static ai.resourcepack.engine.core.vfx.VfxMath.hypot;
import static ai.resourcepack.engine.core.vfx.VfxMath.mod;
import static ai.resourcepack.engine.core.vfx.VfxMath.pow;
import static ai.resourcepack.engine.core.vfx.VfxMath.round;
import static ai.resourcepack.engine.core.vfx.VfxMath.unsigned;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paints VFX sprites: a port of Studio's procedural painter
 * ({@code lib/vfx/sprites.ts}). Every sprite is drawn from a formula in a
 * palette ramp, so a content folder's effect needs no image files and a
 * palette of its own costs nothing to ship. Held to Studio's pixels, hash for
 * hash, by {@code VfxPortTest}.
 */
final class VfxPainter {

    private static final int BANDS = 9;

    private VfxPainter() {
    }

    // ---- palettes. Stops: [intensity, r, g, b, a].

    /** A ramp of the author's own: dark edge, body, hot core, with white added at the top. */
    static double[][] customStops(String deep, String mid, String bright) {
        int[] d = rgb(deep), m = rgb(mid), b = rgb(bright);
        double[] dark = {round(d[0] * 0.6), round(d[1] * 0.6), round(d[2] * 0.6)};
        double[] white = mix(b, new int[] {255, 255, 255}, 0.85);
        double[] dm = mix(d, m, 0.65);
        return new double[][] {
                {0, dark[0], dark[1], dark[2], 0},
                {0.12, d[0], d[1], d[2], 150},
                {0.3, dm[0], dm[1], dm[2], 215},
                {0.55, m[0], m[1], m[2], 248},
                {0.8, b[0], b[1], b[2], 255},
                {1, white[0], white[1], white[2], 255},
        };
    }

    private static double[] mix(int[] x, int[] y, double f) {
        return new double[] {round(x[0] + (y[0] - x[0]) * f), round(x[1] + (y[1] - x[1]) * f), round(x[2] + (y[2] - x[2]) * f)};
    }

    private static int[] rgb(String hex) {
        int n = Integer.parseInt(hex.substring(1), 16);
        return new int[] {(n >> 16) & 255, (n >> 8) & 255, n & 255};
    }

    /** A palette's identity in a pack: a preset's name, or a short hash of a custom ramp's colours. */
    static String paletteId(String name, Map<String, VfxRecipe.Palette> custom) {
        VfxData data = VfxData.get();
        if (name != null && data.isPresetPalette(name)) {
            return name;
        }
        VfxRecipe.Palette p = name != null && custom != null ? custom.get(name) : null;
        if (p == null) {
            return "sun";
        }
        String key = p.deep + p.mid + p.bright;
        int h = (int) 2166136261L;
        for (int i = 0; i < key.length(); i++) {
            h = (h ^ key.charAt(i)) * 16777619;
        }
        return "c" + Long.toString(h & 0xffffffffL, 36);
    }

    static double[][] paletteStops(String name, Map<String, VfxRecipe.Palette> custom) {
        VfxData data = VfxData.get();
        if (name != null && data.isPresetPalette(name)) {
            return data.presetStops(name);
        }
        VfxRecipe.Palette p = name != null && custom != null ? custom.get(name) : null;
        return p != null ? customStops(p.deep, p.mid, p.bright) : data.presetStops("sun");
    }

    /** The sprite's id in the pack: {@code glow_sun}, {@code slash_c1x2y3z}, {@code ice}. */
    static String spriteId(String sprite, String paletteId) {
        return VfxData.get().spriteOf(sprite).colour() && paletteId != null ? sprite + "_" + paletteId : sprite;
    }

    static int[] ramp(double[][] stops, double t) {
        if (t <= 0.035) {
            return new int[] {0, 0, 0, 0};
        }
        t = Math.min(1, round(Math.min(1, t) * BANDS) / BANDS);
        for (int i = 1; i < stops.length; i++) {
            if (t <= stops[i][0]) {
                double[] a = stops[i - 1], b = stops[i];
                double f = (t - a[0]) / (b[0] - a[0]);
                return new int[] {
                        (int) round(a[1] + (b[1] - a[1]) * f),
                        (int) round(a[2] + (b[2] - a[2]) * f),
                        (int) round(a[3] + (b[3] - a[3]) * f),
                        (int) round(a[4] + (b[4] - a[4]) * f),
                };
            }
        }
        double[] last = stops[stops.length - 1];
        return new int[] {(int) last[1], (int) last[2], (int) last[3], (int) last[4]};
    }

    /** A ramp whose dim end fades out instead of darkening: what a light sprite is painted in. */
    static double[][] lightStops(double[][] stops) {
        double[][] out = new double[stops.length][];
        for (int i = 0; i < stops.length; i++) {
            double[] s = stops[i];
            double t = s[0], a = s[4];
            out[i] = new double[] {t, s[1], s[2], s[3], t < 0.2 ? round(a * 0.2) : t < 0.4 ? round(a * 0.55) : a};
        }
        return out;
    }

    // ---- noise

    private static double hash(double x, double y, double s) {
        int h = (int) (long) (x * 374761393 + y * 668265263 + s * 2147483647);
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return unsigned(h) / 4294967296.0;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double vnoise(double x, double y, double p, double s) {
        double xi = Math.floor(x), yi = Math.floor(y), xf = smooth(x - xi), yf = smooth(y - yi);
        double a = hash(w(xi, p), w(yi, p), s), b = hash(w(xi + 1, p), w(yi, p), s);
        double c = hash(w(xi, p), w(yi + 1, p), s), d = hash(w(xi + 1, p), w(yi + 1, p), s);
        return a + (b - a) * xf + (c - a) * yf + (a - b - c + d) * xf * yf;
    }

    private static double w(double i, double p) {
        return p != 0 ? mod(i, p) : i;
    }

    private static double fbm(double x, double y, double p, double s) {
        double v = 0, amp = 0.5, tot = 0;
        for (int o = 0; o < 4; o++) {
            v += amp * vnoise(x, y, p, s + o * 17);
            tot += amp;
            x *= 2;
            y *= 2;
            if (p != 0) {
                p *= 2;
            }
            amp *= 0.5;
        }
        return v / tot;
    }

    private static double[] worley(double u, double v, double n, double s) {
        double x = u * n, y = v * n, xi = Math.floor(x), yi = Math.floor(y);
        double f1 = 9, f2 = 9;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                double cx = xi + dx, cy = yi + dy;
                double px = cx + 0.15 + 0.7 * hash(mod(cx, n), mod(cy, n), s);
                double py = cy + 0.15 + 0.7 * hash(mod(cy, n), mod(cx, n), s + 7);
                double d = hypot(px - x, py - y);
                if (d < f1) {
                    f2 = f1;
                    f1 = d;
                } else if (d < f2) {
                    f2 = d;
                }
            }
        }
        return new double[] {f1, f2};
    }

    private static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay, wx = px - ax, wy = py - ay;
        double den = vx * vx + vy * vy;
        double t = Math.max(0, Math.min(1, (wx * vx + wy * vy) / (den == 0 ? 1 : den)));
        return hypot(px - (ax + vx * t), py - (ay + vy * t));
    }

    private static double polyDist(double px, double py, double[][] pts) {
        double d = 1e9;
        for (int i = 1; i < pts.length; i++) {
            d = Math.min(d, segDist(px, py, pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]));
        }
        return d;
    }

    /** Studio's {@code rng}: its state is a double in JavaScript, but only its 32 bits are ever read. */
    private static final class Rng {
        private int st;

        Rng(double seed) {
            int s = (int) (long) seed;
            st = s == 0 ? 1 : s;
        }

        double next() {
            st = (st ^ (st >>> 15)) * (int) 2246822519L + (int) 0x9e3779b9L;
            return unsigned(st) / 4294967296.0;
        }
    }

    private record Path(double[][] pts, boolean main) {
    }

    private static final Map<Integer, List<Path>> BOLTS = new HashMap<>();
    private static double[][][] cracks;

    private static synchronized List<Path> bolts(int seed) {
        List<Path> hit = BOLTS.get(seed);
        if (hit != null) {
            return hit;
        }
        Rng r = new Rng((double) seed * 7919 + 13);
        List<Path> out = new ArrayList<>();
        List<double[]> main = new ArrayList<>();
        main.add(new double[] {0.5, 0});
        double x = 0.5;
        for (int i = 1; i <= 16; i++) {
            x = Math.max(0.15, Math.min(0.85, x + (r.next() - 0.5) * 0.22));
            main.add(new double[] {x, i / 16.0});
        }
        out.add(new Path(main.toArray(new double[0][]), true));
        for (int b = 0; b < 3; b++) {
            double[] at = main.get(2 + (int) Math.floor(r.next() * 11));
            double dir = r.next() < 0.5 ? -1 : 1;
            List<double[]> br = new ArrayList<>();
            br.add(at);
            double bx = at[0], by = at[1];
            for (int i = 0; i < 5; i++) {
                bx += dir * (0.03 + r.next() * 0.06);
                by += 0.03 + r.next() * 0.03;
                br.add(new double[] {Math.max(0.02, Math.min(0.98, bx)), by});
            }
            out.add(new Path(br.toArray(new double[0][]), false));
        }
        BOLTS.put(seed, out);
        return out;
    }

    private static synchronized double[][][] cracks() {
        if (cracks != null) {
            return cracks;
        }
        Rng r = new Rng(4242);
        List<double[][]> out = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            double ang = (i / 11.0) * Math.PI * 2 + r.next() * 0.4, rad = 5 + r.next() * 3;
            List<double[]> pts = new ArrayList<>();
            pts.add(new double[] {32 + StrictMath.cos(ang) * rad, 32 + StrictMath.sin(ang) * rad});
            double len = 18 + r.next() * 12;
            while (rad < len) {
                rad += 3 + r.next() * 2;
                ang += (r.next() - 0.5) * 0.5;
                pts.add(new double[] {32 + StrictMath.cos(ang) * rad, 32 + StrictMath.sin(ang) * rad});
            }
            out.add(pts.toArray(new double[0][]));
            if (r.next() < 0.6) {
                double[] at = pts.get(1 + (int) Math.floor(r.next() * (pts.size() - 2)));
                double ba = ang + (r.next() < 0.5 ? 0.7 : -0.7);
                out.add(new double[][] {at, {at[0] + StrictMath.cos(ba) * 7, at[1] + StrictMath.sin(ba) * 7}});
            }
        }
        cracks = out.toArray(new double[0][][]);
        return cracks;
    }

    // ---- the painters: an intensity run through the palette, or RGBA

    private static final double PI = Math.PI;

    /** A painter's answer: either {@code rgba} is set, or {@code value} is an intensity. */
    private static final class Pixel {
        double value;
        int[] rgba;
    }

    private static void paint(String sprite, double u, double v, double a, int k, double[][] p, int f, Pixel out) {
        out.rgba = null;
        out.value = 0;
        switch (sprite) {
            case "glow": {
                double d = hypot(u * 2 - 1, v * 2 - 1);
                out.value = pow(Math.max(0, 1 - d), 2) * 1.25 * pow(1 - a, 1.2);
                return;
            }
            case "flare": {
                double x = u * 2 - 1, y = v * 2 - 1, d = hypot(x, y);
                double core = pow(Math.max(0, 1 - d * 2.4), 2) * 1.3;
                double h = StrictMath.exp(-Math.abs(y) * 26) * pow(Math.max(0, 1 - Math.abs(x)), 1.6);
                double vv = StrictMath.exp(-Math.abs(x) * 26) * pow(Math.max(0, 1 - Math.abs(y)), 1.6) * 0.75;
                double ring = StrictMath.exp(-pow((d - 0.55) / 0.03, 2)) * 0.35;
                out.value = (core + h + vv + ring) * (1 - a);
                return;
            }
            case "streak": {
                double across = StrictMath.exp(-pow((u * 2 - 1) * 3.2, 2));
                double along = v < 0.08 ? v / 0.08 : pow(1 - (v - 0.08) / 0.92, 1.6);
                out.value = across * along * 1.3 * (1 - a);
                return;
            }
            case "spike": {
                double hw = 0.95 * v + 0.02, x = Math.abs(u * 2 - 1);
                if (x > hw) {
                    return;
                }
                out.value = pow(1 - x / hw, 0.7) * (0.55 + 0.7 * (1 - v)) * Math.min(1, (1 - v) * 6) * (1 - a);
                return;
            }
            case "shock": {
                double d = hypot(u * 2 - 1, v * 2 - 1), edge = 0.93;
                if (d > edge) {
                    return;
                }
                double wd = 0.2 * (1 - a) + 0.035;
                double i = StrictMath.exp(-(edge - d) / wd) * 1.25 * pow(1 - a, 0.6);
                if (a > 0.25 && fbm(u * 6, v * 6, 6, 3) < (a - 0.25) * 0.9) {
                    i *= 0.25;
                }
                out.value = i;
                return;
            }
            case "hex": {
                double x = Math.abs(u * 2 - 1), y = Math.abs(v * 2 - 1);
                double h = Math.max(x * 0.866 + y * 0.5, y);
                double ang = StrictMath.atan2(v * 2 - 1, u * 2 - 1);
                double r1 = StrictMath.exp(-pow((h - 0.84) / 0.035, 2)) * 1.15;
                double gap = mod((ang / (PI * 2)) * 6 + 0.25, 1) < 0.72 ? 1 : 0;
                double r2 = StrictMath.exp(-pow((h - 0.68) / 0.022, 2)) * 0.75 * gap;
                double fill = h < 0.84 ? 0.14 * pow(h / 0.84, 3) : 0;
                out.value = (r1 + r2 + fill) * pow(1 - a, 0.8);
                return;
            }
            case "rune": {
                double x = u * 2 - 1, y = v * 2 - 1, d = hypot(x, y), ang = StrictMath.atan2(y, x);
                double i = StrictMath.exp(-pow((d - 0.94) / 0.025, 2)) + StrictMath.exp(-pow((d - 0.78) / 0.02, 2)) * 0.9
                        + StrictMath.exp(-pow((d - 0.46) / 0.02, 2)) * 0.8;
                if (d > 0.81 && d < 0.91) {
                    double cell = Math.floor(mod(ang / (PI * 2), 1) * 28), row = Math.floor((d - 0.81) / 0.034);
                    if (hash(cell, row, 5) > 0.45 && mod((ang / (PI * 2)) * 28, 1) < 0.7) {
                        i += 0.75;
                    }
                }
                if (d < 0.78) {
                    for (double off : new double[] {0, PI}) {
                        for (int n = 0; n < 3; n++) {
                            double a1 = off + n * ((PI * 2) / 3) - PI / 2, a2 = a1 + (PI * 2) / 3;
                            double ld = segDist(x, y, StrictMath.cos(a1) * 0.76, StrictMath.sin(a1) * 0.76,
                                    StrictMath.cos(a2) * 0.76, StrictMath.sin(a2) * 0.76);
                            i += StrictMath.exp(-pow(ld / 0.022, 2)) * 0.8;
                        }
                    }
                }
                i += pow(Math.max(0, 1 - d / 0.3), 2) * 0.4;
                out.value = i * pow(1 - a, 0.8);
                return;
            }
            case "slash": {
                double x = u * 2 - 1, y = v * 2 - 1, r = hypot(x, y);
                double span = PI * 1.55, start = PI * 0.55;
                double sv = mod(StrictMath.atan2(y, x) - start, PI * 2) / span;
                if (sv > 1) {
                    return;
                }
                double reveal = Math.min(1, a / 0.22);
                if (sv < 1 - reveal) {
                    return;
                }
                double big = 0.96 - (1 - sv) * 0.1;
                double t = 0.58 * pow(StrictMath.sin(PI * Math.min(1, sv * 1.08)), 0.75) * (0.25 + 0.75 * sv);
                double inner = big - t;
                if (r > big || r < inner || t <= 0) {
                    return;
                }
                double q = (r - inner) / t, dis = Math.max(0, (a - 0.32) / 0.68);
                if (q < dis * 0.7) {
                    return;
                }
                if (fbm(u * 9, v * 9, 0, 8) < dis * 0.8 - 0.05) {
                    return;
                }
                double streaks = 0.65 + 0.55 * vnoise(sv * 26, q * 2.5, 0, 4);
                out.value = (0.08 + pow(q, 1.7)) * (0.3 + 0.95 * sv) * streaks * 1.3 * (1 - dis * 0.45);
                return;
            }
            case "bolt": {
                double wd = 32, ht = 128, px = u * wd, py = v * ht;
                double d = 1e9;
                for (Path path : bolts(k)) {
                    double[][] scaled = new double[path.pts().length][];
                    for (int i = 0; i < scaled.length; i++) {
                        scaled[i] = new double[] {path.pts()[i][0] * wd, path.pts()[i][1] * ht};
                    }
                    d = Math.min(d, polyDist(px, py, scaled) / (path.main() ? 1 : 0.6));
                }
                out.value = StrictMath.exp(-pow(d / 1.3, 2)) * 1.4 + StrictMath.exp(-d / 3.5) * 0.45;
                return;
            }
            case "pillar": {
                double vv = mod(v + f / 16.0, 1), uu = u;
                double[] wo = worley(uu, vv, 5, 33);
                double e = wo[1] - wo[0];
                double vein = StrictMath.exp(-pow(e / 0.14, 2));
                double shimmer = fbm(mod(uu - f / 16.0, 1) * 4, vv * 4, 4, 2);
                double base = 0.16 + 0.2 * shimmer * shimmer + (1 - wo[0]) * 0.08;
                out.value = (base + vein * 0.95) * new double[] {1, 0.78, 0.55, 0.3}[k];
                return;
            }
            case "crack": {
                double s = 64, px = u * s, py = v * s;
                double d = 1e9;
                for (double[][] path : cracks()) {
                    d = Math.min(d, polyDist(px, py, path));
                }
                double r = hypot(u * 2 - 1, v * 2 - 1);
                double i = StrictMath.exp(-pow(d / 0.9, 2)) * 1.3 + StrictMath.exp(-d / 2.5) * 0.4 + (r < 0.3 ? 0.16 : 0);
                out.value = i * pow(1 - a, 0.9);
                return;
            }
            case "swirl": {
                double x = u * 2 - 1, y = v * 2 - 1, r = hypot(x, y);
                if (r > 1) {
                    return;
                }
                double arm = StrictMath.cos(3 * (StrictMath.atan2(y, x) + 3.2 * StrictMath.log(r + 0.08)));
                double sw = pow(Math.max(0, (arm - 0.15) / 0.85), 1.5);
                out.value = (sw * pow(1 - r, 0.7) * Math.min(1, r * 3.5) * 1.35
                        + StrictMath.exp(-pow((r - 0.33) / 0.05, 2)) * 0.5) * (1 - a);
                return;
            }
            case "core": {
                double d = hypot(u * 2 - 1, v * 2 - 1);
                if (d < 0.56) {
                    out.rgba = new int[] {6, 0, 12, 255};
                } else if (d < 0.7) {
                    out.rgba = ramp(p, 1 - Math.abs(d - 0.6) * 5);
                } else {
                    out.rgba = ramp(p, Math.max(0, 1 - d) * 1.2);
                }
                return;
            }
            case "ice": {
                int x = (int) Math.floor(u * 32), y = (int) Math.floor(v * 32);
                int e = Math.min(Math.min(x, y), Math.min(31 - x, 31 - y));
                int[] c;
                if (e < 2) {
                    c = new int[] {228, 252, 255, 240};
                } else if (e < 4) {
                    c = new int[] {110, 210, 255, 205};
                } else if (e == 8 || e == 9) {
                    c = new int[] {175, 238, 255, 225};
                } else if (e > 11) {
                    c = new int[] {80, 195, 250, 175};
                } else {
                    c = new int[] {30, 150, 230, 150 + (int) Math.floor(hash(x, y, 3) * 25)};
                }
                if (x - y > 18 && e < 6) {
                    c = new int[] {255, 255, 255, 250};
                }
                out.rgba = new int[] {c[0], c[1], c[2], (int) round(c[3] * (1 - a))};
                return;
            }
            case "rock": {
                int x = (int) Math.floor(u * 16), y = (int) Math.floor(v * 16);
                double n = hash(x, y, 11);
                int[] shade = n < 0.33 ? new int[] {150, 46, 72} : n < 0.7 ? new int[] {185, 64, 86} : new int[] {215, 92, 104};
                double[] wo = worley(u, v, 3, 5);
                double heat = (1 - a) * StrictMath.exp(-pow((wo[1] - wo[0]) / 0.09, 2));
                if (heat > 0.12) {
                    int[] c = ramp(VfxData.get().presetStops("sun"), 0.35 + heat * 0.65);
                    out.rgba = new int[] {c[0], c[1], c[2], 255};
                } else {
                    out.rgba = new int[] {shade[0], shade[1], shade[2], 255};
                }
                return;
            }
            case "orb": {
                double n = fbm(u * 4, v * 4, 4, 9);
                int[] c = ramp(p, 0.5 + n * 0.55);
                out.rgba = new int[] {c[0], c[1], c[2], 255};
                return;
            }
            case "beam":
                out.value = StrictMath.exp(-pow((u * 2 - 1) * 3, 2)) * (1.15 + 0.15 * vnoise(v * 8, 0, 8, 2)) * (1 - a);
                return;
            case "blade": {
                double x = Math.abs(u * 2 - 1);
                double hw, i;
                if (v < 0.72) {
                    hw = v < 0.14 ? (v / 0.14) * 0.36 + 0.02 : 0.38;
                    if (x > hw) {
                        return;
                    }
                    i = x < 0.07 ? 1.1 : 0.45 + 0.6 * pow(x / hw, 3);
                } else if (v < 0.79) {
                    hw = 0.95;
                    if (x > hw) {
                        return;
                    }
                    i = 0.95;
                } else if (v < 0.95) {
                    hw = 0.16;
                    if (x > hw) {
                        return;
                    }
                    i = 0.4 + (Math.floor(v * 64) % 2) * 0.15;
                } else {
                    hw = 0.26;
                    if (x > hw) {
                        return;
                    }
                    i = 0.9;
                }
                out.value = i * (1 - a);
                return;
            }
            case "petal": {
                double sv = 1 - v, x = Math.abs(u * 2 - 1);
                double hw = 0.82 * StrictMath.sin(PI * Math.min(1, pow(sv, 0.75))) * (1 - sv * 0.25);
                if (x > hw || hw <= 0) {
                    return;
                }
                double vein = StrictMath.exp(-pow(x / 0.06, 2)) * 0.5;
                out.value = (0.35 + 0.6 * pow(x / hw, 2) + vein) * (0.6 + 0.5 * sv) * (1 - a);
                return;
            }
            case "wing": {
                double dx = u, dy = v - 0.3, r = hypot(dx, dy), ang = StrictMath.atan2(dy, dx);
                if (ang < -0.35 || ang > 1.25) {
                    return;
                }
                double fr = (ang + 0.35) / 1.6, n = 8, idx = Math.floor(fr * n), within = fr * n - idx;
                double len = 1.02 - idx * 0.075 + (hash(idx, 0, 4) - 0.5) * 0.06;
                if (r > len) {
                    return;
                }
                double edge = Math.abs(within - 0.5) * 2;
                double tip = Math.max(0, (r - (len - 0.2)) / 0.2);
                double lead = 1 - fr;
                out.value = ((1 - pow(edge, 3)) * 0.75 + 0.3) * (0.45 + 0.55 * r) * (0.55 + 0.6 * lead) * (1 - tip * 0.6) * (1 - a);
                return;
            }
            case "hextile": {
                double x = Math.abs(u * 2 - 1), y = Math.abs(v * 2 - 1);
                double h = Math.max(x * 0.5 + y * 0.866, x);
                if (h > 0.96) {
                    return;
                }
                out.value = (StrictMath.exp(-(0.96 - h) / 0.09) * 1.2 + 0.2 + 0.25 * pow(h, 6)) * pow(1 - a, 0.9);
                return;
            }
            case "rift": {
                double x = u * 2 - 1, y = v * 2 - 1;
                double row = Math.floor(v * 64);
                double hw = 0.4 * (1 - pow(Math.abs(y), 1.7)) * (0.7 + 0.55 * vnoise(row * 0.7, 0, 0, 9));
                double d = hw > 0.005 ? Math.abs(x) / hw : 99;
                if (d < 0.78) {
                    double st = hash(Math.floor(u * 64), row, 12);
                    if (st > 0.975) {
                        out.rgba = new int[] {255, 245, 255, 255};
                        return;
                    }
                    double neb = fbm(u * 5, v * 5, 0, 13);
                    int[] c = ramp(p, neb * 0.35);
                    out.rgba = new int[] {(int) round(6 + c[0] * 0.25), (int) round(c[1] * 0.2), (int) round(14 + c[2] * 0.3), 255};
                } else if (d < 1) {
                    out.rgba = ramp(p, 1 - (d - 0.78) * 1.4);
                } else {
                    out.rgba = ramp(p, StrictMath.exp(-(d - 1) * 2.2) * 0.65);
                }
                return;
            }
            case "eyewhite": {
                double x = u * 2 - 1, y = v * 2 - 1, hh = 0.62 * (1 - x * x);
                if (Math.abs(y) > hh) {
                    out.rgba = new int[] {0, 0, 0, 0};
                } else if (Math.abs(y) > hh - 0.1) {
                    out.rgba = new int[] {40, 0, 50, 255};
                } else {
                    double vein = StrictMath.exp(-pow(Math.abs(y - StrictMath.sin(x * 9) * 0.15) / 0.03, 2)) * (Math.abs(x) > 0.5 ? 1 : 0);
                    out.rgba = vein > 0.5 ? new int[] {200, 40, 60, 255} : new int[] {236, 226, 240, 255};
                }
                return;
            }
            case "iris": {
                double x = u * 2 - 1, y = v * 2 - 1, r = hypot(x, y);
                if (r > 0.95) {
                    out.rgba = new int[] {0, 0, 0, 0};
                } else if (Math.abs(x) < 0.13 * (1 - y * y) + 0.02) {
                    out.rgba = new int[] {5, 0, 5, 255};
                } else {
                    double rays = 0.85 + 0.15 * StrictMath.cos(StrictMath.atan2(y, x) * 22);
                    int[] c = ramp(p, (0.35 + 0.65 * (1 - r)) * rays + (r > 0.85 ? -0.3 : 0));
                    out.rgba = new int[] {c[0], c[1], c[2], 255};
                }
                return;
            }
            default:
                throw new IllegalArgumentException("No painter for VFX sprite " + sprite);
        }
    }

    /** The level as 0..1, which is what a painter is handed. */
    static double levelAmount(VfxData.Sprite info, int k) {
        if (info.levels() == 1) {
            return 0;
        }
        return info.age() ? k / (double) (info.levels() - 1) : k / (double) info.levels();
    }

    /**
     * One level of one sprite in one palette: RGBA, {@code w} wide and
     * {@code h * frames} tall (an animated sprite's frames stacked, as a
     * .png.mcmeta animation wants).
     */
    static byte[] paint(String sprite, double[][] ramped, int k) {
        VfxData.Sprite info = VfxData.get().spriteOf(sprite);
        double[][] stops = info.light() ? lightStops(ramped) : ramped;
        int wd = info.w(), ht = info.h(), frames = info.frames();
        double a = levelAmount(info, k);
        byte[] rgba = new byte[wd * ht * frames * 4];
        Pixel px = new Pixel();
        for (int f = 0; f < frames; f++) {
            for (int y = 0; y < ht; y++) {
                for (int x = 0; x < wd; x++) {
                    paint(sprite, (x + 0.5) / wd, (y + 0.5) / ht, a, k, stops, f, px);
                    int[] c = px.rgba != null ? px.rgba : ramp(stops, px.value);
                    int at = ((f * ht + y) * wd + x) * 4;
                    for (int i = 0; i < 4; i++) {
                        rgba[at + i] = (byte) Math.max(0, Math.min(255, c[i]));
                    }
                }
            }
        }
        return rgba;
    }
}
