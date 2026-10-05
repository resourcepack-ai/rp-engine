package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Translates the recipe files shared by Nexo and Oraxen into RP Engine recipes.
 *
 * <p>Neither plugin writes the recipe type inside the recipe: it is where the
 * file is. Oraxen has fixed file names ({@code recipes/shaped.yml},
 * {@code furnace.yml}, ...) and Nexo a folder per type
 * ({@code recipes/shaped/anything.yml}, its builder writing
 * {@code shaped_recipes.yml} inside). Both are read off the path relative to
 * the {@code recipes/} folder.
 *
 * <p>An ingredient is {@code minecraft_type}, {@code nexo_item} or
 * {@code oraxen_item}. Anything else - a tag, another plugin's item - has no
 * RP Engine equivalent, and a recipe missing one of its ingredients would be
 * a different recipe, so the whole recipe is skipped and named.
 */
final class NexoOraxenRecipe {
    private NexoOraxenRecipe() {
    }

    /** The recipe kinds both plugins have and RP Engine does not, which are skipped by name. */
    private static final List<String> UNSUPPORTED =
            List.of("cauldron", "grindstone", "crafter", "predicate", "transmute");

    /** The slots a recipe of theirs can name an item in; a trim and a repair have no result. */
    private static final List<String> SLOTS =
            List.of("result", "input", "base", "template", "ingredient", "material");

    static boolean looksLikeOne(DefinitionNode document) {
        for (String id : document.keys()) {
            DefinitionNode recipe = document.node(id).orElse(DefinitionNode.empty());
            for (String slot : SLOTS) {
                DefinitionNode value = recipe.node(slot).orElse(DefinitionNode.empty());
                if (value.has("minecraft_type") || value.has("nexo_item") || value.has("oraxen_item")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param recipeFile the file's path relative to the {@code recipes/}
     *                   folder, which is where its type is written
     */
    static Map<String, Object> translate(DefinitionNode document, String namespace, String recipeFile,
                                         String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = typeOf(recipeFile);
        if (type == null) {
            diagnostics.add(Diagnostic.warning(origin,
                    "This Nexo/Oraxen recipe file is not named, or in a folder named, shaped, shapeless, furnace, "
                            + "blasting, smoking, campfire, stonecutting, smithing, brewing or anvil, so it was "
                            + "skipped."));
            return out;
        }
        if (UNSUPPORTED.contains(type)) {
            diagnostics.add(Diagnostic.warning(origin,
                    document.keys().size() + " " + type + " recipes were skipped: RP Engine has no " + type
                            + " recipes."));
            return out;
        }
        for (String id : document.keys()) {
            DefinitionNode recipe = document.node(id).orElse(DefinitionNode.empty());
            if (type.equals("smithing") || type.equals("brewing") || type.equals("anvil")) {
                Map<String, Object> translated = station(type, recipe, id, namespace, origin, diagnostics);
                if (translated != null) out.put(id, translated);
                continue;
            }
            Optional<String> result = ingredient(recipe.node("result").orElse(DefinitionNode.empty()), namespace);
            if (result.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "Its result is not a minecraft_type, nexo_item, or oraxen_item, so the recipe was skipped."));
                continue;
            }
            Map<String, Object> translated = new LinkedHashMap<>();
            translated.put("type", type);
            translated.put("result", result.get());
            recipe.node("result").flatMap(value -> value.integer("amount"))
                    .ifPresent(amount -> translated.put("amount", amount));

            List<String> unreadable = new ArrayList<>();
            if (type.equals("shaped")) {
                Map<String, Object> keys = new LinkedHashMap<>();
                DefinitionNode ingredients = recipe.node("ingredients").orElse(DefinitionNode.empty());
                for (String key : ingredients.keys()) {
                    DefinitionNode value = ingredients.node(key).orElse(DefinitionNode.empty());
                    Optional<String> item = ingredient(value, namespace);
                    if (item.isEmpty()) {
                        unreadable.add(key);
                        continue;
                    }
                    keys.put(key, item.get());
                    if (value.integer("amount").orElse(1) > 1) {
                        diagnostics.add(Diagnostic.warning(origin, id,
                                "ingredient " + key + " asks for a stack of " + value.integer("amount").get()
                                        + " in its slot. RP Engine takes one per slot."));
                    }
                }
                List<Object> pattern = new ArrayList<>();
                for (String row : recipe.strings("shape")) {
                    StringBuilder converted = new StringBuilder();
                    for (char character : row.toCharArray()) {
                        // Their underscore is our empty crafting slot.
                        converted.append(character == '_' ? ' ' : character);
                    }
                    pattern.add(converted.toString());
                }
                translated.put("keys", keys);
                translated.put("pattern", pattern);
            } else if (type.equals("shapeless")) {
                List<Object> ingredients = new ArrayList<>();
                DefinitionNode declared = recipe.node("ingredients").orElse(DefinitionNode.empty());
                for (String key : declared.keys()) {
                    DefinitionNode value = declared.node(key).orElse(DefinitionNode.empty());
                    Optional<String> item = ingredient(value, namespace);
                    if (item.isEmpty()) {
                        unreadable.add(key);
                        continue;
                    }
                    // An amount on a shapeless ingredient is that many of it in
                    // the grid, which ours writes as that many entries.
                    for (int i = 0; i < Math.max(1, value.integer("amount").orElse(1)); i++) {
                        ingredients.add(item.get());
                    }
                }
                translated.put("ingredients", ingredients);
            } else {
                DefinitionNode input = recipe.node("input").or(() -> recipe.node("ingredient"))
                        .orElse(DefinitionNode.empty());
                Optional<String> item = ingredient(input, namespace);
                if (item.isPresent()) translated.put("ingredient", item.get());
                else unreadable.add("input");
                recipe.integer("cookingTime").or(() -> recipe.integer("cooking_time"))
                        .ifPresent(time -> translated.put("time", time));
                recipe.string("experience").ifPresent(experience -> translated.put("experience", experience));
            }
            if (!unreadable.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "ingredient " + String.join(", ", unreadable) + " is not a minecraft_type, nexo_item or "
                                + "oraxen_item, so the recipe was skipped rather than made without it."));
                continue;
            }
            out.put(id, translated);
        }
        return out;
    }

    /**
     * A smithing, brewing or anvil recipe, which name their slots rather than
     * filling a grid.
     *
     * <p>The two plugins agree on smithing ({@code template}, {@code base},
     * {@code addition}, and Nexo's {@code trim_pattern} for a trim) and only
     * Nexo brews ({@code input} in the bottles, {@code ingredient} on top).
     * Their anvils differ: Oraxen's is {@code base}, an optional
     * {@code addition} and {@code experience_cost}; Nexo's is {@code input},
     * {@code material} and {@code cost}, and with no result it repairs the
     * input by {@code repair} durability points.
     *
     * @return the recipe, or null when it was skipped (and said why)
     */
    private static Map<String, Object> station(String type, DefinitionNode recipe, String id, String namespace,
                                               String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        Optional<String> result = ingredient(recipe.node("result").orElse(DefinitionNode.empty()), namespace);
        if (type.equals("smithing")) {
            Optional<String> pattern = recipe.string("trim_pattern");
            out.put("type", pattern.isPresent() ? "smithing_trim" : "smithing");
            slot(recipe, "template", "template", namespace, out, unreadable);
            slot(recipe, "base", "base", namespace, out, unreadable);
            slot(recipe, "addition", "addition", namespace, out, unreadable);
            if (pattern.isPresent()) {
                out.put("pattern", pattern.get());
            } else if (result.isPresent()) {
                out.put("result", result.get());
                if (!recipe.bool("copy_components").orElse(Boolean.TRUE)) out.put("copy-data", false);
            } else {
                unreadable.add("result");
            }
        } else if (type.equals("brewing")) {
            out.put("type", "brewing");
            slot(recipe, "input", "base", namespace, out, unreadable);
            slot(recipe, "ingredient", "ingredient", namespace, out, unreadable);
            if (result.isPresent()) out.put("result", result.get());
            else unreadable.add("result");
        } else {
            out.put("type", "anvil");
            boolean nexo = recipe.has("input") || recipe.has("material");
            slot(recipe, nexo ? "input" : "base", "base", namespace, out, unreadable);
            String additionSlot = nexo ? "material" : "addition";
            if (recipe.has(additionSlot)) {
                slot(recipe, additionSlot, "addition", namespace, out, unreadable);
                int amount = recipe.node(additionSlot).flatMap(value -> value.integer("amount")).orElse(1);
                if (amount > 1) out.put("addition-amount", amount);
            }
            int baseAmount = recipe.node(nexo ? "input" : "base").flatMap(value -> value.integer("amount")).orElse(1);
            if (baseAmount > 1) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "asks for " + baseAmount + " of its first input. An RP Engine anvil recipe uses one."));
            }
            // Oraxen charges nothing unless told to; Nexo charges a level.
            out.put("cost", nexo ? recipe.integer("cost").orElse(1) : recipe.integer("experience_cost").orElse(0));
            int repair = recipe.integer("repair").orElse(0);
            if (result.isPresent()) {
                out.put("result", result.get());
            } else if (nexo && repair > 0) {
                out.put("repair", repair);
            } else {
                unreadable.add("result");
            }
        }
        if (!unreadable.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    String.join(", ", unreadable) + (unreadable.size() == 1 ? " is" : " are") + " missing, or not "
                            + "a minecraft_type, nexo_item or oraxen_item, so the recipe was skipped rather than "
                            + "made without it."));
            return null;
        }
        return out;
    }

    /** One named slot, copied across as {@code ours}, or named in {@code unreadable}. */
    private static void slot(DefinitionNode recipe, String theirs, String ours, String namespace,
                             Map<String, Object> out, List<String> unreadable) {
        Optional<String> item = ingredient(recipe.node(theirs).orElse(DefinitionNode.empty()), namespace);
        if (item.isPresent()) out.put(ours, item.get());
        else unreadable.add(theirs);
    }

    private static Optional<String> ingredient(DefinitionNode value, String namespace) {
        return value.string("minecraft_type")
                .or(() -> value.string("nexo_item").map(id -> qualified(id, namespace)))
                .or(() -> value.string("oraxen_item").map(id -> qualified(id, namespace)));
    }

    private static String qualified(String id, String namespace) {
        return id.contains(":") ? id : namespace + ":" + id;
    }

    /**
     * The type a recipe file's path names: its own name first, then the
     * folders it is in, nearest first. {@code shaped_recipes} is the name
     * Nexo's in-game builder writes.
     */
    static String typeOf(String file) {
        String path = file.toLowerCase(Locale.ROOT).replace('\\', '/');
        int dot = path.lastIndexOf('.');
        if (dot > path.lastIndexOf('/')) path = path.substring(0, dot);
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String name = segments[i].endsWith("_recipes")
                    ? segments[i].substring(0, segments[i].length() - "_recipes".length()) : segments[i];
            switch (name) {
                case "shaped":
                case "shapeless":
                case "blasting":
                case "smoking":
                case "campfire":
                    return name;
                case "furnace":
                case "smelting":
                    return "smelting";
                case "stonecutting":
                case "stonecutter":
                    return "stonecutting";
                case "smithing":
                case "brewing":
                case "anvil":
                    return name;
                case "brewing_stand":
                    return "brewing";
                default:
                    if (UNSUPPORTED.contains(name)) return name;
            }
        }
        return null;
    }
}
