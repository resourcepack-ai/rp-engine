package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a content pack said a recipe is.
 *
 * <p>An ingredient here is a <em>content id or a vanilla material</em>, and
 * both are just strings until the server resolves them. That is deliberate:
 * the loader has no way to know whether {@code mypack:ruby} is going to exist
 * by the time everything has loaded, and refusing a recipe whose ingredient
 * belongs to a pack that has not been read yet would make load order matter.
 */
public final class RecipeInfo {

    /** The kinds of recipe a pack can write. */
    public enum Type {

        /** A pattern in a crafting grid. Position matters. */
        SHAPED,

        /** A set of ingredients in a crafting grid. Position does not. */
        SHAPELESS,

        /** A furnace. */
        SMELTING,

        /** A blast furnace: ores, twice as fast, ingots only. */
        BLASTING,

        /** A smoker: food, twice as fast. */
        SMOKING,

        /** A campfire. */
        CAMPFIRE,

        /** A stonecutter. */
        STONECUTTING
    }

    private final ContentId id;
    private final Type type;
    private final String result;
    private final int amount;
    private final List<String> rows;
    private final Map<String, String> keys;
    private final List<String> ingredients;
    private final float experience;
    private final int cookingTime;

    private RecipeInfo(ContentId id, Type type, String result, int amount, List<String> rows,
                       Map<String, String> keys, List<String> ingredients,
                       float experience, int cookingTime) {
        this.id = id;
        this.type = type;
        this.result = result;
        this.amount = amount;
        this.rows = rows;
        this.keys = keys;
        this.ingredients = ingredients;
        this.experience = experience;
        this.cookingTime = cookingTime;
    }

    /** Engine internal; built by the recipe loader. */
    public static RecipeInfo of(ContentId id, Type type, String result, int amount, List<String> rows,
                                Map<String, String> keys, List<String> ingredients,
                                float experience, int cookingTime) {
        return new RecipeInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(result, "result"),
                Math.max(1, amount),
                rows == null ? List.of() : List.copyOf(rows),
                keys == null ? Map.of() : Map.copyOf(keys),
                ingredients == null ? List.of() : List.copyOf(ingredients),
                experience, cookingTime);
    }

    /** Its id, which also becomes the recipe's key in the server's registry. */
    public ContentId id() {
        return id;
    }

    /** Which kind of recipe. */
    public Type type() {
        return type;
    }

    /** What it makes: a content id, or a vanilla material name. */
    public String result() {
        return result;
    }

    /** How many it makes. */
    public int amount() {
        return amount;
    }

    /** The rows of a shaped pattern, each up to three characters. */
    public List<String> rows() {
        return rows;
    }

    /** What each character in the pattern stands for. */
    public Map<String, String> keys() {
        return keys;
    }

    /** The ingredients of a shapeless, cooking or stonecutting recipe. */
    public List<String> ingredients() {
        return ingredients;
    }

    /** Experience for a cooking recipe. */
    public float experience() {
        return experience;
    }

    /** Ticks for a cooking recipe. */
    public int cookingTime() {
        return cookingTime;
    }

    /** Whether this is one of the cooking types. */
    public boolean isCooking() {
        return type == Type.SMELTING || type == Type.BLASTING
                || type == Type.SMOKING || type == Type.CAMPFIRE;
    }

    @Override
    public String toString() {
        return id + " (" + type + " -> " + result + ")";
    }
}
