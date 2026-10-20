package ai.resourcepack.engine.api;

import java.util.Objects;
import java.util.function.Predicate;

import org.bukkit.entity.Entity;

/**
 * How one cast of a VFX plays: once or on a loop, for how long, until what, how
 * fast, how big, where, and for whom. {@link #DEFAULT} plays it once, where it
 * was cast, for everybody whose pack has it.
 *
 * <pre>
 * VfxOptions aura = VfxOptions.builder()
 *         .loop()                                   // until stopped
 *         .until("{in_combat} == \"false\"")        // ... or until this holds
 *         .follow(player)                           // moves and turns with them
 *         .scale(1.5)
 *         .tag("aura")
 *         .build();
 * </pre>
 *
 * <p>A loop repeats the effect's loop section when it has one (made in Studio:
 * the build-up plays once, the middle repeats, the fade plays when it stops),
 * and the whole effect when it has none.
 */
public final class VfxOptions {

    /** What a loop does when it is told to stop, by its time, its count, its condition or {@link VfxPlay#finish}. */
    public enum End {
        /** Plays on to the end: out of its loop section through its fade, or to the end of the pass. */
        FINISH,
        /** Ends there and then. */
        CUT
    }

    /** Who sees it. */
    public enum Viewers {
        /** Everybody near whose pack has it. */
        EVERYONE,
        /** Only the player it was cast on (nobody, for a cast at a place). */
        CASTER
    }

    public static final VfxOptions DEFAULT = builder().build();

    private final boolean loop;
    private final long loopTicks;
    private final int loopTimes;
    private final String until;
    private final String whileTrue;
    private final Predicate<VfxPlay> stopWhen;
    private final int checkEvery;
    private final End end;
    private final double speed;
    private final double scale;
    private final double turn;
    private final double[] offset;
    private final Entity follow;
    private final int delay;
    private final boolean vary;
    private final int seed;
    private final boolean sounds;
    private final double volume;
    private final Viewers viewers;
    private final String tag;

    private VfxOptions(Builder b) {
        this.loop = b.loop;
        this.loopTicks = b.loopTicks;
        this.loopTimes = b.loopTimes;
        this.until = b.until;
        this.whileTrue = b.whileTrue;
        this.stopWhen = b.stopWhen;
        this.checkEvery = b.checkEvery;
        this.end = b.end;
        this.speed = b.speed;
        this.scale = b.scale;
        this.turn = b.turn;
        this.offset = b.offset.clone();
        this.follow = b.follow;
        this.delay = b.delay;
        this.vary = b.vary;
        this.seed = b.seed;
        this.sounds = b.sounds;
        this.volume = b.volume;
        this.viewers = b.viewers;
        this.tag = b.tag;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder starting from these, to change one or two. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.loop = loop;
        b.loopTicks = loopTicks;
        b.loopTimes = loopTimes;
        b.until = until;
        b.whileTrue = whileTrue;
        b.stopWhen = stopWhen;
        b.checkEvery = checkEvery;
        b.end = end;
        b.speed = speed;
        b.scale = scale;
        b.turn = turn;
        b.offset = offset.clone();
        b.follow = follow;
        b.delay = delay;
        b.vary = vary;
        b.seed = seed;
        b.sounds = sounds;
        b.volume = volume;
        b.viewers = viewers;
        b.tag = tag;
        return b;
    }

    /** Whether it plays on a loop. */
    public boolean loop() {
        return loop;
    }

    /** How long a loop lasts, in ticks; 0 is no limit. */
    public long loopTicks() {
        return loopTicks;
    }

    /** How many times a loop repeats before it ends; 0 is no limit. */
    public int loopTimes() {
        return loopTimes;
    }

    /** A condition ({@code if:} language) that ends the loop once it holds, or null. */
    public String until() {
        return until;
    }

    /** A condition that ends the loop once it stops holding, or null. */
    public String whileTrue() {
        return whileTrue;
    }

    /** A check of your own that ends the loop once it answers true, or null. */
    public Predicate<VfxPlay> stopWhen() {
        return stopWhen;
    }

    /** Ticks between checks of {@link #until}, {@link #whileTrue} and {@link #stopWhen}. */
    public int checkEvery() {
        return checkEvery;
    }

    public End end() {
        return end;
    }

    /** Playback speed: 1 as made, 2 twice as fast. */
    public double speed() {
        return speed;
    }

    /** Size: 1 as made. Distances and sprites both grow. */
    public double scale() {
        return scale;
    }

    /** Degrees turned to the left of the way the caster faces. */
    public double turn() {
        return turn;
    }

    /** Blocks from the cast point in the effect's frame: [left, up, ahead]. */
    public double[] offset() {
        return offset.clone();
    }

    /** What it moves and turns with, or null to stay where it was cast. */
    public Entity follow() {
        return follow;
    }

    /** Ticks before it starts. */
    public int delay() {
        return delay;
    }

    /** Whether each pass of a loop draws its own random sparks rather than repeating the last. */
    public boolean vary() {
        return vary;
    }

    /** The seed its random draws start from; the same seed plays the same way. */
    public int seed() {
        return seed;
    }

    /** Whether its sounds play. */
    public boolean sounds() {
        return sounds;
    }

    /** Multiplies every sound's volume. */
    public double volume() {
        return volume;
    }

    public Viewers viewers() {
        return viewers;
    }

    /** A name to stop it by ({@link Vfx#stop(String)}), or null. */
    public String tag() {
        return tag;
    }

    public static final class Builder {
        private boolean loop;
        private long loopTicks;
        private int loopTimes;
        private String until;
        private String whileTrue;
        private Predicate<VfxPlay> stopWhen;
        private int checkEvery = 20;
        private End end = End.FINISH;
        private double speed = 1;
        private double scale = 1;
        private double turn;
        private double[] offset = {0, 0, 0};
        private Entity follow;
        private int delay;
        private boolean vary;
        private int seed = 1;
        private boolean sounds = true;
        private double volume = 1;
        private Viewers viewers = Viewers.EVERYONE;
        private String tag;

        private Builder() {
        }

        /** Plays on a loop until stopped, or until a time, count or condition given here says. */
        public Builder loop() {
            this.loop = true;
            return this;
        }

        /** Plays once (the default). */
        public Builder once() {
            this.loop = false;
            return this;
        }

        /** Loops for this many ticks (20 a second), then ends as {@link #end} says. */
        public Builder loopFor(long ticks) {
            this.loop = true;
            this.loopTicks = Math.max(0, ticks);
            return this;
        }

        /** Loops this many times, then ends as {@link #end} says. */
        public Builder loopTimes(int times) {
            this.loop = true;
            this.loopTimes = Math.max(0, times);
            return this;
        }

        /**
         * Loops until a condition holds, in the {@code if:} language dialogs
         * use: {@code {arena_state} == "ended"}, {@code !perm:vip}, {@code &&},
         * {@code ||}, PlaceholderAPI. Read for the player it was cast on.
         */
        public Builder until(String condition) {
            this.loop = true;
            this.until = blank(condition);
            return this;
        }

        /** Loops for as long as a condition holds. */
        public Builder whileTrue(String condition) {
            this.loop = true;
            this.whileTrue = blank(condition);
            return this;
        }

        /** Loops until your own check answers true. Main thread. */
        public Builder stopWhen(Predicate<VfxPlay> check) {
            this.loop = true;
            this.stopWhen = check;
            return this;
        }

        /** Ticks between condition checks: 1 to 1200, 20 by default. */
        public Builder checkEvery(int ticks) {
            this.checkEvery = Math.max(1, Math.min(1200, ticks));
            return this;
        }

        public Builder end(End end) {
            this.end = Objects.requireNonNull(end, "end");
            return this;
        }

        /** 0.1 to 10: 0.5 is half speed. */
        public Builder speed(double speed) {
            this.speed = clamp(speed, 0.1, 10, 1);
            return this;
        }

        /** 0.1 to 10: 2 is twice the size. */
        public Builder scale(double scale) {
            this.scale = clamp(scale, 0.1, 10, 1);
            return this;
        }

        /** Degrees to the left of the caster's facing; negative turns right. */
        public Builder turn(double degrees) {
            this.turn = Double.isFinite(degrees) ? degrees : 0;
            return this;
        }

        /** Moves it from the cast point, in blocks: to the caster's left, up, and ahead. */
        public Builder offset(double left, double up, double ahead) {
            this.offset = new double[] {clamp(left, -64, 64, 0), clamp(up, -64, 64, 0), clamp(ahead, -64, 64, 0)};
            return this;
        }

        /** Moves and turns with this entity (a player, a mob) for as long as it plays. */
        public Builder follow(Entity entity) {
            this.follow = entity;
            return this;
        }

        /** Ticks before it starts: 0 to 6000. */
        public Builder delay(int ticks) {
            this.delay = Math.max(0, Math.min(6000, ticks));
            return this;
        }

        /** Each pass of a loop gets its own random draws. */
        public Builder vary(boolean vary) {
            this.vary = vary;
            return this;
        }

        public Builder seed(int seed) {
            this.seed = seed;
            return this;
        }

        public Builder sounds(boolean on) {
            this.sounds = on;
            return this;
        }

        /** 0 to 4: multiplies every sound's volume. */
        public Builder volume(double volume) {
            this.volume = clamp(volume, 0, 4, 1);
            return this;
        }

        public Builder viewers(Viewers viewers) {
            this.viewers = Objects.requireNonNull(viewers, "viewers");
            return this;
        }

        /** A name to stop it by later; any text, compared ignoring case. */
        public Builder tag(String tag) {
            this.tag = blank(tag);
            return this;
        }

        public VfxOptions build() {
            return new VfxOptions(this);
        }

        private static String blank(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }

        private static double clamp(double v, double lo, double hi, double fallback) {
            return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : fallback;
        }
    }
}
