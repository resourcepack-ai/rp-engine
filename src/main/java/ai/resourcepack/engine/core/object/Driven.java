package ai.resourcepack.engine.core.object;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Easing;
import ai.resourcepack.engine.api.ShaderObject;
import ai.resourcepack.engine.api.ShaderObjectInfo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * One placed object: what it was told, what it is doing, and the handle the
 * API hands out. See {@link ObjectRuntime}.
 */
public final class Driven implements ShaderObject {

    private final ObjectRuntime runtime;
    private final ItemDisplay display;
    private final ShaderObjectInfo info;

    private int state;
    private int previous;
    /** The game tick the state began, or -1 for long ago. */
    private long start;
    private int seed;
    private double size;
    private final float[] turn = new float[3];

    private Tween move;
    private Tween turning;
    private Tween sizing;

    private ObjectSequence sequence;
    private int step;
    private long waitUntil;
    private Location origin;

    Driven(ObjectRuntime runtime, ItemDisplay display, ShaderObjectInfo info) {
        this.runtime = runtime;
        this.display = display;
        this.info = info;
        PersistentDataContainer data = display.getPersistentDataContainer();
        state = data.getOrDefault(runtime.stateKey, PersistentDataType.INTEGER, 0);
        previous = data.getOrDefault(runtime.previousKey, PersistentDataType.INTEGER, 0);
        start = data.getOrDefault(runtime.startKey, PersistentDataType.LONG, -1L);
        seed = data.getOrDefault(runtime.seedKey, PersistentDataType.INTEGER, 0);
        size = data.getOrDefault(runtime.sizeKey, PersistentDataType.DOUBLE, info.size());
        String[] parts = data.getOrDefault(runtime.turnKey, PersistentDataType.STRING, "0,0,0").split(",");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                turn[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException ignored) {
                turn[i] = 0;
            }
        }
    }

    // ---- what the handle answers ---------------------------------------------

    @Override
    public ContentId id() {
        return info.id();
    }

    @Override
    public ShaderObjectInfo info() {
        return runtime.catalogue().info(info.id()).orElse(info);
    }

    @Override
    public ItemDisplay entity() {
        return display;
    }

    @Override
    public boolean isValid() {
        return display.isValid();
    }

    @Override
    public Location location() {
        return display.getLocation();
    }

    @Override
    public String state() {
        return info.stateName(state);
    }

    @Override
    public boolean state(String name) {
        if (!isValid()) {
            return false;
        }
        Integer value = info.state(name).orElse(null);
        if (value == null) {
            return false;
        }
        if (value != state) {
            previous = state;
            state = value;
            start = gameTime();
            applyItem();
            persist();
        }
        return true;
    }

    @Override
    public void replay() {
        if (!isValid()) {
            return;
        }
        previous = state;
        start = gameTime();
        applyItem();
        persist();
    }

    @Override
    public void settle() {
        if (!isValid() || start < 0) {
            return;
        }
        start = -1;
        applyItem();
        persist();
    }

    @Override
    public int seed() {
        return seed;
    }

    @Override
    public void seed(int value) {
        if (!isValid()) {
            return;
        }
        seed = Math.max(0, Math.min(15, value));
        applyItem();
        persist();
    }

    @Override
    public double size() {
        return size;
    }

    @Override
    public float[] rotation() {
        return turn.clone();
    }

    @Override
    public void moveTo(Location to, int ticks, Easing easing) {
        if (!isValid() || to == null || to.getWorld() == null || !to.getWorld().equals(display.getWorld())) {
            return;
        }
        Location from = display.getLocation();
        move = new Tween(runtime.now(), Math.max(0, ticks), easing,
                new double[]{from.getX(), from.getY(), from.getZ()}, new double[]{to.getX(), to.getY(), to.getZ()});
        if (ticks <= 0) {
            tickMove();
        }
    }

    @Override
    public void moveBy(Vector by, int ticks, Easing easing) {
        if (by == null) {
            return;
        }
        Location target = (move != null ? move.endLocation(display.getWorld()) : display.getLocation()).add(by);
        moveTo(target, ticks, easing);
    }

    @Override
    public void turnTo(float yaw, float pitch, float roll, int ticks, Easing easing) {
        if (!isValid()) {
            return;
        }
        turning = new Tween(runtime.now(), Math.max(0, ticks), easing,
                new double[]{turn[0], turn[1], turn[2]}, new double[]{yaw, pitch, roll});
        if (ticks <= 0) {
            tickTransform();
        }
    }

    @Override
    public void resize(double diameter, int ticks, Easing easing) {
        if (!isValid() || !(diameter > 0)) {
            return;
        }
        sizing = new Tween(runtime.now(), Math.max(0, ticks), easing, new double[]{size}, new double[]{diameter});
        if (ticks <= 0) {
            tickTransform();
        }
    }

    @Override
    public boolean isMoving() {
        return move != null || turning != null || sizing != null;
    }

    @Override
    public boolean play(String name) {
        if (!isValid()) {
            return false;
        }
        ObjectSequence found = runtime.catalogue().sequence(info.id(), name).orElse(null);
        if (found == null) {
            return false;
        }
        sequence = found;
        step = 0;
        waitUntil = runtime.now();
        origin = display.getLocation();
        return true;
    }

    @Override
    public String playing() {
        return sequence == null ? null : sequence.name();
    }

    @Override
    public void stop() {
        sequence = null;
        move = null;
        turning = null;
        sizing = null;
        persist();
    }

    @Override
    public void remove() {
        sequence = null;
        runtime.forget(this);
        display.remove();
    }

    // ---- the tick ------------------------------------------------------------

    void tick() {
        if (move != null) {
            tickMove();
        }
        if (turning != null || sizing != null) {
            tickTransform();
        }
        if (start >= 0 && runtime.now() % 20 == 0 && gameTime() - start > ObjectControls.SETTLE_AFTER) {
            settle();
        }
        runSequence();
    }

    private void tickMove() {
        double[] at = move.sample(runtime.now());
        Location to = new Location(display.getWorld(), at[0], at[1], at[2]);
        display.teleport(to);
        if (move.done(runtime.now())) {
            move = null;
        }
    }

    private void tickTransform() {
        long now = runtime.now();
        if (turning != null) {
            double[] at = turning.sample(now);
            for (int i = 0; i < 3; i++) {
                turn[i] = (float) at[i];
            }
            if (turning.done(now)) {
                turning = null;
                persist();
            }
        }
        if (sizing != null) {
            size = sizing.sample(now)[0];
            if (sizing.done(now)) {
                sizing = null;
                persist();
            }
        }
        // One tick of interpolation per step, and the client fills the frames
        // between: the curve is ours, the smoothing is the game's.
        applyTransform(ObjectRuntime.MOVE_TICKS);
    }

    private void runSequence() {
        int guard = 0;
        while (sequence != null && runtime.now() >= waitUntil && guard++ < 64) {
            if (step >= sequence.steps().size()) {
                sequence = null;
                return;
            }
            ObjectSequence.Step next = sequence.steps().get(step++);
            if (!perform(next)) {
                return;
            }
        }
    }

    /** One step. False when the sequence must not go on this tick. */
    private boolean perform(ObjectSequence.Step step) {
        long now = runtime.now();
        if (step instanceof ObjectSequence.State s) {
            if (s.replay() && info.state(s.state()).orElse(-1) == state) {
                replay();
            } else if (!state(s.state())) {
                runtime.log().warning("3D object " + id() + " has no state " + s.state() + " (sequence " + sequence.name() + ").");
            }
        } else if (step instanceof ObjectSequence.Seed s) {
            seed(s.seed());
        } else if (step instanceof ObjectSequence.Move m) {
            Location to = m.to() != null
                    ? origin.clone().add(m.to()[0], m.to()[1], m.to()[2])
                    : (move != null ? move.endLocation(display.getWorld()) : display.getLocation())
                            .add(m.by()[0], m.by()[1], m.by()[2]);
            moveTo(to, m.ticks(), m.easing());
            if (!m.async()) {
                waitUntil = now + m.ticks();
            }
        } else if (step instanceof ObjectSequence.Turn t) {
            float[] base = turning != null ? turning.endFloats() : turn.clone();
            float yaw = t.yaw() == null ? base[0] : (t.by() ? base[0] + t.yaw() : t.yaw());
            float pitch = t.pitch() == null ? base[1] : (t.by() ? base[1] + t.pitch() : t.pitch());
            float roll = t.roll() == null ? base[2] : (t.by() ? base[2] + t.roll() : t.roll());
            turnTo(yaw, pitch, roll, t.ticks(), t.easing());
            if (!t.async()) {
                waitUntil = now + t.ticks();
            }
        } else if (step instanceof ObjectSequence.Resize r) {
            double base = sizing != null ? sizing.endFloats()[0] : size;
            resize(r.size() != null ? r.size() : base * r.times(), r.ticks(), r.easing());
            if (!r.async()) {
                waitUntil = now + r.ticks();
            }
        } else if (step instanceof ObjectSequence.Wait w) {
            waitUntil = now + w.ticks();
        } else if (step instanceof ObjectSequence.Sound s) {
            if (!s.sound().isEmpty()) {
                display.getWorld().playSound(display.getLocation(), s.sound(), s.volume(), s.pitch());
            }
        } else if (step instanceof ObjectSequence.Command c) {
            Location at = display.getLocation();
            String command = c.command()
                    .replace("{x}", String.format(Locale.ROOT, "%.3f", at.getX()))
                    .replace("{y}", String.format(Locale.ROOT, "%.3f", at.getY()))
                    .replace("{z}", String.format(Locale.ROOT, "%.3f", at.getZ()))
                    .replace("{world}", at.getWorld().getName())
                    .replace("{size}", String.format(Locale.ROOT, "%.3f", size));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } else if (step instanceof ObjectSequence.Settle) {
            settle();
        } else if (step instanceof ObjectSequence.Remove) {
            remove();
            return false;
        } else if (step instanceof ObjectSequence.Restart) {
            this.step = 0;
            // Once a tick at most, so a loop with no waits in it cannot hang the server.
            return false;
        }
        return isValid();
    }

    // ---- what the client is sent ---------------------------------------------

    void applyItem() {
        int color = ObjectControls.color(state, previous, start, seed);
        display.setItemStack(runtime.items().item(info.model(), color));
    }

    void applyTransform(int ticks) {
        float scale = (float) (size * 2);
        Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(-turn[0]))
                .rotateX((float) Math.toRadians(turn[1]))
                .rotateZ((float) Math.toRadians(turn[2]));
        Transformation next = new Transformation(new Vector3f(), rotation,
                new Vector3f(scale, scale, scale), new Quaternionf());
        // Toggled so the client starts a fresh interpolation even when the
        // delay was already 0: the same idiom the rig animator uses.
        display.setInterpolationDelay(1);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ticks);
        display.setTransformation(next);
    }

    /** An object found standing whose state began long enough ago that its stamp can no longer be trusted. */
    void settleIfOld() {
        if (start >= 0 && gameTime() - start > ObjectControls.SETTLE_AFTER) {
            settle();
        }
    }

    void persist() {
        if (!display.isValid()) {
            return;
        }
        PersistentDataContainer data = display.getPersistentDataContainer();
        data.set(runtime.stateKey, PersistentDataType.INTEGER, state);
        data.set(runtime.previousKey, PersistentDataType.INTEGER, previous);
        data.set(runtime.startKey, PersistentDataType.LONG, start);
        data.set(runtime.seedKey, PersistentDataType.INTEGER, seed);
        data.set(runtime.sizeKey, PersistentDataType.DOUBLE, size);
        data.set(runtime.turnKey, PersistentDataType.STRING, turn[0] + "," + turn[1] + "," + turn[2]);
    }

    private long gameTime() {
        return display.getWorld().getGameTime();
    }

    /** A value eased from one point to another over a span of the runtime's ticks. */
    private static final class Tween {
        final long begin;
        final int ticks;
        final Easing easing;
        final double[] from;
        final double[] to;

        Tween(long begin, int ticks, Easing easing, double[] from, double[] to) {
            this.begin = begin;
            this.ticks = ticks;
            this.easing = easing == null ? Easing.LINEAR : easing;
            this.from = from;
            this.to = to;
        }

        double[] sample(long now) {
            double t = ticks <= 0 ? 1 : (double) (now - begin) / ticks;
            double k = easing.apply(t);
            double[] out = new double[from.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = from[i] + (to[i] - from[i]) * k;
            }
            return out;
        }

        boolean done(long now) {
            return now - begin >= ticks;
        }

        Location endLocation(org.bukkit.World world) {
            return new Location(world, to[0], to[1], to[2]);
        }

        float[] endFloats() {
            float[] out = new float[to.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) to[i];
            }
            return out;
        }
    }
}
