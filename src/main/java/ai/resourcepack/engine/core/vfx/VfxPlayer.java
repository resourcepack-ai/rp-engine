package ai.resourcepack.engine.core.vfx;

import static ai.resourcepack.engine.core.vfx.VfxMath.DEG;
import static ai.resourcepack.engine.core.vfx.VfxMath.add;
import static ai.resourcepack.engine.core.vfx.VfxMath.hash3;
import static ai.resourcepack.engine.core.vfx.VfxMath.length;
import static ai.resourcepack.engine.core.vfx.VfxMath.mul;
import static ai.resourcepack.engine.core.vfx.VfxMath.quatAlong;
import static ai.resourcepack.engine.core.vfx.VfxMath.quatAxis;
import static ai.resourcepack.engine.core.vfx.VfxMath.quatEuler;
import static ai.resourcepack.engine.core.vfx.VfxMath.quatMul;
import static ai.resourcepack.engine.core.vfx.VfxMath.quatRotate;
import static ai.resourcepack.engine.core.vfx.VfxMath.randomDir;
import static ai.resourcepack.engine.core.vfx.VfxMath.sub;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays a recipe: what is drawn on each tick. A port of Studio's player
 * ({@code lib/vfx/player.ts}), line for line and draw for draw, because Studio's
 * preview, its thumbnails and this have to show the same effect. Deterministic:
 * every random choice is seeded, so a recipe plays the same way every time.
 *
 * <p>A tick's output is a list of instances, each one sprite with a stable id,
 * so whatever draws it can keep one display entity per id and let the client
 * interpolate between ticks.
 */
public final class VfxPlayer {

    /** One sprite on one tick, in the caster's frame. */
    public record Instance(String id, String sprite, String palette, int level, double[] pos, double[] rot,
                           double[] scale, boolean billboard) {
    }

    /** A sound to play this tick, at a point in the caster's frame. */
    public record SoundEvent(String sound, double volume, double pitch, double[] at) {
    }

    public record Frame(int t, List<Instance> instances, List<SoundEvent> sounds) {
    }

    private static final double[] ORIGIN_UP = {0, 1, 0};
    private static final double[] Y = {0, 1, 0};

    private static final class Copy {
        double yaw;
        double[] offset;
        double radius;
        double delay;
        double scale;
    }

    private static final class Mote {
        String id;
        String kind;
        String sprite;
        String palette;
        double[] p;
        double[] v;
        int age;
        int life;
        double w;
        double len;
        double gravity;
        double drag;
        double swirl;
        double[] centre;
        double[] toward;
        double pull;
        double[] axis;
        double spin;
        double angle;
    }

    private static final class Prepared {
        int index;
        VfxRecipe.Layer layer;
        List<Copy> copies;
        VfxMath.Mulberry rng;
        double[] carry;
    }

    private final VfxRecipe recipe;
    private final VfxData data = VfxData.get();
    private final List<Prepared> layers = new ArrayList<>();
    private List<Mote> motes = new ArrayList<>();
    private int moteSerial;
    private int t;

    public VfxPlayer(VfxRecipe recipe) {
        this(recipe, 1);
    }

    public VfxPlayer(VfxRecipe recipe, int seed) {
        this.recipe = recipe;
        for (int index = 0; index < recipe.layers.size(); index++) {
            Prepared p = new Prepared();
            p.index = index;
            p.layer = recipe.layers.get(index);
            p.copies = layoutCopies(p.layer, index);
            p.rng = new VfxMath.Mulberry((double) seed * 31 + (double) index * 104729 + 17);
            p.carry = new double[p.copies.size()];
            layers.add(p);
        }
    }

    /** The next tick {@link #step} will draw. */
    public int tick() {
        return t;
    }

    public boolean done() {
        return t >= recipe.duration;
    }

    // ---- tracks

    private static double ease(String e, double x) {
        x = Math.max(0, Math.min(1, x));
        if (e == null) {
            return x;
        }
        switch (e) {
            case "in":
                return x * x * x;
            case "out":
                return 1 - VfxMath.pow(1 - x, 3);
            case "inout":
                return x < 0.5 ? 4 * x * x * x : 1 - VfxMath.pow(-2 * x + 2, 3) / 2;
            case "back": {
                double c = 1.9;
                return 1 + (c + 1) * VfxMath.pow(x - 1, 3) + c * VfxMath.pow(x - 1, 2);
            }
            case "step":
                return x >= 1 ? 1 : 0;
            default:
                return x;
        }
    }

    private static double[] lerp(double[] a, double[] b, double f) {
        if (a.length == 1 && b.length == 1) {
            return new double[] {a[0] + (b[0] - a[0]) * f};
        }
        double[] va = a.length == 1 ? new double[] {a[0], a[0], a[0]} : a;
        double[] vb = b.length == 1 ? new double[] {b[0], b[0], b[0]} : b;
        return new double[] {va[0] + (vb[0] - va[0]) * f, va[1] + (vb[1] - va[1]) * f, va[2] + (vb[2] - va[2]) * f};
    }

    /** A track's value {@code lt} ticks into its layer. */
    static double[] sample(VfxRecipe.Track track, double lt, double[] fallback) {
        if (track == null) {
            return fallback;
        }
        if (!track.keyed()) {
            return track.constant;
        }
        if (lt <= track.times[0]) {
            return track.values[0];
        }
        for (int i = 1; i < track.times.length; i++) {
            if (lt <= track.times[i]) {
                double span = track.times[i] - track.times[i - 1];
                double f = span <= 0 ? 1 : ease(track.eases[i], (lt - track.times[i - 1]) / span);
                return lerp(track.values[i - 1], track.values[i], f);
            }
        }
        return track.values[track.values.length - 1];
    }

    // ---- copies

    private static List<Copy> layoutCopies(VfxRecipe.Layer layer, int index) {
        VfxRecipe.Copies c = layer.copies;
        List<Copy> out = new ArrayList<>();
        if (c == null) {
            Copy one = new Copy();
            one.offset = new double[] {0, 0, 0};
            one.scale = 1;
            out.add(one);
            return out;
        }
        VfxMath.Mulberry r = new VfxMath.Mulberry(c.seed != null ? c.seed : 1000 + (double) index * 7919);
        boolean full = c.arc != null && Math.abs(c.arc[1] - c.arc[0]) >= 359.999;
        for (int i = 0; i < c.count; i++) {
            // Always five draws a copy, used or not, so layers sharing a seed
            // and a count put their copies in the same places.
            double sx = r.next() * 2 - 1, sy = r.next() * 2 - 1, sz = r.next() * 2 - 1, ry = r.next(), rj = r.next();
            double f = c.count == 1 ? 0 : i / (double) (full ? c.count : c.count - 1);
            double yaw = c.randomYaw ? ry * 360 : c.arc != null ? c.arc[0] + (c.arc[1] - c.arc[0]) * f : 0;
            double[] scatter = c.scatter != null ? c.scatter : new double[] {0, 0, 0};
            double[] step = c.step != null ? c.step : new double[] {0, 0, 0};
            Copy copy = new Copy();
            copy.yaw = yaw;
            copy.offset = new double[] {sx * scatter[0] + step[0] * i, sy * scatter[1] + step[1] * i, sz * scatter[2] + step[2] * i};
            copy.radius = c.radius != null ? c.radius : 0;
            copy.delay = (c.stagger != null ? c.stagger : 0) * i + VfxMath.round(rj * (c.jitter != null ? c.jitter : 0));
            copy.scale = Math.max(0, 1 + (c.scaleStep != null ? c.scaleStep : 0) * i);
            out.add(copy);
        }
        return out;
    }

    private static double[] copyPoint(Copy copy, double[] p) {
        return add(copy.offset, quatRotate(quatAxis(Y, copy.yaw * DEG), new double[] {p[0], p[1], p[2] + copy.radius}));
    }

    private static double[] copyQuat(Copy copy, double[] q) {
        return copy.yaw != 0 ? quatMul(quatAxis(Y, copy.yaw * DEG), q) : q;
    }

    // ---- motes

    private static double[] spawnPoint(VfxRecipe.Layer layer, VfxMath.Mulberry r) {
        double[] at = layer.at != null ? layer.at : ORIGIN_UP;
        double[] s = layer.size != null ? layer.size : new double[] {1, 1, 1};
        switch (layer.shape) {
            case "sphere": {
                double[] d = randomDir(r);
                double k = StrictMath.cbrt(r.next());
                return add(at, new double[] {d[0] * s[0] * k, d[1] * s[1] * k, d[2] * s[2] * k});
            }
            case "ring": {
                double a = r.next() * Math.PI * 2;
                return add(at, new double[] {StrictMath.sin(a) * s[0], 0, StrictMath.cos(a) * s[2]});
            }
            case "disc": {
                double a = r.next() * Math.PI * 2, k = Math.sqrt(r.next());
                return add(at, new double[] {StrictMath.sin(a) * s[0] * k, 0, StrictMath.cos(a) * s[2] * k});
            }
            case "box":
                return add(at, new double[] {(r.next() * 2 - 1) * s[0], (r.next() * 2 - 1) * s[1], (r.next() * 2 - 1) * s[2]});
            default:
                return at;
        }
    }

    private static double[] spawnVelocity(VfxRecipe.Layer layer, double[] from, VfxMath.Mulberry r) {
        double[] speed = layer.speed != null ? layer.speed : new double[] {0.1, 0.3};
        double k = speed[0] + r.next() * (speed[1] - speed[0]);
        double[] any = randomDir(r);
        double[] base;
        if (layer.dir != null) {
            base = layer.dir;
        } else {
            double[] out = sub(from, layer.at != null ? layer.at : ORIGIN_UP);
            base = length(out) > 1e-4 ? out : any;
        }
        double bl = length(base);
        double[] b = mul(base, 1 / (bl == 0 ? 1 : bl));
        double spread = layer.spread;
        double[] d = {b[0] + (any[0] - b[0]) * spread, b[1] + (any[1] - b[1]) * spread, b[2] + (any[2] - b[2]) * spread};
        double dl = length(d);
        return mul(d, k / (dl == 0 ? 1 : dl));
    }

    // ---- the player

    public Frame step() {
        int t = this.t;
        List<Instance> instances = new ArrayList<>();
        List<SoundEvent> sounds = new ArrayList<>();
        if (recipe.sounds != null) {
            for (VfxRecipe.Sound s : recipe.sounds) {
                if (s.t == t) {
                    sounds.add(new SoundEvent(s.sound, s.volume != null ? s.volume : 1, s.pitch != null ? s.pitch : 1,
                            s.at != null ? s.at : ORIGIN_UP));
                }
            }
        }

        for (Prepared prepared : layers) {
            VfxRecipe.Layer layer = prepared.layer;
            int life = layer.end - layer.start;
            for (int ci = 0; ci < prepared.copies.size(); ci++) {
                Copy copy = prepared.copies.get(ci);
                double lt = t - layer.start - copy.delay;
                if (lt < 0 || lt >= life) {
                    continue;
                }
                if (layer.sounds != null) {
                    for (VfxRecipe.Sound s : layer.sounds) {
                        if (s.t != lt) {
                            continue;
                        }
                        double[] local = s.at != null ? s.at
                                : !layer.motes ? sample(layer.pos, lt, new double[] {0, 0, 0})
                                : layer.at != null ? layer.at : ORIGIN_UP;
                        sounds.add(new SoundEvent(s.sound, s.volume != null ? s.volume : 1, s.pitch != null ? s.pitch : 1,
                                copyPoint(copy, local)));
                    }
                }
                if (!layer.motes) {
                    Instance inst = spriteInstance(prepared, layer, copy, ci, lt, t);
                    if (inst != null) {
                        instances.add(inst);
                    }
                } else {
                    emit(prepared, layer, copy, ci, lt, instances.size());
                }
            }
        }

        // Motes, after the layers, so a mote born this tick is drawn this tick.
        List<Mote> kept = new ArrayList<>();
        for (Mote m : motes) {
            if (++m.age >= m.life) {
                continue;
            }
            if (m.toward != null) {
                double[] d = sub(m.toward, m.p);
                double dl = length(d);
                if (dl < 0.5) {
                    continue;
                }
                m.v = add(m.v, mul(d, m.pull / dl));
            }
            m.p = add(m.p, m.v);
            if (m.swirl != 0) {
                double rx = m.p[0] - m.centre[0], rz = m.p[2] - m.centre[2], rl = VfxMath.hypot(rx, rz);
                if (rl > 1e-4) {
                    m.p = new double[] {m.p[0] + (rz / rl) * m.swirl, m.p[1], m.p[2] - (rx / rl) * m.swirl};
                }
            }
            m.v = new double[] {m.v[0] * m.drag, (m.v[1] - m.gravity) * m.drag, m.v[2] * m.drag};
            kept.add(m);
            double f = (double) m.age / m.life;
            int level = data.level(m.sprite, f);
            if ("glow".equals(m.kind)) {
                instances.add(new Instance(m.id, m.sprite, m.palette, level, m.p, new double[] {0, 0, 0, 1},
                        mul(new double[] {1, 1, 1}, m.w * (1 - f * 0.6)), true));
            } else if ("streak".equals(m.kind)) {
                double speed = length(m.v);
                double l = m.len * Math.min(1.6, 0.35 + speed * 2.5);
                instances.add(new Instance(m.id, m.sprite, m.palette, level, m.p, quatAlong(m.v), new double[] {m.w, l, m.w}, false));
            } else {
                m.angle += m.spin;
                instances.add(new Instance(m.id, m.sprite, m.palette, level, m.p, quatAxis(m.axis, m.angle),
                        mul(new double[] {1, 1, 1}, m.w * (1 - f * f)), false));
            }
        }
        motes = kept;
        this.t++;
        return new Frame(t, instances, sounds);
    }

    private Instance spriteInstance(Prepared prepared, VfxRecipe.Layer layer, Copy copy, int ci, double lt, int t) {
        if (layer.flicker != null && layer.flicker != 0 && hash3(prepared.index, ci, t) < layer.flicker) {
            return null;
        }
        double[] local = sample(layer.pos, lt, new double[] {0, 0, 0});
        double[] pos = copyPoint(copy, local);
        double[] euler = sample(layer.rot, lt, new double[] {0, 0, 0});
        double[] spin = layer.spin != null ? layer.spin : new double[] {0, 0, 0};
        // Spin turns it about its OWN axes, before rot places it.
        double[] turn = spin[0] != 0 || spin[1] != 0 || spin[2] != 0
                ? quatMul(quatEuler(euler), quatEuler(new double[] {spin[0] * lt, spin[1] * lt, spin[2] * lt}))
                : quatEuler(euler);
        if (layer.faceMotion) {
            double[] ahead = sample(layer.pos, lt + 1, local), behind = sample(layer.pos, lt - 1, local);
            double[] vel = sub(ahead, behind);
            if (length(vel) > 1e-5) {
                turn = quatMul(quatAlong(vel), turn);
            }
        }
        double[] rot = copyQuat(copy, turn);
        double[] raw = sample(layer.scale, lt, new double[] {1});
        double[] scale = raw.length == 1
                ? new double[] {raw[0] * copy.scale, raw[0] * copy.scale, raw[0] * copy.scale}
                : mul(raw, copy.scale);
        if (scale[0] <= 0.001 && scale[1] <= 0.001 && scale[2] <= 0.001) {
            return null;
        }
        double fade = sample(layer.fade, lt, new double[] {0})[0];
        if (fade >= 1) {
            return null;
        }
        VfxData.Sprite info = data.spriteOf(layer.sprite);
        int level = layer.shuffle
                ? (int) Math.floor(hash3(prepared.index + 77, ci, t) * info.levels())
                : data.level(layer.sprite, fade);
        return new Instance("l" + prepared.index + "c" + ci, layer.sprite,
                info.colour() ? (layer.palette != null ? layer.palette : "sun") : null, level, pos, rot, scale,
                layer.billboard);
    }

    private void emit(Prepared prepared, VfxRecipe.Layer layer, Copy copy, int ci, double lt, int drawn) {
        int n = lt == 0 ? (layer.burst != null ? layer.burst : 0) : 0;
        if (layer.rate != null && layer.rate != 0) {
            prepared.carry[ci] += layer.rate;
            double whole = Math.floor(prepared.carry[ci]);
            prepared.carry[ci] -= whole;
            n += (int) whole;
        }
        VfxMath.Mulberry r = prepared.rng;
        String sprite = VfxRecipe.moteSprite(layer);
        String palette = data.spriteOf(sprite).colour() ? (layer.palette != null ? layer.palette : "sun") : null;
        double[] scale = layer.moteScale != null ? layer.moteScale : new double[] {0.15, 0.35};
        double[] life = layer.life != null ? layer.life : new double[] {10, 20};
        int live = data.limits().live();
        for (int i = 0; i < n; i++) {
            if (motes.size() + drawn >= live) {
                return;
            }
            double[] local = spawnPoint(layer, r);
            double[] v = spawnVelocity(layer, local, r);
            Mote m = new Mote();
            m.id = "m" + moteSerial++;
            m.kind = layer.kind;
            m.sprite = sprite;
            m.palette = palette;
            m.p = copyPoint(copy, local);
            m.v = quatRotate(quatAxis(Y, copy.yaw * DEG), v);
            m.age = 0;
            m.life = (int) VfxMath.round(life[0] + r.next() * (life[1] - life[0]));
            m.w = scale[0] + r.next() * (scale[1] - scale[0]);
            m.len = layer.length != null ? layer.length : 1.2;
            m.gravity = layer.gravity != null ? layer.gravity : 0;
            m.drag = layer.drag;
            m.swirl = layer.swirl != null ? layer.swirl : 0;
            m.centre = copyPoint(copy, layer.at != null ? layer.at : ORIGIN_UP);
            m.toward = layer.toward != null ? copyPoint(copy, layer.toward) : null;
            m.pull = layer.pull != null ? layer.pull : 0.05;
            m.axis = randomDir(r);
            m.spin = 0.15 + r.next() * 0.35;
            motes.add(m);
        }
    }
}
