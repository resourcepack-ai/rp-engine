package ai.resourcepack.engine.core.sound;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.PackMeta;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.core.pack.PackFiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads sound definitions out of a load report.
 *
 * <p>Free of Bukkit, like every other definition reader here, so the whole of
 * what a sound IS is decided and tested without a server.
 */
public final class SoundDefinitions {

    private SoundDefinitions() {
    }

    /** Everything of kind SOUND in {@code loaded}, parsed, without looking at the audio. */
    public static Result parse(LoadReport loaded) {
        return parse(loaded, PackFiles.NONE);
    }

    /**
     * Everything of kind SOUND in {@code loaded}, parsed, with each file's
     * headers read through {@code files}: its channel count, and its length
     * where the definition did not give one. See {@link OggInfo}.
     */
    public static Result parse(LoadReport loaded, PackFiles files) {
        Map<ContentId, SoundInfo> sounds = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        PackFiles reader = files == null ? PackFiles.NONE : files;
        List<String> namespaces = new ArrayList<>();
        for (PackMeta pack : loaded.packs()) {
            namespaces.add(pack.namespace());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.SOUND)) {
            parseOne(definition, diagnostics)
                    .map(sound -> measured(sound, definition, reader, namespaces, diagnostics))
                    .ifPresent(sound -> sounds.put(sound.id(), sound));
        }
        return new Result(Map.copyOf(sounds), List.copyOf(diagnostics));
    }

    /** Where the build puts a sound's audio, which is where {@link SoundAssets} looks for it too. */
    static String audioPath(SoundInfo sound) {
        String location = sound.file().indexOf(':') > 0
                ? sound.file() : sound.id().namespace() + ":" + sound.file();
        int colon = location.indexOf(':');
        return "assets/" + location.substring(0, colon) + "/sounds/" + location.substring(colon + 1) + ".ogg";
    }

    /**
     * The sound with what its file says added. A file that is not there is the
     * build's error to report, with the path, so it is passed over here.
     */
    private static SoundInfo measured(SoundInfo sound, ContentDefinition definition, PackFiles files,
                                      List<String> namespaces, List<Diagnostic> diagnostics) {
        String path = audioPath(sound);
        Optional<byte[]> bytes = files.find(path, namespaces);
        if (bytes.isEmpty()) {
            return sound;
        }
        Optional<OggInfo> read = OggInfo.read(bytes.get());
        if (read.isEmpty() || !read.get().vorbis()) {
            diagnostics.add(Diagnostic.warning(definition.origin(), definition.id().path(),
                    path + (read.isEmpty() ? " is not an Ogg file" : " is an Ogg file but not Vorbis (Opus, perhaps)")
                            + ", so it will play as silence. Minecraft only plays Ogg Vorbis: export it again as that."));
            return sound;
        }
        // A length somebody wrote wins: they may have trimmed a tail of
        // silence off the loop on purpose.
        SoundInfo out = sound.withChannels(read.get().channels());
        return out.length() > 0 ? out : out.withLength(read.get().seconds());
    }

    private static Optional<SoundInfo> parseOne(ContentDefinition definition, List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        // Default to a file of the same name, because a pack that writes
        // chime.ogg and calls it chime has said it once already.
        String file = body.string("file").orElse(definition.id().path());

        String category = body.string("category").orElse("master").trim().toLowerCase(Locale.ROOT);
        if (!SoundInfo.CATEGORIES.contains(category)) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "category: " + category + " is not one of " + sorted()
                            + ". Using master, which means the player's master slider."));
            category = "master";
        }

        float volume = number(body, "volume", 1f, 0f, 10f, origin, where, diagnostics);
        float pitch = number(body, "pitch", 1f, 0.5f, 2f, origin, where, diagnostics);

        // How long the file runs. Absent is zero, meaning "nobody said", and
        // parse() fills it from the file's own headers when it can read them;
        // a sound whose file it cannot read keeps the zero, which everything
        // that reads it treats as "play it once". See SoundInfo.length.
        float length = number(body, "length", 0f, 0f, 3600f, origin, where, diagnostics);

        return Optional.of(SoundInfo.of(definition.id(), file, category,
                body.string("subtitle").orElse(null), volume, pitch,
                body.bool("stream").orElse(Boolean.FALSE))
                .withLength(length));
    }

    private static String sorted() {
        List<String> names = new ArrayList<>(SoundInfo.CATEGORIES);
        java.util.Collections.sort(names);
        return String.join(", ", names);
    }

    private static float number(DefinitionNode body, String key, float fallback, float min, float max,
                                String origin, String where, List<Diagnostic> diagnostics) {
        Optional<String> declared = body.string(key);
        if (declared.isEmpty()) {
            return fallback;
        }
        float value;
        try {
            value = Float.parseFloat(declared.get().trim());
        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + declared.get() + " is not a number. Using " + fallback + "."));
            return fallback;
        }
        if (!Float.isFinite(value) || value < min || value > max) {
            float clamped = Math.max(min, Math.min(max, Float.isFinite(value) ? value : fallback));
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + declared.get() + " is outside " + min + " to " + max
                            + ". Using " + clamped + "."));
            return clamped;
        }
        return value;
    }

    /** The sounds, and what was wrong with the ones that are missing. */
    public static final class Result {

        private final Map<ContentId, SoundInfo> sounds;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, SoundInfo> sounds, List<Diagnostic> diagnostics) {
            this.sounds = sounds;
            this.diagnostics = diagnostics;
        }

        /** Every sound that parsed, keyed by id. */
        public Map<ContentId, SoundInfo> sounds() {
            return sounds;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
