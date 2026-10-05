package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.Diagnostic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.resourcepack.engine.core.content.CraftEngine.get;
import static ai.resourcepack.engine.core.content.CraftEngine.list;
import static ai.resourcepack.engine.core.content.CraftEngine.location;
import static ai.resourcepack.engine.core.content.CraftEngine.map;
import static ai.resourcepack.engine.core.content.CraftEngine.number;
import static ai.resourcepack.engine.core.content.CraftEngine.string;
import static ai.resourcepack.engine.core.content.CraftEngine.strings;
import static ai.resourcepack.engine.core.content.CraftEngine.truthy;

/**
 * CraftEngine images, emoji, recipes and sounds, as RP Engine icons, recipes
 * and sounds.
 *
 * <h2>Images and emoji</h2>
 *
 * <p>An image is a bitmap font glyph, which is what an RP Engine icon is. Its
 * {@code file} is a resource location and stays one, its {@code height} and
 * {@code ascent} carry over (the height read off the PNG when the image does
 * not say, as CraftEngine does), and a grid - {@code grid_size}, or
 * {@code chars} written as rows - becomes one icon per cell,
 * {@code <id>_<row>_<column>} counted from 0, the numbers CraftEngine's own
 * {@code <image:id:row:column>} uses. A {@code ref} image is the cell it
 * points at. What does not come across is the character an image pins
 * (RP Engine gives each icon its character by id) and the font it is in
 * (icons go into the default font).
 *
 * <p>An emoji is a picture somebody types. RP Engine's equivalent is the
 * icon itself, typed as {@code :id:} with {@code chat.icons} on, so every
 * keyword written {@code :word:} becomes an icon called {@code word} drawing
 * the emoji's picture. A keyword that is not a possible id ({@code :)}) and
 * the emoji's permission and chat format are named and skipped.
 */
final class CraftEngineAssets {

    private CraftEngineAssets() {
    }

    static void translate(CraftEngine.Library library, CraftEngine.Pack pack, List<CraftEngine.Output> out,
                          List<Diagnostic> diagnostics) {
        Set<String> taken = new LinkedHashSet<>();
        for (CraftEngine.Output output : out) {
            if (output.kind() != ContentKind.RECIPE) taken.add(output.path());
        }
        Map<String, Integer> pinned = new LinkedHashMap<>();
        for (CraftEngine.Entry entry : pack.entries) {
            if (entry.section.equals("images")) {
                image(library, entry, out, taken, pinned, diagnostics);
            }
        }
        for (Map.Entry<String, Integer> file : pinned.entrySet()) {
            diagnostics.add(Diagnostic.warning(file.getKey(),
                    file.getValue() + " image" + (file.getValue() == 1 ? " pins its character" : "s pin their characters")
                            + " with char:. RP Engine gives each icon its character by id, so write :namespace:id: "
                            + "(or Icons.format) rather than the character itself."));
        }
        for (CraftEngine.Entry entry : pack.entries) {
            switch (entry.section) {
                case "emoji":
                    emoji(library, entry, out, taken, diagnostics);
                    break;
                case "recipes":
                    recipe(library, entry, out, diagnostics);
                    break;
                case "sounds":
                    sound(library, entry, out, diagnostics);
                    break;
                default:
            }
        }
    }

    // ---- images -----------------------------------------------------------------------

    /** What an image draws, worked out once. */
    record Image(String file, int height, int ascent, int rows, int columns, Set<Integer> blank) {
    }

    private static void image(CraftEngine.Library library, CraftEngine.Entry entry, List<CraftEngine.Output> out,
                              Set<String> taken, Map<String, Integer> pinned, List<Diagnostic> diagnostics) {
        if (get(entry.body, "char", "chars", "unicode") != null) {
            pinned.merge(entry.origin, 1, Integer::sum);
        }
        String font = string(entry.body.get("font"));
        if (font != null && !location(font).equals("minecraft:default")) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "is in the font " + font + ". An RP Engine icon goes into the default font, so it shows "
                            + "anywhere text does rather than only where that font is asked for."));
        }

        Object ref = get(entry.body, "ref");
        if (ref != null) {
            reference(library, entry, String.valueOf(ref), out, taken, diagnostics);
            return;
        }
        Image image = resolve(library, entry, diagnostics);
        if (image == null) return;
        if (image.rows() * image.columns() == 1) {
            emit(entry, entry.path(), icon(image, 0, 0), out, taken, diagnostics);
            return;
        }
        int made = 0;
        for (int row = 0; row < image.rows(); row++) {
            for (int column = 0; column < image.columns(); column++) {
                if (image.blank().contains(row * image.columns() + column)) continue;
                if (emit(entry, cell(entry.path(), row, column), icon(image, row, column), out, taken, diagnostics)) {
                    made++;
                }
            }
        }
        diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                "is a " + image.rows() + " by " + image.columns() + " grid, so it became " + made + " icons, "
                        + cell(entry.path(), 0, 0) + " onwards: " + entry.path() + "_<row>_<column>, counted from 0 "
                        + "as CraftEngine's <image:" + entry.id + ":row:column> counts them."));
    }

    static String cell(String path, int row, int column) {
        return path + "_" + row + "_" + column;
    }

    private static Map<String, Object> icon(Image image, int row, int column) {
        Map<String, Object> icon = new LinkedHashMap<>();
        icon.put("file", image.file());
        icon.put("height", image.height());
        icon.put("ascent", image.ascent());
        if (image.rows() * image.columns() > 1) {
            Map<String, Object> grid = new LinkedHashMap<>();
            grid.put("rows", image.rows());
            grid.put("columns", image.columns());
            grid.put("cell", row * image.columns() + column + 1);
            icon.put("grid", grid);
        }
        return icon;
    }

    private static boolean emit(CraftEngine.Entry entry, String path, Map<String, Object> icon,
                                List<CraftEngine.Output> out, Set<String> taken, List<Diagnostic> diagnostics) {
        if (!taken.add(path)) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "would be the icon " + path + ", which this pack already uses for something else, so it was "
                            + "skipped."));
            return false;
        }
        out.add(new CraftEngine.Output(ContentKind.FONT, path, icon, entry.origin));
        return true;
    }

    /** An image's file, size and grid, or null when it cannot be an icon. */
    static Image resolve(CraftEngine.Library library, CraftEngine.Entry entry, List<Diagnostic> diagnostics) {
        String file = string(entry.body.get("file"));
        if (file == null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id, "names no file, so it was skipped."));
            return null;
        }
        String texture = location(file.replace('\\', '/'));
        int rows = 1;
        int columns = 1;
        Set<Integer> blank = new LinkedHashSet<>();
        Object chars = get(entry.body, "char", "chars", "unicode");
        if (chars instanceof List<?> lines && !lines.isEmpty()) {
            rows = lines.size();
            for (int row = 0; row < lines.size(); row++) {
                int[] codepoints = codepoints(String.valueOf(lines.get(row)));
                columns = Math.max(columns, codepoints.length);
                for (int column = 0; column < codepoints.length; column++) {
                    if (codepoints[column] == 0) blank.add(row * codepoints.length + column);
                }
            }
        } else if (chars instanceof String text) {
            columns = Math.max(1, codepoints(text).length);
        } else if (chars == null) {
            String grid = string(get(entry.body, "grid_size"));
            if (grid != null) {
                String[] parts = grid.split(",");
                Double r = parts.length == 2 ? number(parts[0]) : null;
                Double c = parts.length == 2 ? number(parts[1]) : null;
                if (r != null && c != null && r >= 1 && c >= 1) {
                    rows = r.intValue();
                    columns = c.intValue();
                }
            }
        }
        Double declaredHeight = number(get(entry.body, "height", "scale", "scale_ratio"));
        int height;
        if (declaredHeight != null) {
            height = declaredHeight.intValue();
        } else {
            Integer pixels = pngHeight(library, texture);
            if (pixels == null) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "names no height and its PNG (" + texture + ") is not in a loaded pack's resourcepack "
                                + "folder to measure, so it was skipped."));
                return null;
            }
            height = Math.max(1, pixels / rows);
        }
        if (height < 1 || height > 256) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "has height " + height + ". An icon is 1 to 256 pixels tall; a negative one is CraftEngine's "
                            + "way of drawing space, which RP Engine does itself. It was skipped."));
            return null;
        }
        Double declaredAscent = number(get(entry.body, "ascent", "y_position"));
        int ascent = declaredAscent == null ? height - 1 : declaredAscent.intValue();
        return new Image(texture, height, ascent, rows, columns, blank);
    }

    /** {@code ref: ns:id:row:column}, or {@code ref} beside {@code row} and {@code col}: one cell of another image. */
    private static void reference(CraftEngine.Library library, CraftEngine.Entry entry, String ref,
                                  List<CraftEngine.Output> out, Set<String> taken, List<Diagnostic> diagnostics) {
        String[] parts = ref.trim().split(":");
        String target;
        int row = number(entry.body.get("row")) == null ? 0 : number(entry.body.get("row")).intValue();
        int column = number(entry.body.get("col")) == null ? 0 : number(entry.body.get("col")).intValue();
        if (parts.length >= 3) {
            target = parts[0] + ":" + parts[1];
            row = number(parts[2]) == null ? 0 : number(parts[2]).intValue();
            column = parts.length > 3 && number(parts[3]) != null ? number(parts[3]).intValue() : 0;
        } else {
            target = parts.length == 2 ? ref.trim() : null;
            if (target == null) {
                for (String id : library.images.keySet()) {
                    if (id.endsWith(":" + parts[0])) {
                        target = id;
                        break;
                    }
                }
            }
        }
        CraftEngine.Entry image = target == null ? null : library.images.get(target);
        if (image == null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "refers to the image " + ref + ", which no loaded CraftEngine pack defines, so it was skipped."));
            return;
        }
        Image resolved = resolve(library, image, new ArrayList<>());
        if (resolved == null || row >= resolved.rows() || column >= resolved.columns()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "refers to a cell " + ref + " does not have, so it was skipped."));
            return;
        }
        Map<String, Object> icon = icon(resolved, row, column);
        Double height = number(get(entry.body, "height", "scale", "scale_ratio"));
        Double ascent = number(get(entry.body, "ascent", "y_position"));
        if (height != null) icon.put("height", height.intValue());
        if (ascent != null) icon.put("ascent", ascent.intValue());
        emit(entry, entry.path(), icon, out, taken, diagnostics);
    }

    /**
     * The codepoints of a {@code chars} row: real characters, or CraftEngine's
     * escaped form, a string starting {@code \}{@code u} made of six-character
     * {@code \}{@code uXXXX} groups.
     */
    static int[] codepoints(String text) {
        if (text.startsWith("\\u") && text.length() % 6 == 0) {
            StringBuilder decoded = new StringBuilder();
            try {
                for (int i = 0; i < text.length(); i += 6) {
                    decoded.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                }
                return decoded.toString().codePoints().toArray();
            } catch (NumberFormatException e) {
                // Not escapes after all; the characters as written.
            }
        }
        return text.codePoints().toArray();
    }

    /** The height of a PNG in any loaded pack's resource pack folder, read off its header. */
    private static Integer pngHeight(CraftEngine.Library library, String texture) {
        int colon = texture.indexOf(':');
        String relative = "resourcepack/assets/" + texture.substring(0, colon) + "/textures/"
                + texture.substring(colon + 1) + ".png";
        for (CraftEngine.Pack pack : library.packs.values()) {
            if (pack.directory == null) continue;
            Path file = pack.directory.resolve(relative).normalize();
            if (!file.startsWith(pack.directory.normalize()) || !Files.isRegularFile(file)) continue;
            try (InputStream in = Files.newInputStream(file)) {
                byte[] header = in.readNBytes(24);
                if (header.length < 24 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') return null;
                return ((header[20] & 0xff) << 24) | ((header[21] & 0xff) << 16) | ((header[22] & 0xff) << 8)
                        | (header[23] & 0xff);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    // ---- emoji ------------------------------------------------------------------------

    private static void emoji(CraftEngine.Library library, CraftEngine.Entry entry, List<CraftEngine.Output> out,
                              Set<String> taken, List<Diagnostic> diagnostics) {
        String declared = string(entry.body.get("image"));
        if (declared == null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "names no image, which is the only part of an emoji RP Engine can draw, so it was skipped."));
            return;
        }
        String[] parts = declared.trim().split(":");
        String target = parts.length >= 2 ? parts[0] + ":" + parts[1] : entry.namespace() + ":" + parts[0];
        int row = parts.length >= 3 && number(parts[2]) != null ? number(parts[2]).intValue() : 0;
        int column = parts.length >= 4 && number(parts[3]) != null ? number(parts[3]).intValue() : 0;
        CraftEngine.Entry image = library.images.get(target);
        Image resolved = image == null ? null : resolve(library, image, new ArrayList<>());
        if (resolved == null || row >= resolved.rows() || column >= resolved.columns()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "draws " + declared + ", which is not an image (or a cell of one) a loaded pack defines, so it "
                            + "was skipped."));
            return;
        }
        // The icon the image itself became, which is already typed as :id:.
        String drawn = resolved.rows() * resolved.columns() == 1 ? image.path() : cell(image.path(), row, column);
        boolean sameFolder = image.folder.equals(entry.folder);

        List<String> typed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String keyword : strings(entry.body.get("keywords"))) {
            String word = keyword.trim();
            boolean colons = word.length() > 2 && word.startsWith(":") && word.endsWith(":");
            String name = colons ? word.substring(1, word.length() - 1).toLowerCase(Locale.ROOT) : null;
            if (name == null || !ContentId.isValidPath(name) || name.contains(":")) {
                skipped.add(keyword + " (an RP Engine icon is typed :name:)");
                continue;
            }
            if (sameFolder && name.equals(drawn)) {
                typed.add(":" + name + ":");
                continue;
            }
            if (taken.contains(name)) {
                skipped.add(keyword + " (" + name + " is already another id in this pack)");
                continue;
            }
            taken.add(name);
            out.add(new CraftEngine.Output(ContentKind.FONT, name, icon(resolved, row, column), entry.origin));
            typed.add(":" + name + ":");
        }
        if (string(entry.body.get("permission")) != null) {
            skipped.add("the permission " + entry.body.get("permission")
                    + " (an icon is typed by anybody with rpengine.chat.icons)");
        }
        if (get(entry.body, "content", "format") != null || get(entry.body, "content_overrides") != null) {
            skipped.add("its chat format (an icon is drawn as itself)");
        }
        if (!skipped.isEmpty() || typed.isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    (typed.isEmpty() ? "has no keyword RP Engine can type"
                            : "is typed as " + String.join(", ", typed) + " with chat.icons on")
                            + (skipped.isEmpty() ? "." : "; skipped: " + String.join("; ", skipped) + ".")));
        }
    }

    // ---- recipes ----------------------------------------------------------------------

    private static final Set<String> UNSUPPORTED = Set.of("smithing_transform", "smithing_trim", "brewing",
            "shaped_transform", "shapeless_transform", "dye", "crafting_dye");

    private static void recipe(CraftEngine.Library library, CraftEngine.Entry entry, List<CraftEngine.Output> out,
                               List<Diagnostic> diagnostics) {
        String declared = CraftEngineItems.type(entry.body.get("type"));
        String type;
        switch (declared) {
            case "shaped":
            case "crafting_shaped":
                type = "shaped";
                break;
            case "shapeless":
            case "crafting_shapeless":
                type = "shapeless";
                break;
            case "smelting":
            case "blasting":
            case "smoking":
            case "stonecutting":
                type = declared;
                break;
            case "campfire_cooking":
                type = "campfire";
                break;
            default:
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        (declared.isEmpty() ? "names no type" : "is a " + declared + " recipe")
                                + (UNSUPPORTED.contains(declared) ? ", and RP Engine recipes are crafting, cooking and "
                                + "stonecutting only" : "") + ", so it was skipped."));
                return;
        }
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("type", type);

        Object result = entry.body.get("result");
        String resultId = result instanceof Map<?, ?> detail ? string(CraftEngineYaml.cast(detail).get("id")) : string(result);
        Double count = result instanceof Map<?, ?> detail ? number(CraftEngineYaml.cast(detail).get("count")) : null;
        String resultReference = resultId == null ? null : library.reference(resultId, "minecraft");
        if (resultReference == null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "makes " + resultId + ", which is not an item a loaded pack or the game has, so the recipe was "
                            + "skipped."));
            return;
        }
        recipe.put("result", resultReference);
        if (count != null && count.intValue() > 1) recipe.put("amount", count.intValue());

        List<String> unreadable = new ArrayList<>();
        if (type.equals("shaped")) {
            Map<String, Object> keys = new LinkedHashMap<>();
            Map<String, Object> ingredients = map(get(entry.body, "ingredient", "ingredients"));
            if (ingredients != null) {
                for (Map.Entry<String, Object> key : ingredients.entrySet()) {
                    String reference = ingredient(library, key.getValue(), unreadable);
                    if (reference != null) keys.put(key.getKey(), reference);
                }
            }
            recipe.put("pattern", strings(entry.body.get("pattern")));
            recipe.put("keys", keys);
        } else if (type.equals("shapeless")) {
            Object declaredIngredients = get(entry.body, "ingredient", "ingredients");
            List<Object> entries = declaredIngredients instanceof Map<?, ?> keyed
                    ? new ArrayList<>(CraftEngineYaml.cast(keyed).values()) : list(declaredIngredients);
            List<Object> ingredients = new ArrayList<>();
            for (Object ingredient : entries) {
                String reference = ingredient(library, ingredient, unreadable);
                if (reference == null) continue;
                Map<String, Object> detail = map(ingredient);
                int times = detail == null || number(detail.get("count")) == null ? 1
                        : Math.max(1, number(detail.get("count")).intValue());
                for (int i = 0; i < times; i++) ingredients.add(reference);
            }
            recipe.put("ingredients", ingredients);
        } else {
            String reference = ingredient(library, get(entry.body, "ingredient", "ingredients"), unreadable);
            if (reference != null) recipe.put("ingredient", reference);
            if (!type.equals("stonecutting")) {
                Double experience = number(get(entry.body, "exp", "experience"));
                if (experience != null) recipe.put("experience", experience);
                // CraftEngine's default cooking time is 80 ticks for every
                // kind of furnace, not vanilla's.
                Double time = number(entry.body.get("time"));
                recipe.put("time", time == null ? 80 : time.intValue());
            }
        }
        if (!unreadable.isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "the ingredient" + (unreadable.size() == 1 ? " " : "s ") + String.join(", ", unreadable)
                            + (unreadable.size() == 1 ? " is" : " are") + " not one item of a loaded pack or the "
                            + "game (a tag, a choice of several, or another plugin's item), so the recipe was "
                            + "skipped rather than made without it."));
            return;
        }
        List<String> skipped = new ArrayList<>();
        for (String key : List.of("visual_result", "functions", "function", "conditions", "condition",
                "unlock_on_ingredient_obtained", "unlock_on_join")) {
            if (entry.body.get(key) != null) skipped.add(key);
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    String.join(", ", skipped) + " have no RP Engine equivalent and were skipped. The recipe still "
                            + "works."));
        }
        out.add(new CraftEngine.Output(ContentKind.RECIPE, entry.path(), recipe, entry.origin));
    }

    /** One ingredient as RP Engine writes it, or null (named in {@code unreadable}) when it is not one item. */
    private static String ingredient(CraftEngine.Library library, Object declared, List<String> unreadable) {
        Object value = declared;
        Map<String, Object> detail = map(declared);
        if (detail != null) value = get(detail, "item", "items");
        List<Object> choices = list(value);
        if (choices.size() != 1 || string(choices.get(0)) == null) {
            unreadable.add(String.valueOf(value));
            return null;
        }
        String id = string(choices.get(0)).trim();
        if (id.startsWith("#")) {
            unreadable.add(id);
            return null;
        }
        String reference = library.reference(id, "minecraft");
        if (reference == null) {
            unreadable.add(id);
        }
        return reference;
    }

    // ---- sounds -----------------------------------------------------------------------

    private static void sound(CraftEngine.Library library, CraftEngine.Entry entry, List<CraftEngine.Output> out,
                              List<Diagnostic> diagnostics) {
        if (entry.namespace().equals("minecraft") || truthy(entry.body.get("replace"))) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "replaces a vanilla sound. RP Engine sounds are new events in the pack's own namespace; ship "
                            + "a vanilla replacement as resourcepack/assets/minecraft/sounds.json instead."));
            return;
        }
        List<Object> variants = list(get(entry.body, "sound", "sounds"));
        if (variants.isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id, "names no sound file, so it was skipped."));
            return;
        }
        Object first = variants.get(0);
        Map<String, Object> detail = map(first);
        String name = detail == null ? string(first) : string(detail.get("name"));
        if (detail != null && "event".equalsIgnoreCase(string(detail.get("type")))) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "plays another sound event, " + name + ", rather than a file, which an RP Engine sound cannot, "
                            + "so it was skipped."));
            return;
        }
        if (name == null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id, "names no sound file, so it was skipped."));
            return;
        }
        String file = name.trim();
        if (file.endsWith(".ogg")) file = file.substring(0, file.length() - 4);
        // A sounds.json name with no namespace is minecraft's, as the game reads it.
        if (file.indexOf(':') < 0) file = "minecraft:" + file;
        if (file.startsWith(entry.folder + ":")) file = file.substring(entry.folder.length() + 1);

        Map<String, Object> sound = new LinkedHashMap<>();
        sound.put("file", file);
        String subtitle = string(entry.body.get("subtitle"));
        if (subtitle != null) {
            String english = library.english(subtitle);
            sound.put("subtitle", english != null ? english : subtitle);
        }
        if (detail != null) {
            Double volume = number(detail.get("volume"));
            Double pitch = number(detail.get("pitch"));
            if (volume != null) sound.put("volume", volume);
            if (pitch != null) sound.put("pitch", pitch);
            if (detail.get("stream") != null) sound.put("stream", truthy(detail.get("stream")));
        }
        out.add(new CraftEngine.Output(ContentKind.SOUND, entry.path(), sound, entry.origin));
        if (variants.size() > 1) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "picks one of " + variants.size() + " files at random. An RP Engine sound plays one file, so "
                            + "only " + name + " came across."));
        }
        if (detail != null) {
            List<String> skipped = new ArrayList<>();
            for (String key : List.of("weight", "preload", "attenuation_distance")) {
                if (get(detail, key) != null) skipped.add(key);
            }
            if (!skipped.isEmpty()) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        String.join(", ", skipped) + " have no RP Engine equivalent and were skipped. The sound "
                                + "itself still plays."));
            }
        }
    }
}
