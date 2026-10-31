package ai.resourcepack.engine.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack, or a Studio push, said a custom entity is.
 *
 * <p>A custom entity is <strong>a real mob wearing a model</strong>. Not a
 * display standing next to one and not a disguise packet: a zombie, or a
 * polar bear, or whatever the pack named, with its own AI, its own pathing,
 * its own hitbox — and a rig carried along on top of it, with the vanilla body
 * hidden.
 *
 * <p>That is the whole design, and it is what makes this cheap to build and
 * hard to get wrong. Everything a mob is already works: it walks, it takes
 * damage, it dies, other plugins see it, a name tag names it. The engine adds
 * what it looks like, what it sounds like, what it drops, and the animation it
 * plays for what it is doing ({@link MobState}).
 *
 * <p>Built with {@link #builder}. The eight-argument {@link #of} is the shape
 * this had before any of that, kept so a plugin that registered entities
 * against it still compiles; it describes a mob with no animations and no
 * sounds of its own.
 */
public final class EntityInfo {

    /** The sound slots a mob can fill, by the name an author writes. */
    public static final List<String> SOUND_KEYS = List.of("ambient", "hurt", "death", "attack", "step");

    private final ContentId id;
    private final String type;
    private final String model;
    private final String carrier;
    private final String name;
    private final boolean nameVisible;
    private final double health;
    private final float scale;
    private final boolean silent;
    private final List<String> tags;
    private final Map<MobState, String> animations;
    private final Map<String, String> sounds;
    private final List<EntityDrop> drops;
    private final int xp;
    private final double damage;
    private final double speed;
    private final double knockbackResistance;
    private final double height;
    private final float turn;
    private final String headBone;
    private final boolean ai;
    private final boolean burns;
    private final boolean hurtFlash;
    private final double followRange;
    private final boolean glowing;
    private final boolean fireImmune;
    private final BossBar bossBar;
    private final Ranged ranged;
    private final Spawning spawning;

    private EntityInfo(Builder b) {
        this.id = b.id;
        this.type = b.type;
        this.model = b.model;
        this.carrier = b.carrier;
        this.name = b.name;
        this.nameVisible = b.nameVisible == null ? !b.name.isEmpty() : b.nameVisible;
        this.health = b.health;
        this.scale = b.scale;
        this.silent = b.silent;
        this.tags = List.copyOf(b.tags);
        this.animations = Collections.unmodifiableMap(new EnumMap<>(b.animations));
        this.sounds = Collections.unmodifiableMap(new LinkedHashMap<>(b.sounds));
        this.drops = List.copyOf(b.drops);
        this.xp = b.xp;
        this.damage = b.damage;
        this.speed = b.speed;
        this.knockbackResistance = b.knockbackResistance;
        this.height = b.height;
        this.turn = b.turn;
        this.headBone = b.headBone;
        this.ai = b.ai;
        this.burns = b.burns;
        this.hurtFlash = b.hurtFlash;
        this.followRange = b.followRange;
        this.glowing = b.glowing;
        this.fireImmune = b.fireImmune;
        this.bossBar = b.bossBar;
        this.ranged = b.ranged;
        this.spawning = b.spawning;
    }

    /** Engine internal; the shape before mobs animated. */
    public static EntityInfo of(ContentId id, String type, String model, String name,
                                double health, float scale, boolean silent, List<String> tags) {
        return builder(id, type).model(model).name(name).health(health).scale(scale)
                .silent(silent).tags(tags).build();
    }

    /** A mob of {@code type} with nothing else said: the safe defaults. */
    public static Builder builder(ContentId id, String type) {
        return new Builder(Objects.requireNonNull(id, "id"), Objects.requireNonNull(type, "type"));
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /**
     * The vanilla mob underneath, as a {@code EntityType} name.
     *
     * <p>Choose it for behaviour rather than looks, since the looks are
     * replaced: a {@code ZOMBIE} walks towards players, a {@code POLAR_BEAR}
     * wanders and fights back, an {@code ALLAY} drifts. That choice is the
     * entity's personality and it cannot be configured away.
     */
    public String type() {
        return type;
    }

    /**
     * The item id whose model it wears, or empty when it wears a
     * {@link #carrier()} or looks like what it is.
     */
    public Optional<ContentId> model() {
        return model.isEmpty() ? Optional.empty() : ContentId.parse(model);
    }

    /**
     * The {@code custom_model_data} string its art is drawn with, for a mob
     * pushed from Studio — see {@link VehicleInfo#carrier()}, which is the
     * same split for the same reason. Exactly one of this and {@link #model()}
     * is set on a mob that has a model at all.
     */
    public Optional<String> carrier() {
        return carrier.isEmpty() ? Optional.empty() : Optional.of(carrier);
    }

    /** The name shown above it, or empty for none. */
    public Optional<String> name() {
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /** Whether the name floats over it all the time, rather than only when looked at. */
    public boolean nameVisible() {
        return nameVisible;
    }

    /** Its maximum health, or 0 for the mob's own. */
    public double health() {
        return health;
    }

    /** How much bigger than built the model is drawn. */
    public float scale() {
        return scale;
    }

    /**
     * Whether it is silent. True as well whenever it has any sound of its own,
     * since a polar bear's growl over a rhino's grunt is two animals.
     */
    public boolean silent() {
        return silent || !sounds.isEmpty();
    }

    /**
     * Scoreboard tags put on it when it spawns, so a command block or a
     * datapack can find these without knowing anything about this engine.
     */
    public List<String> tags() {
        return tags;
    }

    /** State to animation name. A state not here does not animate (see {@link MobState}). */
    public Map<MobState, String> animations() {
        return animations;
    }

    /**
     * Slot ({@link #SOUND_KEYS}) to sound id, as written: a pack's
     * {@code namespace:id}, {@code studio:<id>}, or a vanilla sound event.
     */
    public Map<String, String> sounds() {
        return sounds;
    }

    /** What it drops instead of its vanilla loot. Empty drops nothing. */
    public List<EntityDrop> drops() {
        return drops;
    }

    /** Experience it drops. */
    public int xp() {
        return xp;
    }

    /** Its attack damage, or 0 for the mob's own. */
    public double damage() {
        return damage;
    }

    /** A multiplier on the mob's own movement speed. */
    public double speed() {
        return speed;
    }

    /** 0 to 1. */
    public double knockbackResistance() {
        return knockbackResistance;
    }

    /**
     * How tall its hitbox should be, in blocks, or 0 for the mob's own. Applied
     * through the {@code scale} attribute, so only on 1.20.5 and newer.
     */
    public double height() {
        return height;
    }

    /** Degrees added to the model's heading, for a model built facing another way. */
    public float turn() {
        return turn;
    }

    /** A bone that turns toward what it is fighting, or empty. */
    public Optional<String> headBone() {
        return headBone.isEmpty() ? Optional.empty() : Optional.of(headBone);
    }

    /** False stands it still: no pathing, no attacking, no turning. */
    public boolean ai() {
        return ai;
    }

    /** Whether daylight may set it alight, as it would a zombie. */
    public boolean burns() {
        return burns;
    }

    /**
     * Whether its model flashes red for a moment when it is hurt, as a vanilla
     * mob does. On by default.
     *
     * @since 0.2.51
     */
    public boolean hurtFlash() {
        return hurtFlash;
    }

    /**
     * How far away, in blocks, it notices something to chase, or 0 for the
     * mob's own.
     *
     * @since 0.2.51
     */
    public double followRange() {
        return followRange;
    }

    /**
     * Whether its model is drawn with the glowing outline.
     *
     * @since 0.2.51
     */
    public boolean glowing() {
        return glowing;
    }

    /**
     * Whether fire, lava and burning do it no harm.
     *
     * @since 0.2.51
     */
    public boolean fireImmune() {
        return fireImmune;
    }

    /**
     * The boss bar shown to players near it, or empty for none.
     *
     * @since 0.2.51
     */
    public Optional<BossBar> bossBar() {
        return Optional.ofNullable(bossBar);
    }

    /**
     * How it shoots, or empty for a mob that only fights up close.
     *
     * @since 0.2.51
     */
    public Optional<Ranged> ranged() {
        return Optional.ofNullable(ranged);
    }

    /**
     * Where and how often it appears on its own, in place of a natural spawn,
     * or empty for a mob that only ever comes from a command, an egg or a
     * plugin.
     *
     * @since 0.2.51
     */
    public Optional<Spawning> spawning() {
        return Optional.ofNullable(spawning);
    }

    @Override
    public String toString() {
        return id + " (" + type + ")";
    }

    /**
     * A boss bar with the mob's name and health, shown to every player within
     * {@link #range()} blocks of it.
     *
     * @since 0.2.51
     */
    public static final class BossBar {

        /** The colours a boss bar can be, by Bukkit's {@code BarColor} names. */
        public static final List<String> COLORS = List.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");
        /** The styles, by Bukkit's {@code BarStyle} names. */
        public static final List<String> STYLES = List.of("SOLID", "SEGMENTED_6", "SEGMENTED_10", "SEGMENTED_12",
                "SEGMENTED_20");

        private final String color;
        private final String style;
        private final double range;

        private BossBar(String color, String style, double range) {
            this.color = color;
            this.style = style;
            this.range = range;
        }

        /**
         * A bar; an unknown colour is purple, an unknown style solid, and the
         * range is held to 1 to 256 blocks (32 when not positive).
         */
        public static BossBar of(String color, String style, double range) {
            String c = color == null ? "" : color.trim().toUpperCase(java.util.Locale.ROOT);
            String s = style == null ? "" : style.trim().toUpperCase(java.util.Locale.ROOT);
            double r = Double.isFinite(range) && range > 0 ? Math.max(1, Math.min(256, range)) : 32;
            return new BossBar(COLORS.contains(c) ? c : "PURPLE", STYLES.contains(s) ? s : "SOLID", r);
        }

        /** One of {@link #COLORS}. */
        public String color() {
            return color;
        }

        /** One of {@link #STYLES}. */
        public String style() {
            return style;
        }

        /** Blocks. */
        public double range() {
            return range;
        }
    }

    /**
     * A mob that shoots. A skeleton (or stray, or bogged) on 1.21.4 or newer
     * shoots with its own aim and keeps its distance as it always does, holding
     * a bow nobody can see; anything else is fired from by the engine every
     * {@link #cooldown()} seconds while it has a target in range and in sight.
     *
     * @since 0.2.51
     */
    public static final class Ranged {

        /** What it can shoot, by the name an author writes. */
        public static final List<String> PROJECTILES = List.of("arrow", "snowball", "small_fireball", "fireball",
                "wither_skull", "trident");

        private final String projectile;
        private final double damage;
        private final double cooldown;
        private final double range;

        private Ranged(String projectile, double damage, double cooldown, double range) {
            this.projectile = projectile;
            this.damage = damage;
            this.cooldown = cooldown;
            this.range = range;
        }

        /**
         * How it shoots. An unknown projectile is an arrow; {@code damage} 0 is
         * the projectile's own; {@code cooldown} is held to 0.25 to 60 seconds
         * (2 when not positive) and {@code range} to 2 to 64 blocks (16).
         */
        public static Ranged of(String projectile, double damage, double cooldown, double range) {
            String p = projectile == null ? "" : projectile.trim().toLowerCase(java.util.Locale.ROOT);
            if (p.startsWith("minecraft:")) {
                p = p.substring("minecraft:".length());
            }
            return new Ranged(PROJECTILES.contains(p) ? p : "arrow",
                    Double.isFinite(damage) && damage > 0 ? Math.min(2048, damage) : 0,
                    Double.isFinite(cooldown) && cooldown > 0 ? Math.max(0.25, Math.min(60, cooldown)) : 2,
                    Double.isFinite(range) && range > 0 ? Math.max(2, Math.min(64, range)) : 16);
        }

        /** One of {@link #PROJECTILES}. */
        public String projectile() {
            return projectile;
        }

        /** Damage per hit, or 0 for the projectile's own. */
        public double damage() {
            return damage;
        }

        /** Seconds between shots when the engine is the one shooting. */
        public double cooldown() {
            return cooldown;
        }

        /** Blocks: how far away a target may be and still be shot at. */
        public double range() {
            return range;
        }
    }

    /**
     * Natural spawning: a share of the natural spawns that match these rules
     * are this mob instead. Replacing rather than adding is what keeps the
     * world's mob caps honest: the game only spawns as many as it would have.
     *
     * @since 0.2.51
     */
    public static final class Spawning {

        /** {@code day}, {@code night} or {@code any}. */
        public static final List<String> TIMES = List.of("any", "day", "night");

        private final List<String> worlds;
        private final List<String> biomes;
        private final List<String> replaces;
        private final double chance;
        private final int minLight;
        private final int maxLight;
        private final String time;
        private final int minGroup;
        private final int maxGroup;
        private final int maxPerChunk;

        private Spawning(List<String> worlds, List<String> biomes, List<String> replaces, double chance,
                         int minLight, int maxLight, String time, int minGroup, int maxGroup, int maxPerChunk) {
            this.worlds = worlds;
            this.biomes = biomes;
            this.replaces = replaces;
            this.chance = chance;
            this.minLight = minLight;
            this.maxLight = maxLight;
            this.time = time;
            this.minGroup = minGroup;
            this.maxGroup = maxGroup;
            this.maxPerChunk = maxPerChunk;
        }

        /**
         * The rules, each cleaned: biome names lower-cased and mob types
         * upper-cased with any {@code minecraft:} dropped, the chance held to
         * 0 to 1, light to 0 to 15, groups to 1 to 16 and the cap to 1 to 64.
         */
        public static Spawning of(List<String> worlds, List<String> biomes, List<String> replaces, double chance,
                                  int minLight, int maxLight, String time, int minGroup, int maxGroup,
                                  int maxPerChunk) {
            int lo = Math.max(0, Math.min(15, minLight));
            int hi = Math.max(lo, Math.min(15, maxLight));
            int groupLo = Math.max(1, Math.min(16, minGroup));
            int groupHi = Math.max(groupLo, Math.min(16, maxGroup));
            String when = time == null ? "any" : time.trim().toLowerCase(java.util.Locale.ROOT);
            return new Spawning(clean(worlds, false, false), clean(biomes, true, false), clean(replaces, false, true),
                    Double.isFinite(chance) ? Math.max(0, Math.min(1, chance)) : 0.1,
                    lo, hi, TIMES.contains(when) ? when : "any", groupLo, groupHi,
                    Math.max(1, Math.min(64, maxPerChunk)));
        }

        private static List<String> clean(List<String> names, boolean lower, boolean upper) {
            if (names == null) {
                return List.of();
            }
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                String n = name.trim();
                if (lower || upper) {
                    if (n.toLowerCase(java.util.Locale.ROOT).startsWith("minecraft:")) {
                        n = n.substring("minecraft:".length());
                    }
                    n = upper ? n.toUpperCase(java.util.Locale.ROOT) : n.toLowerCase(java.util.Locale.ROOT);
                }
                out.add(n);
            }
            return List.copyOf(out);
        }

        /** World names it may appear in; empty is every world. */
        public List<String> worlds() {
            return worlds;
        }

        /** Biome names ({@code desert}), a {@code *} matching anything; empty is every biome. */
        public List<String> biomes() {
            return biomes;
        }

        /**
         * Vanilla mob types ({@code ZOMBIE}) whose natural spawns it takes the
         * place of. Empty is any natural spawn of the same kind as its own type
         * (a monster for a monster, an animal for an animal).
         */
        public List<String> replaces() {
            return replaces;
        }

        /** 0 to 1: the share of matching natural spawns that become this mob. */
        public double chance() {
            return chance;
        }

        /** 0 to 15. */
        public int minLight() {
            return minLight;
        }

        /** 0 to 15. */
        public int maxLight() {
            return maxLight;
        }

        /** {@code any}, {@code day} or {@code night}. */
        public String time() {
            return time;
        }

        /** How many appear together, at least. */
        public int minGroup() {
            return minGroup;
        }

        /** How many appear together, at most. */
        public int maxGroup() {
            return maxGroup;
        }

        /** The most of this mob a chunk may hold before natural spawning stops adding them there. */
        public int maxPerChunk() {
            return maxPerChunk;
        }
    }

    /** Every field, with the safe default until set. */
    public static final class Builder {
        private final ContentId id;
        private final String type;
        private String model = "";
        private String carrier = "";
        private String name = "";
        private Boolean nameVisible;
        private double health;
        private float scale = 1f;
        private boolean silent;
        private List<String> tags = List.of();
        private final Map<MobState, String> animations = new EnumMap<>(MobState.class);
        private final Map<String, String> sounds = new LinkedHashMap<>();
        private List<EntityDrop> drops = List.of();
        private int xp;
        private double damage;
        private double speed = 1;
        private double knockbackResistance;
        private double height;
        private float turn;
        private String headBone = "";
        private boolean ai = true;
        private boolean burns;
        private boolean hurtFlash = true;
        private double followRange;
        private boolean glowing;
        private boolean fireImmune;
        private BossBar bossBar;
        private Ranged ranged;
        private Spawning spawning;

        private Builder(ContentId id, String type) {
            this.id = id;
            this.type = type;
        }

        public Builder model(String model) {
            this.model = model == null ? "" : model;
            return this;
        }

        public Builder carrier(String carrier) {
            this.carrier = carrier == null ? "" : carrier;
            return this;
        }

        public Builder name(String name) {
            this.name = name == null ? "" : name;
            return this;
        }

        /** Null is "when it has a name", which is what an entity did before this existed. */
        public Builder nameVisible(Boolean visible) {
            this.nameVisible = visible;
            return this;
        }

        public Builder health(double health) {
            this.health = Double.isFinite(health) ? Math.max(0, Math.min(1024, health)) : 0;
            return this;
        }

        public Builder scale(float scale) {
            this.scale = Float.isFinite(scale) && scale > 0 ? scale : 1f;
            return this;
        }

        public Builder silent(boolean silent) {
            this.silent = silent;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags == null ? List.of() : tags;
            return this;
        }

        /** Blank names are left out, which is what "this state does not animate" is. */
        public Builder animations(Map<MobState, String> animations) {
            this.animations.clear();
            if (animations != null) {
                animations.forEach((state, name) -> {
                    if (state != null && name != null && !name.isBlank()) {
                        this.animations.put(state, name.trim());
                    }
                });
            }
            return this;
        }

        /** Unknown slots and blank ids are left out. */
        public Builder sounds(Map<String, String> sounds) {
            this.sounds.clear();
            if (sounds != null) {
                sounds.forEach((slot, sound) -> {
                    if (slot != null && SOUND_KEYS.contains(slot) && sound != null && !sound.isBlank()) {
                        this.sounds.put(slot, sound.trim());
                    }
                });
            }
            return this;
        }

        public Builder drops(List<EntityDrop> drops) {
            this.drops = drops == null ? List.of() : drops;
            return this;
        }

        public Builder xp(int xp) {
            this.xp = Math.max(0, xp);
            return this;
        }

        public Builder damage(double damage) {
            this.damage = Double.isFinite(damage) ? Math.max(0, Math.min(2048, damage)) : 0;
            return this;
        }

        public Builder speed(double speed) {
            this.speed = Double.isFinite(speed) ? Math.max(0.1, Math.min(4, speed)) : 1;
            return this;
        }

        public Builder knockbackResistance(double resistance) {
            this.knockbackResistance = Double.isFinite(resistance) ? Math.max(0, Math.min(1, resistance)) : 0;
            return this;
        }

        public Builder height(double height) {
            this.height = Double.isFinite(height) && height > 0 ? Math.min(64, height) : 0;
            return this;
        }

        public Builder turn(float degrees) {
            this.turn = Float.isFinite(degrees) ? degrees % 360f : 0f;
            return this;
        }

        public Builder headBone(String bone) {
            this.headBone = bone == null ? "" : bone.trim();
            return this;
        }

        public Builder ai(boolean ai) {
            this.ai = ai;
            return this;
        }

        public Builder burns(boolean burns) {
            this.burns = burns;
            return this;
        }

        /** @since 0.2.51 */
        public Builder hurtFlash(boolean flash) {
            this.hurtFlash = flash;
            return this;
        }

        /** Blocks, 0 for the mob's own. @since 0.2.51 */
        public Builder followRange(double blocks) {
            this.followRange = Double.isFinite(blocks) && blocks > 0 ? Math.min(128, blocks) : 0;
            return this;
        }

        /** @since 0.2.51 */
        public Builder glowing(boolean glowing) {
            this.glowing = glowing;
            return this;
        }

        /** @since 0.2.51 */
        public Builder fireImmune(boolean immune) {
            this.fireImmune = immune;
            return this;
        }

        /** Null for none. @since 0.2.51 */
        public Builder bossBar(BossBar bar) {
            this.bossBar = bar;
            return this;
        }

        /** Null for a mob that does not shoot. @since 0.2.51 */
        public Builder ranged(Ranged ranged) {
            this.ranged = ranged;
            return this;
        }

        /** Null for a mob that never spawns on its own. @since 0.2.51 */
        public Builder spawning(Spawning spawning) {
            this.spawning = spawning;
            return this;
        }

        public EntityInfo build() {
            return new EntityInfo(this);
        }
    }
}
