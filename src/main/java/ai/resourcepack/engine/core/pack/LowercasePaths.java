package ai.resourcepack.engine.core.pack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowercases every file in the bundle whose path has a capital letter in it,
 * and every reference to one.
 *
 * <p>The game refuses a capital anywhere in a resource path ("Invalid path in
 * datapack: starcraft:textures/item/Aal.png, ignoring"), so a texture called
 * {@code Aal.png} is never loaded and every item drawn from it is the purple
 * and black square. Nothing said so: the file is in the pack, and the item's
 * {@code texture: item/Aal} names it exactly. On Windows, where most content
 * folders are made, the file system does not care about case, so nothing
 * there tells an author either, and a server owner spent days on a row of fish
 * that never drew.
 *
 * <p>So the built pack is made what the author meant: the file goes in
 * lowercased, and any JSON string in the pack that names it ({@code
 * starcraft:item/Aal} in a generated item model, or the author's own model,
 * font or atlas) is lowercased with it. Only a string that is exactly such a
 * name is touched, never words in a language file. Where the lowercase name is
 * already taken by another file, both are left as they are and said.
 */
final class LowercasePaths {

    /** An optional overlay folder, then assets/namespace/kind/rest. */
    private static final Pattern ASSET = Pattern.compile("((?:[^/]+/)?assets/)([^/]+)/([^/]+)/(.+)");

    private LowercasePaths() {
    }

    /** What was done, per namespace, for the build to say. */
    record Result(Map<String, List<String>> renamed, Map<String, List<String>> clashes) {
    }

    static Result complete(DeterministicZip zip) {
        Map<String, List<String>> renamed = new LinkedHashMap<>();
        Map<String, List<String>> clashes = new LinkedHashMap<>();
        // Every name the old spelling could be referred to by.
        Set<String> moved = new HashSet<>();
        for (String path : new ArrayList<>(zip.paths())) {
            Matcher asset = ASSET.matcher(path);
            if (!asset.matches()) {
                continue;
            }
            String namespace = asset.group(2);
            String inside = namespace + "/" + asset.group(3) + "/" + asset.group(4);
            String lower = inside.toLowerCase(Locale.ROOT);
            if (lower.equals(inside)) {
                continue;
            }
            String target = asset.group(1) + lower;
            if (zip.has(target)) {
                clashes.computeIfAbsent(namespace, n -> new ArrayList<>()).add(path);
                continue;
            }
            zip.add(target, zip.get(path));
            zip.remove(path);
            renamed.computeIfAbsent(namespace, n -> new ArrayList<>()).add(asset.group(3) + "/" + asset.group(4));
            String rest = asset.group(4);
            String id = namespace + ":" + rest;
            moved.add(id);
            moved.add(withoutExtension(id));
            if (namespace.equals("minecraft")) {
                moved.add(rest);
                moved.add(withoutExtension(rest));
            }
        }
        if (moved.isEmpty()) {
            return new Result(renamed, clashes);
        }
        for (String path : new ArrayList<>(zip.paths())) {
            if (!path.endsWith(".json") && !path.endsWith(".mcmeta")) {
                continue;
            }
            Matcher asset = ASSET.matcher(path);
            if (asset.matches() && asset.group(3).equals("lang")) {
                continue;
            }
            byte[] bytes = zip.get(path);
            JsonElement json;
            try {
                json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                continue;
            }
            JsonElement fixed = fix(json, moved);
            if (fixed != json) {
                zip.add(path, new GsonBuilder().disableHtmlEscaping().create().toJson(fixed)
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        return new Result(renamed, clashes);
    }

    /** {@code element} with every moved name lowercased: itself when none was in it. */
    private static JsonElement fix(JsonElement element, Set<String> moved) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            return moved.contains(value) ? new JsonPrimitive(value.toLowerCase(Locale.ROOT)) : element;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            JsonArray copy = null;
            for (int i = 0; i < array.size(); i++) {
                JsonElement fixed = fix(array.get(i), moved);
                if (fixed != array.get(i) && copy == null) {
                    copy = array.deepCopy();
                }
                if (copy != null) {
                    copy.set(i, fixed);
                }
            }
            return copy == null ? element : copy;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonObject copy = null;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement fixed = fix(entry.getValue(), moved);
                if (fixed != entry.getValue() && copy == null) {
                    copy = object.deepCopy();
                }
                if (copy != null && fixed != entry.getValue()) {
                    copy.add(entry.getKey(), fixed);
                }
            }
            return copy == null ? element : copy;
        }
        return element;
    }

    private static String withoutExtension(String id) {
        int slash = id.lastIndexOf('/');
        int dot = id.lastIndexOf('.');
        return dot > slash && dot > id.indexOf(':') ? id.substring(0, dot) : id;
    }
}
