package ai.resourcepack.engine.core.item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The base item model files a pre-1.21.4 pack needs, and the one thing about
 * them that is genuinely hard.
 *
 * <p>Before 1.21.4 a custom model is chosen by a predicate inside the base
 * item's <em>own</em> model file — {@code assets/minecraft/models/item/paper.json}
 * for an item built on paper. Writing that file replaces vanilla's copy of it
 * wholesale, which means the base half has to reproduce what vanilla renders
 * when no predicate matches. Get it wrong and the custom items are fine while
 * <b>plain paper</b>, everywhere on the server, looks wrong.
 *
 * <p>That is the whole difficulty, and it is why studio does not have it:
 * studio picks its own carriers from a closed set of six, so it can keep a
 * table. Here a server owner writes {@code material:} and means it, so there
 * is no closed set to tabulate.
 *
 * <p><b>The rule.</b> Vanilla's item models are overwhelmingly one of three
 * shapes, and which one is predictable from the name:
 *
 * <ul>
 *   <li>a block's item form parents the block model;</li>
 *   <li>a tool or weapon is {@code item/handheld}, so it is held at an
 *       angle;</li>
 *   <li>everything else is {@code item/generated} over a sprite of the same
 *       name.</li>
 * </ul>
 *
 * <p>That covers nearly everything. What it does not cover is the handful of
 * items whose vanilla model carries predicates of its own — a bow's pull
 * states, a compass's needle, a crossbow's charge — and reproducing those
 * without shipping vanilla's assets is not something a guess can do. Those are
 * named in {@link #AWKWARD} and the build says so rather than quietly
 * flattening somebody's compass.
 */
final class LegacyItemModels {

    /**
     * Materials whose vanilla model is {@code item/handheld}: held at a angle
     * in the hand rather than flat.
     *
     * <p>By suffix rather than by listing every material, because the set is
     * closed by naming convention and a new tool in a later version follows
     * it. A shovel is {@code _SHOVEL} whatever it is made of.
     */
    private static final List<String> HANDHELD_SUFFIXES = Arrays.asList(
            "_SWORD", "_PICKAXE", "_AXE", "_SHOVEL", "_HOE");

    /**
     * Materials whose vanilla model is none of the three shapes, because it
     * carries predicates or overrides of its own.
     *
     * <p>Not a refusal — an item on one of these still gets its number, its
     * model and its texture, and works. What is lost is the base item's own
     * appearance for everybody on the server, which is worth one line in a
     * build report and is invisible otherwise.
     */
    private static final Set<String> AWKWARD = new HashSet<>(Arrays.asList(
            "BOW", "CROSSBOW", "TRIDENT", "SHIELD", "SPYGLASS", "COMPASS",
            "RECOVERY_COMPASS", "CLOCK", "FISHING_ROD", "ELYTRA", "BUNDLE",
            "LIGHT", "TIPPED_ARROW", "POTION", "SPLASH_POTION", "LINGERING_POTION",
            "CHEST", "TRAPPED_CHEST", "ENDER_CHEST", "SHULKER_BOX", "DECORATED_POT",
            "PLAYER_HEAD", "CREEPER_HEAD", "ZOMBIE_HEAD", "SKELETON_SKULL",
            "WITHER_SKELETON_SKULL", "DRAGON_HEAD", "PIGLIN_HEAD"));

    /** One custom model reachable from a base item, at its number. */
    static final class Override {

        final int number;
        final String modelRef;

        Override(int number, String modelRef) {
            this.number = number;
            this.modelRef = modelRef;
        }
    }

    private LegacyItemModels() {
    }

    /** Where the base item's model has to be written to be found. */
    static String path(String material) {
        return "assets/minecraft/models/item/" + material.toLowerCase(Locale.ROOT) + ".json";
    }

    /**
     * Whether this material's vanilla model is beyond reproducing, and the
     * author should be told before their compass changes shape.
     */
    static boolean isAwkward(String material) {
        return AWKWARD.contains(material.toUpperCase(Locale.ROOT));
    }

    /**
     * The base item's model file, with every custom model hanging off it.
     *
     * @param material the vanilla material, e.g. {@code PAPER}
     * @param isBlock  whether it is a block's item form, which the caller
     *                 knows from the server and this cannot
     */
    static byte[] json(String material, boolean isBlock, List<Override> overrides) {
        String name = material.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(128 + overrides.size() * 80);
        out.append("{\n");
        if (isBlock) {
            out.append("  \"parent\": \"minecraft:block/").append(name).append("\",\n");
        } else {
            out.append("  \"parent\": \"minecraft:item/")
                    .append(isHandheld(material) ? "handheld" : "generated").append("\",\n");
            out.append("  \"textures\": { \"layer0\": \"minecraft:item/").append(name).append("\" },\n");
        }
        out.append("  \"overrides\": [\n");
        // Sorted by number, because the client takes the LAST predicate that
        // matches and a list out of order silently resolves to the wrong
        // model. Also makes the file byte-identical between two builds of the
        // same content, which the zip hash depends on.
        List<Override> sorted = new ArrayList<>(overrides);
        sorted.sort(Comparator.comparingInt(entry -> entry.number));
        for (int i = 0; i < sorted.size(); i++) {
            Override override = sorted.get(i);
            out.append("    { \"predicate\": { \"custom_model_data\": ")
                    .append(override.number)
                    .append(" }, \"model\": \"").append(override.modelRef).append("\" }");
            out.append(i + 1 < sorted.size() ? ",\n" : "\n");
        }
        out.append("  ]\n}\n");
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean isHandheld(String material) {
        String upper = material.toUpperCase(Locale.ROOT);
        for (String suffix : HANDHELD_SUFFIXES) {
            if (upper.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
