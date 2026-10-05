package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads ItemsAdder's sounds as RP Engine sounds.
 *
 * <p>ItemsAdder has had two ways of declaring one, and packs of both are
 * around:
 *
 * <ul>
 *   <li><strong>4.0.12 and up</strong>: a {@code sounds:} section in a config,
 *       {@code sounds.<id>.path} naming the audio under the pack's
 *       {@code sounds/} folder and {@code settings} carrying the subtitle,
 *       volume, pitch and stream. The event is {@code <namespace>:<id>},
 *       which is what an RP Engine sound's id is too.</li>
 *   <li><strong>Before that</strong>: a hand-written {@code sounds.json} in the
 *       pack's {@code resourcepack/assets/<namespace>/}, vanilla's own format.
 *       Read by the loader from there.</li>
 * </ul>
 *
 * <p>The audio needs no moving in either case: their {@code sounds/} folder
 * and their resource pack folder are both copied into the built pack where an
 * RP Engine sound looks for its file.
 *
 * <p>What does not come across is the same as for Nexo and Oraxen, each a
 * warning naming the sound: a sound that picks one of several files at random
 * (an RP Engine sound plays one), and the settings with no equivalent here -
 * {@code weight}, {@code attenuation_distance}, {@code preload} and a
 * {@code jukebox} block.
 *
 * <p>A subtitle written as a language key ({@code sound.sound_1}) is looked up
 * in the pack's own English - {@code minecraft_lang_overwrite} in any of its
 * configs, or its resource pack's {@code lang/en_us.json} - because an RP
 * Engine subtitle is the text itself and makes its own key.
 */
final class ItemsAdderSound {

    private ItemsAdderSound() {
    }

    /** The {@code sounds:} section of one config. */
    static Map<String, Object> translate(DefinitionNode sounds, String namespace, String origin,
                                         List<Diagnostic> diagnostics, Map<String, String> lang) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String id : sounds.keys()) {
            DefinitionNode entry = sounds.node(id).orElse(null);
            if (entry == null) continue;
            List<String> paths = entry.strings("path");
            Map<String, Object> sound = new LinkedHashMap<>();
            if (!paths.isEmpty()) {
                if (paths.size() > 1) {
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "picks one of " + paths.size() + " files at random. An RP Engine sound plays one "
                                    + "file, so only " + paths.get(0) + " came across."));
                }
                sound.put("file", file(paths.get(0), namespace));
            }
            DefinitionNode settings = entry.node("settings").orElse(DefinitionNode.empty());
            settings.string("category").ifPresent(category -> sound.put("category", category.toLowerCase(Locale.ROOT)));
            settings.string("subtitle").ifPresent(subtitle -> sound.put("subtitle", lang.getOrDefault(subtitle, subtitle)));
            settings.string("volume").ifPresent(volume -> sound.put("volume", volume));
            settings.string("pitch").ifPresent(pitch -> sound.put("pitch", pitch));
            settings.bool("stream").ifPresent(stream -> sound.put("stream", stream));
            out.put(id, sound);

            List<String> skipped = new ArrayList<>();
            for (String key : List.of("weight", "attenuation_distance", "preload")) {
                if (settings.raw(key) != null) skipped.add(key);
            }
            if (entry.raw("jukebox") != null) skipped.add("jukebox");
            if (!skipped.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        String.join(", ", skipped) + " have no RP Engine equivalent and were skipped. "
                                + "The sound itself still plays."));
            }
        }
        return out;
    }

    /**
     * A vanilla {@code sounds.json}, as ItemsAdder packs wrote them before
     * their {@code sounds:} section existed.
     */
    static Map<String, Object> fromSoundsJson(Path file, String namespace, String origin,
                                              List<Diagnostic> diagnostics, Map<String, String> lang) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                diagnostics.add(Diagnostic.warning(origin, "is not a JSON object, so none of its sounds were read."));
                return out;
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | JsonParseException e) {
            diagnostics.add(Diagnostic.warning(origin, "could not be read as JSON, so none of its sounds were read. "
                    + firstLine(e)));
            return out;
        }

        for (Map.Entry<String, JsonElement> event : root.entrySet()) {
            String id = event.getKey();
            if (!event.getValue().isJsonObject()) continue;
            JsonObject entry = event.getValue().getAsJsonObject();
            List<JsonElement> variants = new ArrayList<>();
            if (entry.has("sounds") && entry.get("sounds").isJsonArray()) {
                entry.getAsJsonArray("sounds").forEach(variants::add);
            }
            if (variants.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, id, "names no sound file, so it was skipped."));
                continue;
            }
            JsonElement first = variants.get(0);
            JsonObject detail = first.isJsonObject() ? first.getAsJsonObject() : new JsonObject();
            String name = first.isJsonObject() ? text(detail, "name") : first.getAsString();
            if (name == null) {
                diagnostics.add(Diagnostic.warning(origin, id, "names no sound file, so it was skipped."));
                continue;
            }
            if ("event".equals(text(detail, "type"))) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "plays another sound event (" + name + ") rather than a file, which an RP Engine sound "
                                + "cannot, so it was skipped."));
                continue;
            }
            if (variants.size() > 1) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "picks one of " + variants.size() + " files at random. An RP Engine sound plays one "
                                + "file, so only " + name + " came across."));
            }

            Map<String, Object> sound = new LinkedHashMap<>();
            sound.put("file", file(name, namespace));
            String subtitle = text(entry, "subtitle");
            if (subtitle != null) {
                if (lang.containsKey(subtitle)) {
                    sound.put("subtitle", lang.get(subtitle));
                } else {
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "has the subtitle key " + subtitle + ", and the pack has no English text for it. An RP "
                                    + "Engine subtitle is the text itself, so it was left out."));
                }
            }
            if (text(detail, "volume") != null) sound.put("volume", text(detail, "volume"));
            if (text(detail, "pitch") != null) sound.put("pitch", text(detail, "pitch"));
            if (detail.has("stream") && detail.get("stream").isJsonPrimitive()) {
                sound.put("stream", detail.get("stream").getAsBoolean());
            }
            out.put(id, sound);
        }
        return out;
    }

    /**
     * {@code minecraft_lang_overwrite.<any>.entries}, for the languages that
     * include English. ItemsAdder writes these into the game's language files;
     * here they are only what a subtitle key means.
     */
    static Map<String, String> lang(DefinitionNode document) {
        Map<String, String> out = new LinkedHashMap<>();
        DefinitionNode overwrites = document.node("minecraft_lang_overwrite").orElse(DefinitionNode.empty());
        for (String name : overwrites.keys()) {
            DefinitionNode overwrite = overwrites.node(name).orElse(DefinitionNode.empty());
            List<String> languages = overwrite.strings("languages");
            boolean english = languages.isEmpty() || languages.stream()
                    .anyMatch(language -> language.equalsIgnoreCase("ALL") || language.equalsIgnoreCase("en_us"));
            if (!english) continue;
            DefinitionNode entries = overwrite.node("entries").orElse(DefinitionNode.empty());
            for (String key : entries.keys()) {
                entries.string(key).ifPresent(text -> out.putIfAbsent(key, text));
            }
        }
        return out;
    }

    /** A {@code lang/en_us.json}'s strings, or nothing if there is none or it is not one. */
    static Map<String, String> langFile(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) out.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (IOException | JsonParseException e) {
            // A language file that does not parse means no subtitles from it;
            // the client will not read it either.
        }
        return out;
    }

    /**
     * {@code my_sounds:music/song_1.ogg} to {@code music/song_1}: a path under
     * this pack's sounds when it is in the pack's namespace, a resource
     * location (which an RP Engine sound's {@code file} may be) when not.
     */
    private static String file(String reference, String namespace) {
        String value = reference.trim();
        if (value.endsWith(".ogg")) value = value.substring(0, value.length() - 4);
        int colon = value.indexOf(':');
        if (colon < 0) return value;
        return value.substring(0, colon).equals(namespace) ? value.substring(colon + 1) : value;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String firstLine(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
