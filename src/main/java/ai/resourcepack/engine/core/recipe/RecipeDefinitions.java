package ai.resourcepack.engine.core.recipe;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.RecipeInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads recipe definitions.
 *
 * <p>Free of Bukkit, which matters more here than usual: a recipe is fiddly
 * data — a pattern, a key map, the arithmetic of how many rows and columns —
 * and all of it can be got wrong in ways that produce a recipe nobody can
 * craft. Every one of those checks is a test.
 */
public final class RecipeDefinitions {

    /** A crafting grid is three by three, and a pattern cannot exceed it. */
    private static final int MAX_ROWS = 3;
    private static final int MAX_COLUMNS = 3;

    private RecipeDefinitions() {
    }

    /** Everything of kind RECIPE in {@code loaded}, parsed. */
    public static Result parse(LoadReport loaded) {
        Map<ContentId, RecipeInfo> recipes = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.RECIPE)) {
            parseOne(definition, diagnostics).ifPresent(recipe -> recipes.put(recipe.id(), recipe));
        }
        return new Result(Map.copyOf(recipes), List.copyOf(diagnostics));
    }

    private static Optional<RecipeInfo> parseOne(ContentDefinition definition, List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        RecipeInfo.Type type;
        String declared = body.string("type").orElse("shaped").trim().toUpperCase(Locale.ROOT);
        try {
            type = RecipeInfo.Type.valueOf(declared);
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "type: " + declared.toLowerCase(Locale.ROOT) + " is not a kind of recipe. One of: "
                            + types() + "."));
            return Optional.empty();
        }

        Optional<String> result = body.string("result");
        if (result.isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "No result. A recipe has to make something - a content id, or a vanilla "
                            + "material like DIAMOND."));
            return Optional.empty();
        }

        int amount = body.integer("amount").orElse(1);
        if (amount < 1 || amount > 64) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "amount: " + amount + " is outside 1 to 64. Using 1."));
            amount = 1;
        }

        List<String> rows = List.of();
        Map<String, String> keys = Map.of();
        List<String> ingredients = List.of();

        if (type == RecipeInfo.Type.SHAPED) {
            rows = body.strings("pattern");
            if (rows.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "A shaped recipe needs a pattern: up to three rows of up to three characters, "
                                + "with a space for an empty slot."));
                return Optional.empty();
            }
            if (rows.size() > MAX_ROWS) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "A pattern is at most " + MAX_ROWS + " rows; this has " + rows.size() + "."));
                return Optional.empty();
            }
            for (String row : rows) {
                if (row.length() > MAX_COLUMNS) {
                    diagnostics.add(Diagnostic.error(origin, where,
                            "The row \"" + row + "\" is " + row.length() + " wide; a crafting grid is "
                                    + MAX_COLUMNS + "."));
                    return Optional.empty();
                }
            }

            Optional<DefinitionNode> declaredKeys = body.node("keys");
            Map<String, String> parsedKeys = new LinkedHashMap<>();
            if (declaredKeys.isPresent()) {
                for (String key : declaredKeys.get().keys()) {
                    declaredKeys.get().string(key)
                            .ifPresent(value -> parsedKeys.put(key, value));
                }
            }
            keys = Map.copyOf(parsedKeys);

            // Every character in the pattern has to stand for something, or
            // the recipe is one nobody can craft and nothing in game says why.
            for (String row : rows) {
                for (int i = 0; i < row.length(); i++) {
                    String character = String.valueOf(row.charAt(i));
                    if (!character.equals(" ") && !keys.containsKey(character)) {
                        diagnostics.add(Diagnostic.error(origin, where,
                                "The pattern uses '" + character + "' and keys does not say what that is. "
                                        + "A space means an empty slot."));
                        return Optional.empty();
                    }
                }
            }
        } else {
            ingredients = body.strings("ingredients");
            if (ingredients.isEmpty()) {
                Optional<String> single = body.string("ingredient");
                if (single.isPresent()) {
                    ingredients = List.of(single.get());
                }
            }
            if (ingredients.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "Nothing to make it from. Give it an ingredient, or a list of ingredients."));
                return Optional.empty();
            }
            if (type != RecipeInfo.Type.SHAPELESS && ingredients.size() > 1) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "A " + declared.toLowerCase(Locale.ROOT) + " recipe takes one ingredient; "
                                + "using the first and ignoring " + (ingredients.size() - 1) + "."));
                ingredients = List.of(ingredients.get(0));
            }
            if (type == RecipeInfo.Type.SHAPELESS && ingredients.size() > 9) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "A crafting grid holds nine ingredients; this has " + ingredients.size() + "."));
                return Optional.empty();
            }
        }

        float experience = 0f;
        int cookingTime = 200;
        if (type == RecipeInfo.Type.SMELTING || type == RecipeInfo.Type.BLASTING
                || type == RecipeInfo.Type.SMOKING || type == RecipeInfo.Type.CAMPFIRE) {
            experience = body.string("experience").map(RecipeDefinitions::number).orElse(0f);
            // Vanilla's own defaults: a furnace is ten seconds, and the fast
            // ones are half that. Getting this wrong is not an error, it is a
            // recipe that feels wrong to play, which nobody reports.
            int fallback = type == RecipeInfo.Type.BLASTING || type == RecipeInfo.Type.SMOKING ? 100 : 200;
            cookingTime = body.integer("time").orElse(fallback);
            if (cookingTime < 1) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "time: " + cookingTime + " is not a number of ticks. Using " + fallback + "."));
                cookingTime = fallback;
            }
        }

        return Optional.of(RecipeInfo.of(definition.id(), type, result.get(), amount,
                rows, keys, ingredients, experience, cookingTime));
    }

    private static float number(String text) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static String types() {
        List<String> names = new ArrayList<>();
        for (RecipeInfo.Type type : RecipeInfo.Type.values()) {
            names.add(type.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }

    /** The recipes, and what was wrong with the ones that are missing. */
    public static final class Result {

        private final Map<ContentId, RecipeInfo> recipes;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, RecipeInfo> recipes, List<Diagnostic> diagnostics) {
            this.recipes = recipes;
            this.diagnostics = diagnostics;
        }

        /** Every recipe that parsed, keyed by id. */
        public Map<ContentId, RecipeInfo> recipes() {
            return recipes;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
