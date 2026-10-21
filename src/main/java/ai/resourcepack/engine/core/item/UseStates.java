package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.DefinitionNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code pull:}, {@code charged:} and {@code charged-firework:}: the looks an
 * item takes while it is being used, which is how a bow is drawn and a
 * crossbow loaded.
 *
 * <pre>
 * longbow:
 *   material: BOW
 *   model: longbow
 *   pull: [longbow_pull_0, longbow_pull_1, longbow_pull_2]
 * </pre>
 *
 * <p>The client does all of it. Vanilla's own bow is an item definition that
 * asks whether the item is being used and for how long, and a custom item's
 * definition can ask the same questions, so nothing is sent per tick and
 * everybody watching sees the same draw. Below 1.21.4 the same looks are
 * predicates on the base material's model, which only a bow and a crossbow
 * have — see {@link #legacyPredicates}.
 *
 * <p>Like {@code first-person:} it is a build instruction rather than a
 * property of the item, so it is read off the definition here and never
 * carried on {@code ItemInfo}.
 */
final class UseStates {

    /**
     * Vanilla's own thresholds, so a three-state bow drawn by somebody who never
     * wrote an {@code at:} feels exactly like a vanilla one. A bow's is seconds
     * held (fully drawn at one), a crossbow's the fraction of its charge time.
     */
    private static final double[] BOW = {0, 0.65, 0.9};
    private static final double[] CROSSBOW = {0, 0.58, 1.0};

    /** One look: a model or a sprite, and for a pull state where it starts. */
    static final class Look {

        /** Null when the author left it to the defaults. */
        final Double at;
        /** A model name, resource location or inline JSON; null for a sprite. */
        final String model;
        /** A texture for a flat look; null for a model. */
        final String texture;

        Look(Double at, String model, String texture) {
            this.at = at;
            this.model = model;
            this.texture = texture;
        }
    }

    final List<Look> pull;
    final Look charged;
    final Look firework;

    private UseStates(List<Look> pull, Look charged, Look firework) {
        this.pull = pull;
        this.charged = charged;
        this.firework = firework;
    }

    /**
     * Reads the three keys off an item's definition.
     *
     * @param problems where anything unusable is described, one line each,
     *                 for the caller to report against the item
     * @return null when the item declares none of them
     */
    static UseStates read(DefinitionNode body, List<String> problems) {
        // A bare name is the same kind of thing as the item's own look: a model
        // on an item with a model:, a texture on a flat one. That is what an
        // author means by "the same, but drawn back" nearly every time.
        boolean modelled = body.raw("model") != null;
        List<Look> pull = new ArrayList<>();
        Object raw = body.raw("pull");
        if (raw instanceof List<?> entries) {
            for (Object entry : entries) {
                Look look = look(entry, modelled, "pull", problems);
                if (look != null) {
                    pull.add(look);
                }
            }
        } else if (raw != null) {
            problems.add("pull: is a list, one entry per state, the first shown the moment drawing starts.");
        }
        Look charged = look(body.raw("charged"), modelled, "charged", problems);
        Look firework = look(body.raw("charged-firework"), modelled, "charged-firework", problems);
        if (pull.isEmpty() && charged == null && firework == null) {
            return null;
        }
        return new UseStates(Collections.unmodifiableList(pull), charged, firework);
    }

    private static Look look(Object raw, boolean modelled, String key, List<String> problems) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> map) {
            DefinitionNode node = DefinitionNode.of(map);
            if (!node.has("at") && !node.has("model") && !node.has("texture")) {
                // A whole model written inline, as model: allows.
                return new Look(null, ItemDefinitions.model(DefinitionNode.of(Map.of("model", map))), null);
            }
            Double at = null;
            if (node.has("at")) {
                at = node.decimal("at").orElse(null);
                if (at == null || at < 0 || at.isNaN() || at.isInfinite()) {
                    problems.add(key + ": at: " + node.raw("at") + " is not a number of 0 or more. "
                            + "The state was left out.");
                    return null;
                }
                if (!key.equals("pull")) {
                    problems.add(key + ": at: means nothing here, only on a pull state. It was ignored.");
                    at = null;
                }
            }
            String model = node.has("model") ? ItemDefinitions.model(node) : null;
            String texture = node.string("texture").map(UseStates::texture).orElse(null);
            if ((model == null) == (texture == null)) {
                problems.add(key + ": each state is a model: or a texture:, exactly one. The state was left out.");
                return null;
            }
            return new Look(at, model, texture);
        }
        String name = String.valueOf(raw).trim();
        if (name.isEmpty() || raw instanceof List) {
            problems.add(key + ": " + raw + " is not a model or a texture. The state was left out.");
            return null;
        }
        return modelled ? new Look(null, name, null) : new Look(null, null, texture(name));
    }

    /**
     * A texture as {@code texture:} takes one, except that a bare file name is
     * looked for in {@code textures/item/}, beside the item's own default:
     * {@code longbow_pull_0} beside {@code longbow}.
     */
    private static String texture(String name) {
        String trimmed = name.trim();
        if (trimmed.endsWith(".png")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        return trimmed.contains("/") || trimmed.contains(":") ? trimmed : "item/" + trimmed;
    }

    /** Whether a crossbow's charged looks can mean anything on this material. */
    static boolean chargeable(String material) {
        return material.toUpperCase(Locale.ROOT).equals("CROSSBOW");
    }

    /** Whether the material has the pull predicates below 1.21.4. */
    static boolean legacyPullable(String material) {
        String upper = material.toUpperCase(Locale.ROOT);
        return upper.equals("BOW") || upper.equals("CROSSBOW");
    }

    /**
     * Where each pull state starts: the author's {@code at:} where given,
     * otherwise vanilla's for a three-state bow or crossbow and an even spread
     * for anything else.
     *
     * <p>The first state is shown from the moment drawing starts whatever its
     * number says, because that is the only thing a first state can mean.
     */
    static double[] thresholds(String material, List<Look> pull) {
        String upper = material.toUpperCase(Locale.ROOT);
        double[] vanilla = pull.size() == 3 && upper.equals("BOW") ? BOW
                : pull.size() == 3 && upper.equals("CROSSBOW") ? CROSSBOW : null;
        double[] out = new double[pull.size()];
        for (int i = 0; i < out.length; i++) {
            Double at = pull.get(i).at;
            out[i] = at != null ? at : vanilla != null ? vanilla[i] : (double) i / out.length;
        }
        return out;
    }

    /**
     * The item definition with the use states wrapped round {@code base}.
     *
     * @param pullRefs     a model reference per pull state, in threshold order
     * @param at           each state's threshold, the same length
     * @param chargedRef   the loaded-with-an-arrow model, or null
     * @param fireworkRef  the loaded-with-a-rocket model, or null
     */
    static String definition(String base, String material, List<String> pullRefs, double[] at,
                             String chargedRef, String fireworkRef) {
        String out = base;
        if (!pullRefs.isEmpty()) {
            // A crossbow's own property is the fraction of ITS charge time, so
            // Quick Charge draws the states faster just as it does vanilla's.
            // Everything else counts seconds the way vanilla's bow does.
            String property = chargeable(material)
                    ? "\"property\":\"minecraft:crossbow/pull\""
                    : "\"property\":\"minecraft:use_duration\",\"scale\":0.05";
            StringBuilder pulled = new StringBuilder("{\"type\":\"minecraft:range_dispatch\",")
                    .append(property).append(",\"entries\":[");
            for (int i = 1; i < pullRefs.size(); i++) {
                if (i > 1) {
                    pulled.append(',');
                }
                pulled.append("{\"threshold\":").append(number(at[i])).append(",\"model\":")
                        .append(model(pullRefs.get(i))).append('}');
            }
            pulled.append("],\"fallback\":").append(model(pullRefs.get(0))).append('}');
            out = "{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                    + "\"on_true\":" + pulled + ",\"on_false\":" + out + "}";
        }
        if (chargedRef != null || fireworkRef != null) {
            List<String> cases = new ArrayList<>();
            if (chargedRef != null) {
                cases.add("{\"when\":\"arrow\",\"model\":" + model(chargedRef) + "}");
            }
            // A crossbow given one loaded look shows it for a rocket too, rather
            // than dropping back to unloaded with a firework on the string.
            String rocket = fireworkRef != null ? fireworkRef : chargedRef;
            cases.add("{\"when\":\"rocket\",\"model\":" + model(rocket) + "}");
            out = "{\"type\":\"minecraft:select\",\"property\":\"minecraft:charge_type\","
                    + "\"cases\":[" + String.join(",", cases) + "],\"fallback\":" + out + "}";
        }
        return out;
    }

    /**
     * The predicates below 1.21.4, after an item's own
     * {@code custom_model_data} one, each paired with a model reference. The
     * client takes the LAST predicate that matches, so they are in the order
     * a draw passes through them: pulling, further pulled, and loaded.
     */
    static List<String[]> legacyPredicates(List<String> pullRefs, double[] at, String chargedRef,
                                           String fireworkRef) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < pullRefs.size(); i++) {
            String predicate = i == 0 ? "\"pulling\": 1" : "\"pulling\": 1, \"pull\": " + number(at[i]);
            out.add(new String[] {predicate, pullRefs.get(i)});
        }
        if (chargedRef != null) {
            out.add(new String[] {"\"charged\": 1", chargedRef});
        }
        String rocket = fireworkRef != null ? fireworkRef : chargedRef;
        if (rocket != null) {
            out.add(new String[] {"\"charged\": 1, \"firework\": 1", rocket});
        }
        return out;
    }

    private static String model(String ref) {
        return "{\"type\":\"minecraft:model\",\"model\":\"" + ref + "\"}";
    }

    /** 0.65 as 0.65 and 1.0 as 1, so two builds of one pack are byte-identical. */
    static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
