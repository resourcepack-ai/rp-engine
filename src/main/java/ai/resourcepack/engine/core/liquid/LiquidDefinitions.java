package ai.resourcepack.engine.core.liquid;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LiquidInfo;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads liquid definitions.
 *
 * <p>Free of Bukkit apart from {@link org.bukkit.potion.PotionEffectType},
 * which is looked up by name at load so a misspelled effect is a console line
 * naming the file rather than a pool that quietly does nothing.
 */
public final class LiquidDefinitions {

    private LiquidDefinitions() {
    }

    /** Everything of kind LIQUID in {@code loaded}, parsed. */
    public static Result parse(LoadReport loaded) {
        Map<ContentId, LiquidInfo> liquids = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.LIQUID)) {
            parseOne(definition, diagnostics).ifPresent(liquid -> liquids.put(liquid.id(), liquid));
        }
        return new Result(Map.copyOf(liquids), List.copyOf(diagnostics));
    }

    private static Optional<LiquidInfo> parseOne(ContentDefinition definition, List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        LiquidInfo.Base base = LiquidInfo.Base.WATER;
        Optional<String> declared = body.string("base");
        if (declared.isPresent()) {
            try {
                base = LiquidInfo.Base.valueOf(declared.get().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "base: " + declared.get() + " is not water or lava. Using water."));
            }
        }

        String effect = body.string("effect").map(name -> name.trim().toUpperCase(Locale.ROOT)).orElse(null);
        if (effect != null && !isEffect(effect)) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "effect: " + effect.toLowerCase(Locale.ROOT) + " is not a potion effect."));
            return Optional.empty();
        }

        int amplifier = body.integer("amplifier").orElse(0);
        if (amplifier < 0 || amplifier > 255) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "amplifier: " + amplifier + " is outside 0 to 255. Using 0."));
            amplifier = 0;
        }

        double damage = 0;
        Optional<String> declaredDamage = body.string("damage");
        if (declaredDamage.isPresent()) {
            try {
                damage = Double.parseDouble(declaredDamage.get().trim());
            } catch (NumberFormatException e) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "damage: " + declaredDamage.get() + " is not a number. Using none."));
            }
            if (damage < 0 || damage > 100) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "damage: " + declaredDamage.get() + " is outside 0 to 100. Using none."));
                damage = 0;
            }
        }

        LiquidInfo liquid = LiquidInfo.of(definition.id(), base, effect, amplifier, damage,
                body.bool("fireproof").orElse(Boolean.FALSE), body.strings("tags"));

        Optional<String> declaredColor = body.string("color");
        if (declaredColor.isPresent()) {
            // raw() rather than the string, because YAML reads 0x3FBF4A as a
            // number and Integer.toString would hand this a decimal that no
            // longer looks like the colour somebody typed.
            int rgb = body.raw("color") instanceof Number
                    ? ((Number) body.raw("color")).intValue() & 0xFFFFFF
                    : colorOf(declaredColor.get());
            if (rgb < 0) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "color: " + declaredColor.get() + " is not a hex colour or a colour name. "
                                + "Left untinted."));
            } else {
                liquid = liquid.withColor(rgb);
            }
        }
        return Optional.of(liquid);
    }

    /**
     * The sixteen colour names, so {@code color: RED} works.
     *
     * <p>Vanilla's dye colours rather than a palette of our own: they are the
     * names an author already has in their head from every other part of the
     * game, and a name that means one thing in wool and another in water would
     * be worse than having no names at all.
     */
    private static final Map<String, Integer> NAMED = namedColors();

    /**
     * {@code color:} as 0xRRGGBB, or -1 for something that is not a colour.
     *
     * <p>Accepts {@code #3FBF4A}, {@code 0x3FBF4A}, {@code 3FBF4A} and
     * {@code GREEN}. Four spellings because this is a field somebody types
     * from memory, and refusing three of the four teaches nothing.
     */
    private static int colorOf(String written) {
        String text = written == null ? "" : written.trim();
        if (text.isEmpty()) {
            return -1;
        }
        Integer named = NAMED.get(text.toUpperCase(Locale.ROOT));
        if (named != null) {
            return named;
        }
        String hex = text.startsWith("#") ? text.substring(1)
                : text.regionMatches(true, 0, "0x", 0, 2) ? text.substring(2)
                : text;
        if (hex.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, Integer> namedColors() {
        Map<String, Integer> named = new LinkedHashMap<>();
        named.put("WHITE", 0xF9FFFE);
        named.put("ORANGE", 0xF9801D);
        named.put("MAGENTA", 0xC74EBD);
        named.put("LIGHT_BLUE", 0x3AB3DA);
        named.put("YELLOW", 0xFED83D);
        named.put("LIME", 0x80C71F);
        named.put("PINK", 0xF38BAA);
        named.put("GRAY", 0x474F52);
        named.put("LIGHT_GRAY", 0x9D9D97);
        named.put("CYAN", 0x169C9C);
        named.put("PURPLE", 0x8932B8);
        named.put("BLUE", 0x3C44AA);
        named.put("BROWN", 0x835432);
        named.put("GREEN", 0x5E7C16);
        named.put("RED", 0xB02E26);
        named.put("BLACK", 0x1D1D21);
        return Map.copyOf(named);
    }

    /**
     * Whether {@code name} is a potion effect.
     *
     * <p>Looked up through the registry rather than an enum, which is how
     * Bukkit exposes these now. It needs a server, so a missing one at load
     * time reads as "cannot check" rather than "does not exist" — the caller
     * only refuses when the answer is a definite no.
     */
    private static boolean isEffect(String name) {
        try {
            return org.bukkit.Registry.EFFECT.get(
                    org.bukkit.NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT))) != null;
        } catch (RuntimeException | NoClassDefFoundError | ExceptionInInitializerError e) {
            // No server. Accept it and let the runtime find out, which is the
            // same call ItemDefinitions makes about materials.
            return true;
        }
    }

    /** The liquids, and what was wrong with the ones that are missing. */
    public static final class Result {

        private final Map<ContentId, LiquidInfo> liquids;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, LiquidInfo> liquids, List<Diagnostic> diagnostics) {
            this.liquids = liquids;
            this.diagnostics = diagnostics;
        }

        /** Every liquid that parsed, keyed by id. */
        public Map<ContentId, LiquidInfo> liquids() {
            return liquids;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
