package ai.resourcepack.engine.core.textfx;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The text effects that come ready-made: Studio's presets, written into this
 * jar by Studio's {@code gen-rpe-text-effects} script so that a content file
 * can say {@code preset: fire} and get exactly what Studio's preset button
 * gives. Generated: never edit {@code presets.json} by hand.
 */
public final class TextEffectPresets {

    /**
     * One preset.
     *
     * @param key    what a content file names it by; never renamed once shipped
     * @param name   what it is called, for a person
     * @param group  which shelf it sits on in Studio: motion, colour, material or disrupt
     * @param blurb  one line on what it does
     * @param sample the words it looks best on
     */
    public record Preset(String key, String name, String group, String blurb, String sample, int[] pad, String glsl) {
    }

    private static final class File {
        List<PresetJson> presets;
    }

    private static final class PresetJson {
        String key;
        String name;
        String group;
        String blurb;
        String sample;
        int[] pad;
        String glsl;
    }

    private static volatile TextEffectPresets loaded;

    private final Map<String, Preset> byKey;

    private TextEffectPresets(File file) {
        Map<String, Preset> read = new LinkedHashMap<>();
        for (PresetJson p : file.presets) {
            read.put(p.key, new Preset(p.key, p.name, p.group, p.blurb, p.sample, p.pad, p.glsl));
        }
        this.byKey = read;
    }

    /** The jar's, read once. */
    public static TextEffectPresets get() {
        TextEffectPresets here = loaded;
        if (here == null) {
            synchronized (TextEffectPresets.class) {
                here = loaded;
                if (here == null) {
                    try (InputStream in = TextEffectPresets.class.getResourceAsStream("/text-effects/presets.json")) {
                        if (in == null) {
                            throw new IllegalStateException("The jar is missing text-effects/presets.json");
                        }
                        here = new TextEffectPresets(new Gson().fromJson(
                                new String(in.readAllBytes(), StandardCharsets.UTF_8), File.class));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    loaded = here;
                }
            }
        }
        return here;
    }

    /** By key, ignoring case. */
    public Optional<Preset> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    /** Every preset, in Studio's order. */
    public List<Preset> all() {
        return new ArrayList<>(byKey.values());
    }
}
