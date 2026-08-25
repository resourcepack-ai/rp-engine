package ai.resourcepack.engine.core.sound;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.SoundInfo;

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

    /** Everything of kind SOUND in {@code loaded}, parsed. */
    public static Result parse(LoadReport loaded) {
        Map<ContentId, SoundInfo> sounds = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.SOUND)) {
            parseOne(definition, diagnostics).ifPresent(sound -> sounds.put(sound.id(), sound));
        }
        return new Result(Map.copyOf(sounds), List.copyOf(diagnostics));
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

        return Optional.of(SoundInfo.of(definition.id(), file, category,
                body.string("subtitle").orElse(null), volume, pitch,
                body.bool("stream").orElse(Boolean.FALSE)));
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
