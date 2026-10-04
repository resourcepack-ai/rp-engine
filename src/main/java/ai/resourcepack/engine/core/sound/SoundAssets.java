package ai.resourcepack.engine.core.sound;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the {@code sounds.json} that turns shipped audio into sound events.
 *
 * <p>One file per namespace, at {@code assets/<namespace>/sounds.json}, and
 * that is why this cannot be done per definition the way an item's model is:
 * the file holds every sound in the namespace, so writing one sound means
 * writing all of them at once. A pack that shipped a second file would replace
 * the first rather than add to it, and the sounds in it would simply not
 * exist.
 *
 * <p>Subtitles go in a language file beside it. A sound with no subtitle is
 * silent to anybody playing with subtitles rather than audio, which is more
 * people than most server owners expect.
 */
public final class SoundAssets implements PackContributor {

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        SoundDefinitions.Result parsed = SoundDefinitions.parse(loaded);

        // Grouped by namespace, sorted throughout, because both files are one
        // blob of text per namespace and their byte order reaches the hash.
        Map<String, Map<ContentId, SoundInfo>> byNamespace = new TreeMap<>();
        for (SoundInfo sound : parsed.sounds().values()) {
            if (!bundle.namespaces().contains(sound.id().namespace())) {
                continue;
            }
            byNamespace.computeIfAbsent(sound.id().namespace(), key -> new TreeMap<>())
                    .put(sound.id(), sound);
        }

        for (Map.Entry<String, Map<ContentId, SoundInfo>> entry : byNamespace.entrySet()) {
            write(entry.getKey(), entry.getValue(), into);
        }
    }

    private void write(String namespace, Map<ContentId, SoundInfo> sounds, Contribution into) {
        StringBuilder json = new StringBuilder("{\n");
        StringBuilder lang = new StringBuilder("{\n");
        boolean firstSound = true;
        boolean firstSubtitle = true;

        for (SoundInfo sound : sounds.values()) {
            String audioPath = "assets/" + namespace + "/sounds/" + sound.file() + ".ogg";
            if (!into.has(audioPath)) {
                into.error(namespace + "/sounds", sound.id().path(),
                        "No audio at " + audioPath + ". Minecraft only plays Ogg Vorbis; "
                                + "an mp3 or a wav renamed to .ogg is silence.");
                continue;
            }

            if (!firstSound) {
                json.append(",\n");
            }
            firstSound = false;

            String subtitleKey = "subtitles." + namespace + "." + sound.id().path().replace('/', '.');
            json.append("  \"").append(sound.id().path()).append("\": {\n")
                    .append("    \"category\": \"").append(sound.category()).append("\",\n");
            if (sound.subtitle().isPresent()) {
                json.append("    \"subtitle\": \"").append(escape(subtitleKey)).append("\",\n");
            }
            json.append("    \"sounds\": [{\"name\": \"").append(namespace).append(':').append(sound.file())
                    .append("\", \"stream\": ").append(sound.stream()).append("}]\n")
                    .append("  }");

            if (sound.subtitle().isPresent()) {
                if (!firstSubtitle) {
                    lang.append(",\n");
                }
                firstSubtitle = false;
                lang.append("  \"").append(escape(subtitleKey)).append("\": \"")
                        .append(escape(sound.subtitle().get())).append('"');
            }
        }

        json.append("\n}\n");
        into.add("assets/" + namespace + "/sounds.json", json.toString().getBytes(StandardCharsets.UTF_8));

        if (!firstSubtitle) {
            lang.append("\n}\n");
            // en_us only. Shipping a translation nobody wrote would be worse
            // than shipping none: the client falls back to en_us for a missing
            // key, and an empty locale file is a wall of blank subtitles.
            into.add("assets/" + namespace + "/lang/en_us.json",
                    lang.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
