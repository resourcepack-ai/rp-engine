package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        STONECUTTING,

        /**
         * A smithing table turning a base into the result, from a template, a
         * base and an addition. What vanilla's netherite upgrade is.
         */
        SMITHING,

        /**
         * A smithing table putting an armour trim on the base. There is no
         * result to name: the result is the base, trimmed.
         */
        SMITHING_TRIM,

        /** A brewing stand: an ingredient on top, the base in the bottle slots. */
        BREWING,

        /**
         * An anvil: the base on the left, an optional addition on the right.
         * Either a fixed result, or - with no result - a repair of the base.
         */
        ANVIL
    }

    /**
     * The parts of a recipe made at a smithing table, a brewing stand or an
     * anvil, which have named slots rather than a grid.
     *
     * <p>A class of its own so the seven crafting and cooking types, which
     * have none of this, keep the factory they always had.
     */
    public static final class Stations {

        private static final Stations NONE = new Stations(null, null, null, true, null, 1, 0, 0, 0f);

        private final String template;
        private final String base;
        private final String addition;
        private final boolean copyData;
        private final String pattern;
        private final int additionAmount;
        private final int cost;
        private final int repairPoints;
        private final float repairFraction;

        private Stations(String template, String base, String addition, boolean copyData, String pattern,
                         int additionAmount, int cost, int repairPoints, float repairFraction) {
            this.template = template;
            this.base = base;
            this.addition = addition;
            this.copyData = copyData;
            this.pattern = pattern;
            this.additionAmount = additionAmount;
            this.cost = cost;
            this.repairPoints = repairPoints;
            this.repairFraction = repairFraction;
        }

        /** Engine internal; built by the recipe loader. */
        public static Stations of(String template, String base, String addition, boolean copyData,
                                  String pattern, int additionAmount, int cost,
                                  int repairPoints, float repairFraction) {
            return new Stations(template, base, addition, copyData, pattern,
                    Math.max(1, additionAmount), Math.max(0, cost),
                    Math.max(0, repairPoints), Math.max(0f, repairFraction));
        }

        /** None of it, for a crafting or cooking recipe. */
        public static Stations none() {
            return NONE;
        }
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
    private final Stations stations;

    private RecipeInfo(ContentId id, Type type, String result, int amount, List<String> rows,
                       Map<String, String> keys, List<String> ingredients,
                       float experience, int cookingTime, Stations stations) {
        this.id = id;
        this.type = type;
        this.result = result;
        this.amount = amount;
        this.rows = rows;
        this.keys = keys;
        this.ingredients = ingredients;
        this.experience = experience;
        this.cookingTime = cookingTime;
        this.stations = stations;
    }

    /** Engine internal; built by the recipe loader. */
    public static RecipeInfo of(ContentId id, Type type, String result, int amount, List<String> rows,
                                Map<String, String> keys, List<String> ingredients,
                                float experience, int cookingTime) {
        return of(id, type, result, amount, rows, keys, ingredients, experience, cookingTime, Stations.none());
    }

    /** Engine internal; built by the recipe loader. */
    public static RecipeInfo of(ContentId id, Type type, String result, int amount, List<String> rows,
                                Map<String, String> keys, List<String> ingredients,
                                float experience, int cookingTime, Stations stations) {
        return new RecipeInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(result, "result"),
                Math.max(1, amount),
                rows == null ? List.of() : List.copyOf(rows),
                keys == null ? Map.of() : Map.copyOf(keys),
                ingredients == null ? List.of() : List.copyOf(ingredients),
                experience, cookingTime,
                stations == null ? Stations.none() : stations);
    }

    /** Its id, which also becomes the recipe's key in the server's registry. */
    public ContentId id() {
        return id;
    }

    /** Which kind of recipe. */
    public Type type() {
        return type;
    }

    /**
     * What it makes: a content id, or a vanilla material name. Empty for a
     * trim and for an anvil repair, whose result is the base itself.
     */
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

    /** The ingredients of a shapeless, cooking, stonecutting or brewing recipe. */
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

    /** The smithing template, or empty. */
    public Optional<String> template() {
        return Optional.ofNullable(stations.template);
    }

    /**
     * What goes in the base slot: what a smithing table upgrades or trims, the
     * potion a brewing stand brews from, the left slot of an anvil.
     */
    public Optional<String> base() {
        return Optional.ofNullable(stations.base);
    }

    /** What goes in the addition slot of a smithing table or an anvil, or empty. */
    public Optional<String> addition() {
        return Optional.ofNullable(stations.addition);
    }

    /**
     * Whether a smithing upgrade carries the base's enchantments, wear and
     * trim onto the result, as vanilla's netherite upgrade does.
     */
    public boolean copyData() {
        return stations.copyData;
    }

    /** A trim recipe's pattern, as a key like {@code minecraft:silence}, or empty. */
    public Optional<String> pattern() {
        return Optional.ofNullable(stations.pattern);
    }

    /** How many of the addition an anvil recipe uses at a time. */
    public int additionAmount() {
        return stations.additionAmount;
    }

    /** Experience levels an anvil recipe costs. */
    public int cost() {
        return stations.cost;
    }

    /** Whether this is an anvil recipe that repairs its base rather than making something. */
    public boolean isRepair() {
        return type == Type.ANVIL && result.isEmpty();
    }

    /** Durability points an anvil repair restores per use of the addition. */
    public int repairPoints() {
        return stations.repairPoints;
    }

    /** The fraction of full durability an anvil repair restores per use of the addition. */
    public float repairFraction() {
        return stations.repairFraction;
    }

    /** Whether this is one of the cooking types. */
    public boolean isCooking() {
        return type == Type.SMELTING || type == Type.BLASTING
                || type == Type.SMOKING || type == Type.CAMPFIRE;
    }

    @Override
    public String toString() {
        return id + " (" + type + " -> " + (result.isEmpty() ? "its base" : result) + ")";
    }
}
