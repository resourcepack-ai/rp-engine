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
        String declared = body.string("type").orElse("shaped").trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            type = RecipeInfo.Type.valueOf(declared);
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "type: " + declared.toLowerCase(Locale.ROOT) + " is not a kind of recipe. One of: "
                            + types() + "."));
            return Optional.empty();
        }

        switch (type) {
            case SMITHING:
            case SMITHING_TRIM:
            case BREWING:
            case ANVIL:
                return station(definition, type, diagnostics);
            default:
                break;
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

    /**
     * A recipe made at a smithing table, a brewing stand or an anvil: named
     * slots rather than a grid, which is why it is parsed apart from the rest.
     */
    private static Optional<RecipeInfo> station(ContentDefinition definition, RecipeInfo.Type type,
                                                List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();
        String name = type.name().toLowerCase(Locale.ROOT);

        Optional<String> template = body.string("template");
        // A brewing stand's bottle slot is what the other two call the base,
        // and "input" is what most people coming from elsewhere call it.
        Optional<String> base = body.string("base").or(() -> body.string("input"));
        Optional<String> addition = body.string("addition");
        Optional<String> ingredient = body.string("ingredient");
        Optional<String> result = body.string("result");

        List<String> missing = new ArrayList<>();
        if (type == RecipeInfo.Type.SMITHING || type == RecipeInfo.Type.SMITHING_TRIM) {
            if (template.isEmpty()) missing.add("template");
            if (base.isEmpty()) missing.add("base");
            if (addition.isEmpty()) missing.add("addition");
            if (type == RecipeInfo.Type.SMITHING && result.isEmpty()) missing.add("result");
        } else if (type == RecipeInfo.Type.BREWING) {
            if (base.isEmpty()) missing.add("base");
            if (ingredient.isEmpty()) missing.add("ingredient");
            if (result.isEmpty()) missing.add("result");
        } else if (base.isEmpty()) {
            missing.add("base");
        }
        if (!missing.isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "A " + name + " recipe needs " + String.join(", ", missing) + ". "
                            + slotsOf(type)));
            return Optional.empty();
        }

        int amount = body.integer("amount").orElse(1);
        if (amount < 1 || amount > 64) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "amount: " + amount + " is outside 1 to 64. Using 1."));
            amount = 1;
        }

        String made = result.orElse("");
        if (type == RecipeInfo.Type.SMITHING_TRIM && result.isPresent()) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "A trim recipe has no result: what comes out is the base, trimmed. result was ignored."));
            made = "";
        }

        int additionAmount = 1;
        int cost = 0;
        int repairPoints = 0;
        float repairFraction = 0f;
        if (type == RecipeInfo.Type.ANVIL) {
            additionAmount = body.integer("addition-amount").orElse(1);
            if (additionAmount < 1 || additionAmount > 64) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "addition-amount: " + additionAmount + " is outside 1 to 64. Using 1."));
                additionAmount = 1;
            }
            cost = body.integer("cost").orElse(1);
            if (cost < 0) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "cost: " + cost + " is not a number of levels. Using 0."));
                cost = 0;
            } else if (cost >= 40) {
                // Vanilla's own ceiling: at 40 a survival player is told
                // "Too Expensive!" and cannot take the result at all.
                diagnostics.add(Diagnostic.warning(origin, where,
                        "cost: " + cost + " is 40 or more, which a survival player cannot pay at an anvil."));
            }
            Optional<String> repair = body.string("repair");
            if (repair.isPresent() && result.isPresent()) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "An anvil recipe either makes a result or repairs its base, not both. "
                                + "Remove result or repair."));
                return Optional.empty();
            }
            if (repair.isEmpty() && result.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "An anvil recipe needs a result, or repair: to mend its base - a number of "
                                + "durability points, or a percentage like 25%."));
                return Optional.empty();
            }
            if (repair.isPresent()) {
                if (addition.isEmpty()) {
                    diagnostics.add(Diagnostic.error(origin, where,
                            "A repair needs an addition: the material it is mended with."));
                    return Optional.empty();
                }
                String text = repair.get().trim();
                try {
                    if (text.endsWith("%")) {
                        repairFraction = Float.parseFloat(text.substring(0, text.length() - 1).trim()) / 100f;
                    } else {
                        repairPoints = Integer.parseInt(text);
                    }
                } catch (NumberFormatException e) {
                    repairFraction = 0f;
                    repairPoints = 0;
                }
                if (repairFraction <= 0f && repairPoints <= 0) {
                    diagnostics.add(Diagnostic.error(origin, where,
                            "repair: " + text + " is not a number of durability points or a percentage "
                                    + "like 25%."));
                    return Optional.empty();
                }
                repairFraction = Math.min(1f, repairFraction);
            }
        } else {
            for (String key : List.of("cost", "repair", "addition-amount")) {
                if (body.has(key)) {
                    diagnostics.add(Diagnostic.warning(origin, where,
                            key + " is for an anvil recipe and does nothing on a " + name + " recipe."));
                }
            }
        }

        boolean copyData = body.bool("copy-data").orElse(Boolean.TRUE);
        RecipeInfo.Stations stations = RecipeInfo.Stations.of(
                template.orElse(null), base.orElse(null), addition.orElse(null), copyData,
                type == RecipeInfo.Type.SMITHING_TRIM ? body.string("pattern").orElse(null) : null,
                additionAmount, cost, repairPoints, repairFraction);
        List<String> ingredients = ingredient.map(List::of).orElse(List.of());
        return Optional.of(RecipeInfo.of(definition.id(), type, made, amount,
                List.of(), Map.of(), ingredients, 0f, 0, stations));
    }

    /** What each station recipe is made of, for the message when part of it is missing. */
    private static String slotsOf(RecipeInfo.Type type) {
        switch (type) {
            case SMITHING:
                return "A smithing table takes a template, a base and an addition, and makes the result.";
            case SMITHING_TRIM:
                return "A smithing table takes a template, a base and an addition, and trims the base.";
            case BREWING:
                return "A brewing stand brews the base (the bottle slots) with the ingredient (the top slot) "
                        + "into the result.";
            default:
                return "An anvil takes a base on the left and, optionally, an addition on the right.";
        }
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
