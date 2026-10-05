package ai.resourcepack.engine.core.recipe;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.RecipeInfo;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
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
 */
public final class Recipes {

    private final Plugin plugin;
    private final Items items;
    private final List<NamespacedKey> registered = new ArrayList<>();

    public Recipes(Plugin plugin, Items items) {
        this.plugin = plugin;
        this.items = items;
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
        for (RecipeInfo info : recipes.values()) {
            try {
                build(info).ifPresentOrElse(
                        recipe -> {
                            Bukkit.addRecipe(recipe);
                            registered.add(keyOf(info.id()));
                        },
                        () -> problems.add(info.id() + ": could not be built."));
            } catch (IllegalArgumentException | IllegalStateException e) {
                // A duplicate key, or an ingredient the server refused. Never
                // let one bad recipe stop the rest being registered.
                problems.add(info.id() + ": " + e.getMessage());
            }
        }
        return problems;
    }

    /** Removes every recipe this engine registered. */
    public void clear() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
    }

    /** How many are currently registered. */
    public int size() {
        return registered.size();
    }

    private NamespacedKey keyOf(ContentId id) {
        // The path carries the namespace too, because a NamespacedKey's own
        // namespace is this plugin and two packs may both define "ruby_block".
        return new NamespacedKey(plugin,
                (id.namespace() + "_" + id.path()).replace('/', '_').toLowerCase(Locale.ROOT));
    }

    private Optional<Recipe> build(RecipeInfo info) {
        Optional<ItemStack> result = stackOf(info.result());
        if (result.isEmpty()) {
            return Optional.empty();
        }
        ItemStack made = result.get();
        made.setAmount(info.amount());
        NamespacedKey key = keyOf(info.id());

        switch (info.type()) {
            case SHAPED: {
                ShapedRecipe recipe = new ShapedRecipe(key, made);
                recipe.shape(info.rows().toArray(new String[0]));
                for (Map.Entry<String, String> entry : info.keys().entrySet()) {
                    Optional<RecipeChoice> choice = choiceOf(entry.getValue());
                    if (choice.isEmpty()) {
                        return Optional.empty();
                    }
                    recipe.setIngredient(entry.getKey().charAt(0), choice.get());
                }
                return Optional.of(recipe);
            }
            case SHAPELESS: {
                ShapelessRecipe recipe = new ShapelessRecipe(key, made);
                for (String ingredient : info.ingredients()) {
                    Optional<RecipeChoice> choice = choiceOf(ingredient);
                    if (choice.isEmpty()) {
                        return Optional.empty();
                    }
                    recipe.addIngredient(choice.get());
                }
                return Optional.of(recipe);
            }
            case STONECUTTING: {
                return choiceOf(info.ingredients().get(0))
                        .map(choice -> new StonecuttingRecipe(key, made, choice));
            }
            default: {
                Optional<RecipeChoice> choice = choiceOf(info.ingredients().get(0));
                if (choice.isEmpty()) {
                    return Optional.empty();
                }
                switch (info.type()) {
                    case BLASTING:
                        return Optional.of(new BlastingRecipe(
                                key, made, choice.get(), info.experience(), info.cookingTime()));
                    case SMOKING:
                        return Optional.of(new SmokingRecipe(
                                key, made, choice.get(), info.experience(), info.cookingTime()));
                    case CAMPFIRE:
                        return Optional.of(new CampfireRecipe(
                                key, made, choice.get(), info.experience(), info.cookingTime()));
                    default:
                        return Optional.of(new FurnaceRecipe(
                                key, made, choice.get(), info.experience(), info.cookingTime()));
                }
            }
        }
    }

    /**
     * An ingredient, as something the server can match against.
     *
     * <p>A custom item becomes an {@code ExactChoice}, which matches the whole
     * stack including its persistent data — so a recipe calling for
     * {@code mypack:ruby} cannot be satisfied with an ordinary diamond that
     * happens to look like one. A vanilla material becomes a
     * {@code MaterialChoice}, which is the looser match everybody expects from
     * a vanilla ingredient.
     */
    private Optional<RecipeChoice> choiceOf(String ingredient) {
        Optional<ContentId> id = ContentId.parse(ingredient);
        if (id.isPresent()) {
            return items.create(id.get()).map(RecipeChoice.ExactChoice::new);
        }
        try {
            return Optional.of(new RecipeChoice.MaterialChoice(
                    Material.valueOf(ingredient.trim().toUpperCase(Locale.ROOT))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A result, as a stack. */
    private Optional<ItemStack> stackOf(String result) {
        Optional<ContentId> id = ContentId.parse(result);
        if (id.isPresent()) {
            return items.create(id.get());
        }
        try {
            return Optional.of(new ItemStack(Material.valueOf(result.trim().toUpperCase(Locale.ROOT))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
