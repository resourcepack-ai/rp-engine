package ai.resourcepack.engine.core.vfx;

/**
 * The arithmetic a VFX is played and painted with, written so that it gives
 * Studio's answers to the last bit.
 *
 * <p>Studio plays and paints in JavaScript, and a port that is merely close
 * would draw a spark a pixel off or pick a different level on a boundary, with
 * nothing to say so. So: doubles throughout, {@link StrictMath} for every
 * transcendental (it is fdlibm, which is what V8's are ported from), V8's own
 * {@code Math.hypot} (Kahan-summed, which fdlibm's is not), and JavaScript's
 * 32-bit integer operations spelled out. {@code VfxPortTest} holds all of it to
 * frames and pixels Studio produced.
 */
final class VfxMath {

    static final double DEG = Math.PI / 180;

    private VfxMath() {
    }

    // ---- JavaScript's numbers

    /** V8's {@code Math.hypot}: scaled by the largest, Kahan-summed. */
    static double hypot(double... values) {
        double max = 0;
        boolean nan = false;
        double[] abs = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            abs[i] = Math.abs(values[i]);
            if (Double.isNaN(abs[i])) {
                nan = true;
            } else if (abs[i] > max) {
                max = abs[i];
            }
        }
        if (max == Double.POSITIVE_INFINITY) {
            return Double.POSITIVE_INFINITY;
        }
        if (nan) {
            return Double.NaN;
        }
        if (max == 0) {
            return 0;
        }
        double sum = 0, compensation = 0;
        for (double a : abs) {
            double n = a / max;
            double summand = n * n - compensation;
            double preliminary = sum + summand;
            compensation = (preliminary - sum) - summand;
            sum = preliminary;
        }
        return Math.sqrt(sum) * max;
    }

    /** {@code Math.round}: halves go up, as JavaScript's do (and as Java's does since 7). */
    static double round(double x) {
        return Math.round(x);
    }

    static double pow(double x, double y) {
        return StrictMath.pow(x, y);
    }

    /** {@code ((a % n) + n) % n}. */
    static double mod(double a, double n) {
        return ((a % n) + n) % n;
    }

    /** {@code x >>> 0} for a value already held as an int: its unsigned 32 bits, as a double. */
    static double unsigned(int x) {
        return x & 0xffffffffL;
    }

    /** JavaScript's ToInt32 of an integer-valued double. */
    static int int32(double x) {
        return (int) (long) x;
    }

    // ---- quaternions, [x, y, z, w]

    static double[] quatMul(double[] a, double[] b) {
        double ax = a[0], ay = a[1], az = a[2], aw = a[3], bx = b[0], by = b[1], bz = b[2], bw = b[3];
        return new double[] {
                aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw,
                aw * bw - ax * bx - ay * by - az * bz,
        };
    }

    static double[] quatAxis(double[] axis, double angle) {
        double len = hypot(axis[0], axis[1], axis[2]);
        if (len == 0) {
            len = 1;
        }
        double s = StrictMath.sin(angle / 2) / len;
        return new double[] {axis[0] * s, axis[1] * s, axis[2] * s, StrictMath.cos(angle / 2)};
    }

    /** [pitch, yaw, roll] in degrees, turned by yaw, then pitch, then roll. */
    static double[] quatEuler(double[] deg) {
        return quatMul(quatMul(quatAxis(new double[] {0, 1, 0}, deg[1] * DEG), quatAxis(new double[] {1, 0, 0}, deg[0] * DEG)),
                quatAxis(new double[] {0, 0, 1}, deg[2] * DEG));
    }

    static double[] quatRotate(double[] q, double[] v) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double ix = w * v[0] + y * v[2] - z * v[1];
        double iy = w * v[1] + z * v[0] - x * v[2];
        double iz = w * v[2] + x * v[1] - y * v[0];
        double iw = -x * v[0] - y * v[1] - z * v[2];
        return new double[] {
                ix * w + iw * -x + iy * -z - iz * -y,
                iy * w + iw * -y + iz * -x - ix * -z,
                iz * w + iw * -z + ix * -y - iy * -x,
        };
    }

    /** The turn taking +y to {@code dir}. */
    static double[] quatAlong(double[] dir) {
        double len = hypot(dir[0], dir[1], dir[2]);
        if (len < 1e-6) {
            return new double[] {0, 0, 0, 1};
        }
        double[] d = {dir[0] / len, dir[1] / len, dir[2] / len};
        double dot = d[1];
        if (dot > 0.999999) {
            return new double[] {0, 0, 0, 1};
        }
        if (dot < -0.999999) {
            return new double[] {1, 0, 0, 0};
        }
        return quatAxis(new double[] {d[2], 0, -d[0]}, StrictMath.acos(dot));
    }

    // ---- vectors

    static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] mul(double[] a, double k) {
        return new double[] {a[0] * k, a[1] * k, a[2] * k};
    }

    static double length(double[] a) {
        return hypot(a[0], a[1], a[2]);
    }

    // ---- seeded randomness

    /** Studio's mulberry32. */
    static final class Mulberry {
        private int a;

        Mulberry(double seed) {
            this.a = int32(seed);
        }

        double next() {
            a = a + 0x6d2b79f5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
            return unsigned(t ^ (t >>> 14)) / 4294967296.0;
        }
    }

    /** A draw that is the same for the same three numbers, for per-tick choices. */
    static double hash3(int a, int b, int c) {
        int h = int32(a + 0x9e3779b9L) * 0x85ebca6b ^ (b + 0x7f4a7c15) * 0xc2b2ae35 ^ (c + 0x165667b1) * 0x27d4eb2f;
        h = (h ^ (h >>> 15)) * 0x2c1b3c6d;
        h = (h ^ (h >>> 12)) * 0x297a2d39;
        return unsigned(h ^ (h >>> 15)) / 4294967296.0;
    }

    static double[] randomDir(Mulberry r) {
        double z = r.next() * 2 - 1, a = r.next() * Math.PI * 2, s = Math.sqrt(1 - z * z);
        return new double[] {s * StrictMath.cos(a), z, s * StrictMath.sin(a)};
    }
}
