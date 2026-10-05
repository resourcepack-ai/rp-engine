package ai.resourcepack.engine.core.recipe;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.RecipeInfo;
import ai.resourcepack.engine.core.version.Compatibility;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.event.Listener;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registers a pack's recipes with the server, and takes them away again.
 *
 * <p><strong>Taking them away is the part that matters.</strong> A recipe lives
 * in the server's own registry, not in ours, so a reload that only added would
 * leave every previous version of every recipe behind — and a recipe deleted
 * from a content pack would keep working for ever, which is a bug nobody can
 * fix without restarting. Everything registered is remembered and dropped
 * before the next load.
 *
 * <p>Ingredients are resolved late, after every pack has loaded, so a recipe
 * may name an item from a pack that had not been read when it was parsed.
 * Otherwise load order would decide whether somebody's recipe worked.
 *
 * <p>Three kinds are not the server's alone. A smithing recipe is registered
 * but its result is decided by {@link RecipeStations}; an anvil recipe is
 * nothing but {@link RecipeStations}, there being no anvil recipe to register;
 * and a brewing recipe goes to Paper's brewer, there being no Bukkit one (see
 * {@link PaperBrewing}).
 */
public final class Recipes {

    private final Plugin plugin;
    private final Items items;
    private final Compatibility compatibility;
    private final RecipeStations stations;
    private final List<NamespacedKey> registered = new ArrayList<>();
    private final List<NamespacedKey> brewing = new ArrayList<>();
    private Optional<PaperBrewing> brewer;
    private int anvilCount;

    public Recipes(Plugin plugin, Items items, Compatibility compatibility) {
        this.plugin = plugin;
        this.items = items;
        this.compatibility = compatibility;
        this.stations = new RecipeStations(items, compatibility);
    }

    /**
     * The listener the smithing table and the anvil need. Registered once by
     * the plugin; it holds whatever the last {@link #replace} gave it.
     */
    public Listener stations() {
        return stations;
    }

    /**
     * Replaces every recipe this engine has registered.
     *
     * @return what could not be registered, as sentences a console can print
     */
    public List<String> replace(Map<ContentId, RecipeInfo> recipes) {
        clear();
        List<String> problems = new ArrayList<>();
        if (recipes == null) {
            return problems;
        }
        Map<NamespacedKey, RecipeStations.Smithing> smithing = new LinkedHashMap<>();
        List<RecipeStations.Anvil> anvil = new ArrayList<>();
        int unbrewed = 0;
        for (RecipeInfo info : recipes.values()) {
            try {
                switch (info.type()) {
                    case ANVIL:
                        anvil(info, problems).ifPresent(anvil::add);
                        break;
                    case BREWING:
                        if (!brew(info, problems)) {
                            unbrewed++;
                        }
                        break;
                    case SMITHING:
                    case SMITHING_TRIM:
                        smithing(info, problems).ifPresent(resolved -> {
                            NamespacedKey key = keyOf(info.id());
                            registered.add(key);
                            smithing.put(key, resolved);
                        });
                        break;
                    default:
                        build(info, problems).ifPresent(recipe -> {
                            Bukkit.addRecipe(recipe);
                            registered.add(keyOf(info.id()));
                        });
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                // A duplicate key, or an ingredient the server refused. Never
                // let one bad recipe stop the rest being registered.
                problems.add(info.id() + ": " + e.getMessage());
            }
        }
        if (unbrewed > 0) {
            problems.add(unbrewed + (unbrewed == 1 ? " brewing recipe was" : " brewing recipes were")
                    + " not registered: brewing recipes need Paper, which adds them to the game's own "
                    + "brewing table. Bukkit has no brewing recipe, and this server is not Paper.");
        }
        anvilCount = anvil.size();
        stations.replace(smithing, anvil);
        return problems;
    }

    /** Removes every recipe this engine registered. */
    public void clear() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
        if (!brewing.isEmpty() && brewer != null) {
            brewer.ifPresent(paper -> brewing.forEach(paper::remove));
        }
        brewing.clear();
        anvilCount = 0;
        stations.clear();
    }

    /** How many are currently registered, anvil recipes included. */
    public int size() {
        return registered.size() + brewing.size() + anvilCount;
    }

    private NamespacedKey keyOf(ContentId id) {
        // The path carries the namespace too, because a NamespacedKey's own
        // namespace is this plugin and two packs may both define "ruby_block".
        return new NamespacedKey(plugin,
                (id.namespace() + "_" + id.path()).replace('/', '_').toLowerCase(Locale.ROOT));
    }

    /** An ingredient, or empty with the reason added to {@code problems}. */
    private Optional<RecipeItem> resolve(RecipeInfo info, String written, String role, List<String> problems) {
        Optional<RecipeItem> item = RecipeItem.resolve(written, items);
        if (item.isEmpty()) {
            problems.add(info.id() + ": its " + role + ", " + written + ", is not an item any pack defines, "
                    + "a vanilla material, or a potion like potion/awkward.");
        }
        return item;
    }

    private Optional<Recipe> build(RecipeInfo info, List<String> problems) {
        Optional<RecipeItem> result = resolve(info, info.result(), "result", problems);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        ItemStack made = result.get().stack(info.amount());
        NamespacedKey key = keyOf(info.id());

        switch (info.type()) {
            case SHAPED: {
                ShapedRecipe recipe = new ShapedRecipe(key, made);
                recipe.shape(info.rows().toArray(new String[0]));
                for (Map.Entry<String, String> entry : info.keys().entrySet()) {
                    Optional<RecipeItem> choice = resolve(info, entry.getValue(), "ingredient", problems);
                    if (choice.isEmpty()) {
                        return Optional.empty();
                    }
                    recipe.setIngredient(entry.getKey().charAt(0), choice.get().exact());
                }
                return Optional.of(recipe);
            }
            case SHAPELESS: {
                ShapelessRecipe recipe = new ShapelessRecipe(key, made);
                for (String ingredient : info.ingredients()) {
                    Optional<RecipeItem> choice = resolve(info, ingredient, "ingredient", problems);
                    if (choice.isEmpty()) {
                        return Optional.empty();
                    }
                    recipe.addIngredient(choice.get().exact());
                }
                return Optional.of(recipe);
            }
            case STONECUTTING: {
                return resolve(info, info.ingredients().get(0), "ingredient", problems)
                        .map(choice -> new StonecuttingRecipe(key, made, choice.exact()));
            }
            default: {
                Optional<RecipeItem> ingredient = resolve(info, info.ingredients().get(0), "ingredient", problems);
                if (ingredient.isEmpty()) {
                    return Optional.empty();
                }
                RecipeChoice choice = ingredient.get().exact();
                switch (info.type()) {
                    case BLASTING:
                        return Optional.of(new BlastingRecipe(
                                key, made, choice, info.experience(), info.cookingTime()));
                    case SMOKING:
                        return Optional.of(new SmokingRecipe(
                                key, made, choice, info.experience(), info.cookingTime()));
                    case CAMPFIRE:
                        return Optional.of(new CampfireRecipe(
                                key, made, choice, info.experience(), info.cookingTime()));
                    default:
                        return Optional.of(new FurnaceRecipe(
                                key, made, choice, info.experience(), info.cookingTime()));
                }
            }
        }
    }

    /**
     * A smithing recipe: registered with the server on loose choices, so the
     * table lets the items in, and resolved for {@link RecipeStations}, which
     * decides what comes out.
     */
    private Optional<RecipeStations.Smithing> smithing(RecipeInfo info, List<String> problems) {
        Optional<RecipeItem> template = resolve(info, info.template().orElse(""), "template", problems);
        Optional<RecipeItem> base = resolve(info, info.base().orElse(""), "base", problems);
        Optional<RecipeItem> addition = resolve(info, info.addition().orElse(""), "addition", problems);
        if (template.isEmpty() || base.isEmpty() || addition.isEmpty()) {
            return Optional.empty();
        }
        NamespacedKey key = keyOf(info.id());
        boolean templates = compatibility.has(Feature.SMITHING_TEMPLATES);

        if (info.type() == RecipeInfo.Type.SMITHING_TRIM) {
            if (!templates) {
                problems.add(info.id() + ": trim recipes need Minecraft "
                        + Feature.SMITHING_TEMPLATES.since() + ", where armour trims arrived.");
                return Optional.empty();
            }
            Optional<Recipe> trim = trim(info, key, template.get(), base.get(), addition.get(), problems);
            if (trim.isEmpty()) {
                return Optional.empty();
            }
            Bukkit.addRecipe(trim.get());
            return Optional.of(new RecipeStations.Smithing(info, template.get(), base.get(), addition.get(), null));
        }

        Optional<RecipeItem> result = resolve(info, info.result(), "result", problems);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        ItemStack made = result.get().stack(info.amount());
        Recipe recipe = templates
                ? new SmithingTransformRecipe(key, made,
                template.get().loose(), base.get().loose(), addition.get().loose())
                // The old two-slot table: the same upgrade, without a template.
                : legacySmithing(key, made, base.get(), addition.get());
        Bukkit.addRecipe(recipe);
        return Optional.of(new RecipeStations.Smithing(info, template.get(), base.get(), addition.get(),
                result.get()));
    }

    @SuppressWarnings("deprecation")
    private static Recipe legacySmithing(NamespacedKey key, ItemStack made, RecipeItem base, RecipeItem addition) {
        return new SmithingRecipe(key, made, base.loose(), addition.loose());
    }

    /**
     * A trim recipe, in whichever form this server takes.
     *
     * <p>Until 1.21.5 a trim's pattern came from its template item, so the
     * recipe names none, and a template that is not a vanilla trim template
     * trims nothing. From 1.21.5 the recipe names the pattern, and the
     * constructor that takes one is looked up rather than called, so the
     * engine still loads below it. A recipe there without a pattern borrows
     * the one its template stands for, when it is a vanilla template.
     */
    private Optional<Recipe> trim(RecipeInfo info, NamespacedKey key, RecipeItem template, RecipeItem base,
                                  RecipeItem addition, List<String> problems) {
        Constructor<SmithingTrimRecipe> withPattern = null;
        try {
            withPattern = SmithingTrimRecipe.class.getConstructor(NamespacedKey.class, RecipeChoice.class,
                    RecipeChoice.class, RecipeChoice.class, TrimPattern.class);
        } catch (NoSuchMethodException e) {
            // Below 1.21.5: the template decides the pattern.
        }
        if (withPattern == null) {
            return Optional.of(newTrim(key, template, base, addition));
        }
        String patternKey = info.pattern().orElseGet(() -> patternOf(template.material()));
        if (patternKey == null) {
            problems.add(info.id() + ": a trim recipe on this version needs a pattern (like "
                    + "minecraft:silence), because its template is not a vanilla trim template.");
            return Optional.empty();
        }
        NamespacedKey patternId = NamespacedKey.fromString(patternKey.toLowerCase(Locale.ROOT));
        TrimPattern pattern = patternId == null ? null : Registry.TRIM_PATTERN.get(patternId);
        if (pattern == null) {
            problems.add(info.id() + ": pattern " + patternKey + " is not a trim pattern this server has.");
            return Optional.empty();
        }
        try {
            return Optional.of(withPattern.newInstance(key, template.loose(), base.loose(), addition.loose(),
                    pattern));
        } catch (ReflectiveOperationException e) {
            problems.add(info.id() + ": the server refused the trim recipe (" + e.getMessage() + ").");
            return Optional.empty();
        }
    }

    @SuppressWarnings("deprecation")
    private static Recipe newTrim(NamespacedKey key, RecipeItem template, RecipeItem base, RecipeItem addition) {
        return new SmithingTrimRecipe(key, template.loose(), base.loose(), addition.loose());
    }

    /** The pattern a vanilla trim template stands for: SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE is minecraft:sentry. */
    static String patternOf(Material template) {
        String name = template.name();
        String suffix = "_ARMOR_TRIM_SMITHING_TEMPLATE";
        return name.endsWith(suffix)
                ? "minecraft:" + name.substring(0, name.length() - suffix.length()).toLowerCase(Locale.ROOT)
                : null;
    }

    private Optional<RecipeStations.Anvil> anvil(RecipeInfo info, List<String> problems) {
        Optional<RecipeItem> base = resolve(info, info.base().orElse(""), "base", problems);
        Optional<RecipeItem> addition = info.addition().isPresent()
                ? resolve(info, info.addition().get(), "addition", problems)
                : Optional.empty();
        if (base.isEmpty() || (info.addition().isPresent() && addition.isEmpty())) {
            return Optional.empty();
        }
        RecipeItem result = null;
        if (!info.isRepair()) {
            Optional<RecipeItem> made = resolve(info, info.result(), "result", problems);
            if (made.isEmpty()) {
                return Optional.empty();
            }
            result = made.get();
        }
        return Optional.of(new RecipeStations.Anvil(info, base.get(), addition.orElse(null), result));
    }

    /** @return false when brewing is not available here, so the caller can say so once */
    private boolean brew(RecipeInfo info, List<String> problems) {
        if (brewer == null) {
            brewer = PaperBrewing.find();
        }
        if (brewer.isEmpty()) {
            return false;
        }
        Optional<RecipeItem> base = resolve(info, info.base().orElse(""), "base", problems);
        Optional<RecipeItem> ingredient = resolve(info, info.ingredients().isEmpty() ? ""
                : info.ingredients().get(0), "ingredient", problems);
        Optional<RecipeItem> result = resolve(info, info.result(), "result", problems);
        if (base.isEmpty() || ingredient.isEmpty() || result.isEmpty()) {
            return true;
        }
        NamespacedKey key = keyOf(info.id());
        try {
            brewer.get().add(key, result.get().stack(info.amount()), base.get(), ingredient.get());
            brewing.add(key);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            problems.add(info.id() + ": the server refused the brewing recipe (" + cause.getMessage() + ").");
        }
        return true;
    }
}
