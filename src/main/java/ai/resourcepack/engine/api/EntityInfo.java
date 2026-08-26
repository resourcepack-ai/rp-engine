package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack said a custom entity is.
 *
 * <p>A custom entity is <strong>a real mob wearing a model</strong>. Not a
 * display standing next to one and not a disguise packet: a zombie, or a
 * villager, or whatever the pack named, with its own AI, its own drops, its own
 * damage — and a rig carried along on top of it, with the vanilla body hidden.
 *
 * <p>That is the whole design, and it is what makes this cheap to build and
 * hard to get wrong. Everything a mob is already works: it walks, it takes
 * damage, it dies, other plugins see it, a spawner spawns it, a name tag names
 * it. All the engine adds is what it looks like.
 */
public final class EntityInfo {

    private final ContentId id;
    private final String type;
    private final String model;
    private final String name;
    private final double health;
    private final float scale;
    private final boolean silent;
    private final List<String> tags;

    private EntityInfo(ContentId id, String type, String model, String name,
                       double health, float scale, boolean silent, List<String> tags) {
        this.id = id;
        this.type = type;
        this.model = model;
        this.name = name;
        this.health = health;
        this.scale = scale;
        this.silent = silent;
        this.tags = tags;
    }

    /** Engine internal; built by the entity loader. */
    public static EntityInfo of(ContentId id, String type, String model, String name,
                                double health, float scale, boolean silent, List<String> tags) {
        return new EntityInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(type, "type"),
                model == null ? "" : model,
                name == null ? "" : name,
                health, scale, silent,
                tags == null ? List.of() : List.copyOf(tags));
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /**
     * The vanilla mob underneath, as a {@code EntityType} name.
     *
     * <p>Choose it for behaviour rather than looks, since the looks are
     * replaced: a {@code ZOMBIE} walks towards players and burns in daylight, a
     * {@code VILLAGER} wanders and flees, an {@code ARMOR_STAND} does nothing
     * at all. That choice is the entity's personality and it cannot be
     * configured away.
     */
    public String type() {
        return type;
    }

    /**
     * The item id whose model it wears, or empty to look like what it is.
     *
     * <p>An item rather than a model file, for the same reason a placed model
     * names one: the art already exists as an item, and a second way to point
     * at the same model is a second thing to keep in step.
     */
    public Optional<ContentId> model() {
        return model.isEmpty() ? Optional.empty() : ContentId.parse(model);
    }

    /** The name shown above it, or empty for none. */
    public Optional<String> name() {
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /** Its maximum health, or 0 for the mob's own. */
    public double health() {
        return health;
    }

    /** How much bigger than the item's own size the model is drawn. */
    public float scale() {
        return scale;
    }

    /** Whether it makes its own mob noises. */
    public boolean silent() {
        return silent;
    }

    /**
     * Scoreboard tags put on it when it spawns.
     *
     * <p>So a command block, a datapack or another plugin can find these
     * without knowing anything about this engine. {@code @e[tag=boss]} is a
     * language every server owner already speaks.
     */
    public List<String> tags() {
        return tags;
    }

    @Override
    public String toString() {
        return id + " (" + type + ")";
    }
}
