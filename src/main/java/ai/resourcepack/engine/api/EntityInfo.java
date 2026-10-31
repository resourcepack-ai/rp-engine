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

    @Override
    public String toString() {
        return id + " (" + type + ")";
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

        public EntityInfo build() {
            return new EntityInfo(this);
        }
    }
}
