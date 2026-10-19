package ai.resourcepack.engine.core.vfx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * What a VFX is drawn with, as Studio defines it: the sprites, the shapes they
 * are drawn on, the palettes, the presets and the limits. Written into this jar
 * by Studio's {@code gen-rpe-vfx} script ({@code vfx/vfx.json}); never edit it
 * by hand, and never restate any of it in code, because a sprite whose level
 * count differs from Studio's picks the wrong picture on every tick.
 */
public final class VfxData {

    /**
     * One sprite.
     *
     * @param model  the shape it is drawn on: plane, flat, cross, cylinder or cube
     * @param levels how many pictures it has; a level is picked per tick by custom_model_data
     * @param frames animation frames in each level's texture (the pillar scrolls)
     * @param age    whether its levels are age (new to old) rather than fade
     * @param colour whether it is painted in a palette; a sprite that is not has its own colours
     * @param shade  lit by the world (rock) rather than glowing
     * @param light  pure light, whose dim edge fades out rather than darkening
     */
    public record Sprite(String name, String model, int w, int h, int levels, int frames, boolean age, boolean colour,
                         boolean shade, boolean light) {
    }

    /** A ready-made effect: what a content file names it by, and its recipe as Studio wrote it. */
    public record Preset(String key, String name, String blurb, JsonObject recipe) {
    }

    /** The limits every recipe is held to; each is a cost somewhere, in entities or in packets. */
    public record Limits(int duration, int layers, int copies, int keys, int motes, int sounds, int palettes, int live,
                         double reach, double scale) {
    }

    private static volatile VfxData loaded;

    private final Map<String, Sprite> sprites;
    private final Map<String, JsonArray> models;
    private final Map<String, double[][]> palettes;
    private final Map<String, Preset> presets;
    private final Limits limits;

    private VfxData(JsonObject file) {
        Map<String, Sprite> sprites = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : file.getAsJsonObject("sprites").entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            sprites.put(e.getKey(), new Sprite(e.getKey(), s.get("model").getAsString(), s.get("w").getAsInt(),
                    s.get("h").getAsInt(), s.get("levels").getAsInt(), s.get("frames").getAsInt(),
                    s.get("age").getAsBoolean(), s.get("colour").getAsBoolean(), s.get("shade").getAsBoolean(),
                    s.get("light").getAsBoolean()));
        }
        this.sprites = Map.copyOf(sprites);
        Map<String, JsonArray> models = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : file.getAsJsonObject("models").entrySet()) {
            models.put(e.getKey(), e.getValue().getAsJsonArray());
        }
        this.models = Map.copyOf(models);
        Map<String, double[][]> palettes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : file.getAsJsonObject("palettes").entrySet()) {
            JsonArray stops = e.getValue().getAsJsonArray();
            double[][] out = new double[stops.size()][];
            for (int i = 0; i < stops.size(); i++) {
                JsonArray stop = stops.get(i).getAsJsonArray();
                out[i] = new double[stop.size()];
                for (int k = 0; k < stop.size(); k++) {
                    out[i][k] = stop.get(k).getAsDouble();
                }
            }
            palettes.put(e.getKey(), out);
        }
        this.palettes = palettes;
        Map<String, Preset> presets = new LinkedHashMap<>();
        for (JsonElement el : file.getAsJsonArray("presets")) {
            JsonObject p = el.getAsJsonObject();
            presets.put(p.get("key").getAsString(), new Preset(p.get("key").getAsString(), p.get("name").getAsString(),
                    p.get("blurb").getAsString(), p.getAsJsonObject("recipe")));
        }
        this.presets = presets;
        JsonObject l = file.getAsJsonObject("limits");
        this.limits = new Limits(l.get("duration").getAsInt(), l.get("layers").getAsInt(), l.get("copies").getAsInt(),
                l.get("keys").getAsInt(), l.get("motes").getAsInt(), l.get("sounds").getAsInt(),
                l.get("palettes").getAsInt(), l.get("live").getAsInt(), l.get("reach").getAsDouble(),
                l.get("scale").getAsDouble());
    }

    /** The jar's, read once. */
    public static VfxData get() {
        VfxData here = loaded;
        if (here == null) {
            synchronized (VfxData.class) {
                here = loaded;
                if (here == null) {
                    try (InputStream in = VfxData.class.getResourceAsStream("/vfx/vfx.json")) {
                        if (in == null) {
                            throw new IllegalStateException("The jar is missing vfx/vfx.json");
                        }
                        here = new VfxData(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                                .getAsJsonObject());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    loaded = here;
                }
            }
        }
        return here;
    }

    public Optional<Sprite> sprite(String name) {
        return Optional.ofNullable(name == null ? null : sprites.get(name));
    }

    Sprite spriteOf(String name) {
        Sprite s = sprites.get(name);
        if (s == null) {
            throw new IllegalArgumentException("No VFX sprite called " + name);
        }
        return s;
    }

    /** The block-model elements a shape is drawn with, as Studio writes them. */
    JsonArray model(String model) {
        return models.get(model);
    }

    boolean isPresetPalette(String name) {
        return name != null && palettes.containsKey(name);
    }

    double[][] presetStops(String name) {
        double[][] stops = palettes.get(name);
        return stops != null ? stops : palettes.get("sun");
    }

    public List<Preset> presets() {
        return new ArrayList<>(presets.values());
    }

    public Optional<Preset> preset(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(presets.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    public Limits limits() {
        return limits;
    }

    /** The level a fade (0..1) shows, as the game's range_dispatch picks it. */
    int level(String sprite, double fade) {
        int levels = spriteOf(sprite).levels();
        return (int) Math.max(0, Math.min(levels - 1, Math.floor(fade * levels)));
    }
}
