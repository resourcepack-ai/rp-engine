package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads Nexo's and Oraxen's {@code sounds.yml} as RP Engine sounds.
 *
 * <p>Both keep it as one file, {@code plugins/Nexo/sounds.yml} or
 * {@code plugins/Oraxen/sounds.yml}: a top-level {@code sounds:} LIST of
 * entries, each with an {@code id}. Ours is a map of ids, so a list of maps
 * with {@code id} in them cannot be one of ours, and recognising it by that
 * shape needs no setting. It is read from the pack's {@code sounds/} folder
 * or loose in the pack folder, wherever somebody copied it.
 *
 * <p><strong>The id has to be this pack's.</strong> An RP Engine sound is
 * {@code <namespace>:<id>} for the folder it is in, and the event name IS
 * that id. So {@code mypack:music.boss} in a folder called {@code mypack}
 * comes across as {@code music.boss}; an id with no namespace, which both
 * plugins read as {@code minecraft:}, is moved into this pack with a warning
 * giving its new name; and an id in somebody else's namespace, or one that
 * replaces a vanilla sound ({@code replace: true}, or {@code minecraft:}
 * written out), is skipped, because replacing a vanilla event is
 * {@code overrides/sounds.json}'s job and naming another namespace is not
 * ours to do.
 */
final class NexoOraxenSound {
    private NexoOraxenSound() {
    }

    static boolean looksLikeOne(DefinitionNode document) {
        Object sounds = document.raw("sounds");
        if (!(sounds instanceof List) || ((List<?>) sounds).isEmpty()) return false;
        for (DefinitionNode entry : document.nodes("sounds")) {
            if (entry.has("id")) return true;
        }
        return false;
    }

    static Map<String, Object> translate(DefinitionNode document, String namespace, String origin,
                                         List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (DefinitionNode entry : document.nodes("sounds")) {
            String declared = entry.string("id").orElse(null);
            if (declared == null) continue;
            String id = declared.trim().toLowerCase(Locale.ROOT);
            boolean replace = entry.bool("replace").orElse(Boolean.FALSE);
            int colon = id.indexOf(':');
            if (replace || id.startsWith("minecraft:")) {
                diagnostics.add(Diagnostic.warning(origin, declared,
                        "replaces a vanilla sound. RP Engine sounds are new events in the pack's own namespace; "
                                + "put a vanilla replacement in overrides/sounds.json instead."));
                continue;
            }
            if (colon >= 0) {
                if (!id.substring(0, colon).equals(namespace)) {
                    diagnostics.add(Diagnostic.warning(origin, declared,
                            "is in another namespace, and a sound here belongs to the pack folder's (" + namespace
                                    + "), so it was skipped. Rename the folder, or the id."));
                    continue;
                }
                id = id.substring(colon + 1);
            } else {
                diagnostics.add(Diagnostic.warning(origin, declared,
                        "has no namespace, so Nexo/Oraxen registered it as minecraft:" + id + ". It is "
                                + namespace + ":" + id + " here; anything playing it by its old name needs the new one."));
            }

            List<String> files = new ArrayList<>(entry.strings("sound"));
            for (Object variant : entry.raw("sounds") instanceof List ? (List<?>) entry.raw("sounds") : List.of()) {
                if (variant instanceof Map) {
                    Object name = ((Map<?, ?>) variant).get("name");
                    if (name == null) name = ((Map<?, ?>) variant).get("sound");
                    if (name != null) files.add(name.toString());
                } else if (variant != null) {
                    files.add(variant.toString());
                }
            }
            if (files.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, declared, "names no sound file, so it was skipped."));
                continue;
            }
            String file = file(files.get(0), namespace);
            if (file == null) {
                diagnostics.add(Diagnostic.warning(origin, declared,
                        "plays " + files.get(0) + ", outside this pack's namespace. RP Engine cannot copy somebody "
                                + "else's audio, so it was skipped."));
                continue;
            }
            if (files.size() > 1) {
                diagnostics.add(Diagnostic.warning(origin, declared,
                        "picks one of " + files.size() + " files at random. An RP Engine sound plays one file, "
                                + "so only " + files.get(0) + " came across."));
            }

            Map<String, Object> sound = new LinkedHashMap<>();
            sound.put("file", file);
            entry.string("category").ifPresent(category -> sound.put("category", category.toLowerCase(Locale.ROOT)));
            entry.string("subtitle").ifPresent(subtitle -> sound.put("subtitle", subtitle));
            entry.string("volume").ifPresent(volume -> sound.put("volume", number(volume)));
            entry.string("pitch").ifPresent(pitch -> sound.put("pitch", number(pitch)));
            entry.bool("stream").ifPresent(stream -> sound.put("stream", stream));
            out.put(id, sound);

            List<String> skipped = new ArrayList<>();
            for (String key : List.of("jukebox_playable", "jukebox", "preload", "weight", "attenuation_distance")) {
                if (entry.raw(key) != null) skipped.add(key);
            }
            if (!skipped.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, declared,
                        String.join(", ", skipped) + " have no RP Engine equivalent and were skipped. "
                                + "The sound itself still plays."));
            }
        }
        return out;
    }

    /**
     * {@code nexo:music/boss.ogg} to {@code music/boss}: a path under the
     * pack's {@code assets/sounds/}, which is where theirs is rooted too.
     */
    private static String file(String reference, String namespace) {
        String value = reference.trim();
        if (value.endsWith(".ogg")) value = value.substring(0, value.length() - 4);
        int colon = value.indexOf(':');
        if (colon < 0) return value;
        return value.substring(0, colon).equals(namespace) ? value.substring(colon + 1) : null;
    }

    /** {@code 1f} is how Nexo's own example writes a volume; a Java float suffix is still a number. */
    private static String number(String text) {
        String value = text.trim();
        return value.endsWith("f") || value.endsWith("F") ? value.substring(0, value.length() - 1) : value;
    }
}
