package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Translates the recipe files shared by Nexo and Oraxen into RP Engine recipes. */
final class NexoOraxenRecipe {
    private NexoOraxenRecipe() {
    }

    static boolean looksLikeOne(DefinitionNode document) {
        for (String id : document.keys()) {
            DefinitionNode recipe = document.node(id).orElse(DefinitionNode.empty());
            DefinitionNode result = recipe.node("result").orElse(DefinitionNode.empty());
            if (result.has("minecraft_type") || result.has("nexo_item") || result.has("oraxen_item")) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> translate(DefinitionNode document, String namespace, String recipeFile,
                                         String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = typeOf(recipeFile);
        if (type == null) {
            diagnostics.add(Diagnostic.warning(origin,
                    "This Nexo/Oraxen recipe file is not named shaped, shapeless, furnace, blasting, "
                            + "smoking, campfire, or stonecutting, so it was skipped."));
            return out;
        }
        for (String id : document.keys()) {
            DefinitionNode recipe = document.node(id).orElse(DefinitionNode.empty());
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

            if (type.equals("shaped")) {
                Map<String, Object> keys = new LinkedHashMap<>();
                DefinitionNode ingredients = recipe.node("ingredients").orElse(DefinitionNode.empty());
                for (String key : ingredients.keys()) {
                    ingredient(ingredients.node(key).orElse(DefinitionNode.empty()), namespace)
                            .ifPresent(value -> keys.put(key, value));
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
                for (String key : recipe.node("ingredients").orElse(DefinitionNode.empty()).keys()) {
                    ingredient(recipe.node("ingredients").orElse(DefinitionNode.empty())
                            .node(key).orElse(DefinitionNode.empty()), namespace).ifPresent(ingredients::add);
                }
                translated.put("ingredients", ingredients);
            } else {
                Optional<String> input = ingredient(recipe.node("input").orElse(DefinitionNode.empty()), namespace)
                        .or(() -> ingredient(recipe.node("ingredient").orElse(DefinitionNode.empty()), namespace));
                input.ifPresent(value -> translated.put("ingredient", value));
                recipe.integer("cookingTime").or(() -> recipe.integer("cooking_time"))
                        .ifPresent(time -> translated.put("time", time));
                recipe.string("experience").ifPresent(experience -> translated.put("experience", experience));
            }
            out.put(id, translated);
        }
        return out;
    }

    private static Optional<String> ingredient(DefinitionNode value, String namespace) {
        return value.string("minecraft_type")
                .or(() -> value.string("nexo_item").map(id -> qualified(id, namespace)))
                .or(() -> value.string("oraxen_item").map(id -> qualified(id, namespace)));
    }

    private static String qualified(String id, String namespace) {
        return id.contains(":") ? id : namespace + ":" + id;
    }

    private static String typeOf(String file) {
        String name = file.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (name.endsWith("shaped.yml") || name.endsWith("shaped.yaml")) return "shaped";
        if (name.endsWith("shapeless.yml") || name.endsWith("shapeless.yaml")) return "shapeless";
        if (name.endsWith("furnace.yml") || name.endsWith("furnace.yaml")) return "smelting";
        if (name.endsWith("blasting.yml") || name.endsWith("blasting.yaml")) return "blasting";
        if (name.endsWith("smoking.yml") || name.endsWith("smoking.yaml")) return "smoking";
        if (name.endsWith("campfire.yml") || name.endsWith("campfire.yaml")) return "campfire";
        if (name.endsWith("stonecutting.yml") || name.endsWith("stonecutting.yaml")) return "stonecutting";
        return null;
    }
}
