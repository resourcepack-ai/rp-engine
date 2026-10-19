package ai.resourcepack.engine.core.vfx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A VFX: a timeline of sprite layers, motes and sounds, played in the caster's
 * frame (origin at their feet, +z the way they faced, +y up, +x to their left),
 * twenty ticks a second.
 *
 * <p>Studio's recipe format, and {@link #clean} is a port of its
 * {@code normalizeVfxRecipe}: the only door a recipe comes in by, whether it
 * was pushed from Studio or written in a content folder. Every number is
 * clamped, every unknown field dropped and every layer that cannot be drawn
 * left out, exactly as Studio does it, so that a recipe means the same thing
 * at both ends. {@code VfxPortTest} runs Studio's own untidy cases through it.
 */
public final class VfxRecipe {

    static final String[] EASES = {"linear", "in", "out", "inout", "back", "step"};
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern PALETTE_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,23}$");
    private static final Pattern SOUND = Pattern.compile("^[a-z0-9_.:/-]{1,96}$");

    /** A value over a layer's life: a constant, or keyframes. Values are one number or three. */
    static final class Track {
        final double[] constant;
        final double[] times;
        final double[][] values;
        /** The ease INTO each key, or null for linear. */
        final String[] eases;

        Track(double[] constant) {
            this.constant = constant;
            this.times = null;
            this.values = null;
            this.eases = null;
        }

        Track(double[] times, double[][] values, String[] eases) {
            this.constant = null;
            this.times = times;
            this.values = values;
            this.eases = eases;
        }

        boolean keyed() {
            return constant == null;
        }
    }

    static final class Copies {
        int count;
        double[] arc;
        Double radius;
        double[] step;
        Double scaleStep;
        double[] scatter;
        boolean randomYaw;
        Double stagger;
        Double jitter;
        Integer seed;
    }

    static final class Sound {
        int t;
        String sound;
        Double volume;
        Double pitch;
        double[] at;
    }

    static final class Palette {
        String deep;
        String mid;
        String bright;
    }

    /** A sprite layer or a motes layer; the fields of the other are left unset. */
    static final class Layer {
        boolean motes;
        String name;
        int start;
        int end;
        String palette;
        Copies copies;
        List<Sound> sounds;
        // sprite
        String sprite;
        boolean billboard;
        boolean faceMotion;
        Track pos;
        Track rot;
        double[] spin;
        Track scale;
        Track fade;
        boolean shuffle;
        Double flicker;
        // motes
        String kind;
        Integer burst;
        Double rate;
        double[] at;
        String shape;
        double[] size;
        boolean sizeNumber;
        double[] dir;
        double spread;
        double[] speed;
        Double gravity;
        double drag;
        Double swirl;
        double[] toward;
        Double pull;
        double[] moteScale;
        Double length;
        double[] life;
    }

    int duration;
    /** The ticks that repeat on a loop, [start, end), or null for the whole effect. */
    int[] loop;
    final Map<String, Palette> palettes = new LinkedHashMap<>();
    final List<Layer> layers = new ArrayList<>();
    List<Sound> sounds;

    /** What the cleaner changed or refused, in words for whoever wrote the recipe. */
    private final List<String> problems = new ArrayList<>();

    private VfxRecipe() {
    }

    /** Ticks the effect lasts (motes started near the end may outlive it a little). */
    public int duration() {
        return duration;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** The stretch that repeats on a loop, [start, end) in ticks, or null when the whole effect does. */
    public int[] loopSection() {
        return loop == null ? null : loop.clone();
    }

    /** Whether anything in it can be drawn. A recipe with nothing left is refused rather than played as nothing. */
    public boolean drawable() {
        return !layers.isEmpty();
    }

    // ---------------------------------------------------------------- cleaning

    /** The recipe, cleaned. Never throws: anything unreadable is an empty, undrawable recipe with a problem. */
    public static VfxRecipe clean(JsonElement raw) {
        VfxData data = VfxData.get();
        VfxData.Limits lim = data.limits();
        VfxRecipe out = new VfxRecipe();
        JsonObject r = raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : new JsonObject();
        int duration = integer(r.get("duration"), 10, lim.duration(), 60);
        out.duration = duration;

        JsonElement rawPalettes = r.get("palettes");
        if (rawPalettes != null && rawPalettes.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : rawPalettes.getAsJsonObject().entrySet()) {
                if (out.palettes.size() >= lim.palettes()) {
                    break;
                }
                String k = e.getKey().trim().toLowerCase(Locale.ROOT);
                if (!PALETTE_KEY.matcher(k).matches() || data.isPresetPalette(k)) {
                    out.problems.add("palette \"" + e.getKey() + "\" needs a lower-case name that isn't a preset's");
                    continue;
                }
                JsonObject p = e.getValue() != null && e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : new JsonObject();
                String deep = hex(p.get("deep")), mid = hex(p.get("mid")), bright = hex(p.get("bright"));
                if (deep == null || mid == null || bright == null) {
                    out.problems.add("palette \"" + e.getKey() + "\" needs deep, mid and bright as #rrggbb");
                    continue;
                }
                Palette pal = new Palette();
                pal.deep = deep;
                pal.mid = mid;
                pal.bright = bright;
                out.palettes.put(k, pal);
            }
        }

        JsonElement rawLayers = r.get("layers");
        JsonArray list = rawLayers != null && rawLayers.isJsonArray() ? rawLayers.getAsJsonArray() : new JsonArray();
        if (list.size() > lim.layers()) {
            out.problems.add("only the first " + lim.layers() + " layers are kept");
        }
        for (int index = 0; index < Math.min(list.size(), lim.layers()); index++) {
            JsonElement rawLayer = list.get(index);
            if (rawLayer == null || !rawLayer.isJsonObject()) {
                continue;
            }
            Layer layer = out.layer(rawLayer.getAsJsonObject(), index, duration, data);
            if (layer != null) {
                out.layers.add(layer);
            }
        }
        if (out.layers.isEmpty()) {
            out.problems.add("nothing in it can be drawn");
        }
        out.sounds = sounds(r.get("sounds"), lim);
        if (defined(r, "loop")) {
            double[] p = pair(r.get("loop"), 0, duration, new double[] {0, duration});
            int start = (int) VfxMath.round(p[0]), end = (int) VfxMath.round(p[1]);
            if (end - start >= 2) {
                out.loop = new int[] {start, end};
            } else {
                out.problems.add("a loop needs a start and an end at least two ticks apart; it was left out");
            }
        }
        return out;
    }

    private Layer layer(JsonObject l, int index, int duration, VfxData data) {
        VfxData.Limits lim = data.limits();
        double reach = lim.reach();
        Layer layer = new Layer();
        layer.name = text(l.get("name"), 40);
        layer.start = integer(l.get("start"), 0, duration, 0);
        layer.end = integer(l.get("end"), layer.start + 1, lim.duration(), duration);
        layer.palette = paletteOf(l.get("palette"), index, data);
        layer.copies = copies(l.get("copies"), lim);
        layer.sounds = sounds(l.get("sounds"), lim);
        if ("motes".equals(string(l.get("type")))) {
            layer.motes = true;
            String kind = string(l.get("kind"));
            layer.kind = "streak".equals(kind) || "tumble".equals(kind) ? kind : "glow";
            String sprite = string(l.get("sprite"));
            layer.sprite = data.sprite(sprite).isPresent() ? sprite : null;
            String shape = string(l.get("shape"));
            layer.shape = "sphere".equals(shape) || "ring".equals(shape) || "disc".equals(shape) || "box".equals(shape)
                    ? shape : "point";
            Integer burst = defined(l, "burst") ? integer(l.get("burst"), 0, lim.motes(), 0) : null;
            Double rate = defined(l, "rate") ? num(l.get("rate"), 0, 8, 0) : null;
            if ((burst == null || burst == 0) && (rate == null || rate == 0)) {
                problems.add("layer " + index + ": a motes layer needs burst or rate, or it starts nothing");
                return null;
            }
            layer.burst = burst != null && burst != 0 ? burst : null;
            layer.rate = rate != null && rate != 0 ? rate : null;
            layer.at = vec(l.get("at"), -reach, reach, new double[] {0, 1, 0});
            if (defined(l, "size")) {
                JsonElement size = l.get("size");
                if (isNumber(size)) {
                    double s = num(size, 0, reach, 1);
                    layer.size = new double[] {s, s, s};
                    layer.sizeNumber = true;
                } else {
                    layer.size = vec(size, 0, reach, new double[] {1, 1, 1});
                }
            }
            if (defined(l, "dir")) {
                layer.dir = vec(l.get("dir"), -1, 1, new double[] {0, 1, 0});
            }
            layer.spread = num(l.get("spread"), 0, 1, defined(l, "dir") ? 0.2 : 1);
            layer.speed = pair(l.get("speed"), 0, 4, new double[] {0.1, 0.3});
            if (defined(l, "gravity")) {
                layer.gravity = num(l.get("gravity"), -0.5, 0.5, 0);
            }
            layer.drag = num(l.get("drag"), 0, 1, 0.9);
            if (defined(l, "swirl")) {
                layer.swirl = num(l.get("swirl"), -2, 2, 0);
            }
            if (defined(l, "toward")) {
                layer.toward = vec(l.get("toward"), -reach, reach, new double[] {0, 1, 0});
                layer.pull = num(l.get("pull"), 0, 2, 0.05);
            }
            layer.moteScale = pair(l.get("scale"), 0.02, 8,
                    "streak".equals(layer.kind) ? new double[] {0.08, 0.15} : new double[] {0.15, 0.35});
            if ("streak".equals(layer.kind)) {
                layer.length = num(l.get("length"), 0.1, 8, 1.2);
            }
            layer.life = pair(l.get("life"), 1, lim.duration(), new double[] {10, 20});
            return layer;
        }
        String sprite = string(l.get("sprite"));
        if (data.sprite(sprite).isEmpty()) {
            problems.add("layer " + index + ": no sprite called \"" + (l.has("sprite") ? jsString(l.get("sprite")) : "undefined") + "\"");
            return null;
        }
        layer.sprite = sprite;
        layer.billboard = isTrue(l.get("billboard"));
        layer.faceMotion = "motion".equals(string(l.get("face"))) && !layer.billboard;
        layer.pos = track(l.get("pos"), vecValue(-reach, reach), lim, new double[] {0, 0, 0});
        if (defined(l, "rot")) {
            layer.rot = track(l.get("rot"), vecValue(-3600, 3600), lim, new double[] {0, 0, 0});
        }
        if (defined(l, "spin")) {
            layer.spin = vec(l.get("spin"), -90, 90, new double[] {0, 0, 0});
        }
        double maxScale = lim.scale();
        layer.scale = track(l.get("scale"), raw -> {
            if (isNumber(raw) && Double.isFinite(raw.getAsDouble())) {
                return new double[] {num(raw, 0, maxScale, 1)};
            }
            return vecValue(0, maxScale).read(raw);
        }, lim, new double[] {1});
        if (defined(l, "fade")) {
            layer.fade = track(l.get("fade"), raw -> isNumber(raw) && Double.isFinite(raw.getAsDouble())
                    ? new double[] {num(raw, 0, 1, 0)} : null, lim, new double[] {0});
        }
        layer.shuffle = isTrue(l.get("shuffle"));
        if (defined(l, "flicker")) {
            layer.flicker = num(l.get("flicker"), 0, 0.9, 0);
        }
        if (!data.spriteOf(sprite).colour()) {
            layer.palette = null;
        }
        return layer;
    }

    private String paletteOf(JsonElement raw, int index, VfxData data) {
        String p = raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()
                ? raw.getAsString().trim().toLowerCase(Locale.ROOT) : "";
        if (p.isEmpty()) {
            return "sun";
        }
        if (data.isPresetPalette(p) || palettes.containsKey(p)) {
            return p;
        }
        problems.add("layer " + index + ": no palette called \"" + jsString(raw) + "\"; it is drawn in sun");
        return "sun";
    }

    private static Copies copies(JsonElement raw, VfxData.Limits lim) {
        if (raw == null || !(raw.isJsonObject() || raw.isJsonArray())) {
            return null;
        }
        JsonObject r = raw.isJsonObject() ? raw.getAsJsonObject() : new JsonObject();
        int count = integer(r.get("count"), 1, lim.copies(), 1);
        if (count <= 1 && !defined(r, "scatter") && !isTrue(r.get("randomYaw")) && !defined(r, "radius")) {
            return null;
        }
        double reach = lim.reach();
        Copies c = new Copies();
        c.count = count;
        if (defined(r, "arc")) {
            c.arc = pair(r.get("arc"), -720, 720, new double[] {0, 360});
        }
        if (defined(r, "radius")) {
            c.radius = num(r.get("radius"), 0, reach, 0);
        }
        if (defined(r, "step")) {
            c.step = vec(r.get("step"), -reach, reach, new double[] {0, 0, 0});
        }
        if (defined(r, "scaleStep")) {
            c.scaleStep = num(r.get("scaleStep"), -0.5, 2, 0);
        }
        if (defined(r, "scatter")) {
            c.scatter = vec(r.get("scatter"), 0, reach, new double[] {0, 0, 0});
        }
        c.randomYaw = isTrue(r.get("randomYaw"));
        if (defined(r, "stagger")) {
            c.stagger = num(r.get("stagger"), 0, 60, 0);
        }
        if (defined(r, "jitter")) {
            c.jitter = num(r.get("jitter"), 0, 60, 0);
        }
        if (defined(r, "seed")) {
            c.seed = integer(r.get("seed"), 0, 1_000_000, 0);
        }
        return c;
    }

    private static List<Sound> sounds(JsonElement raw, VfxData.Limits lim) {
        if (raw == null || !raw.isJsonArray()) {
            return null;
        }
        List<Sound> out = new ArrayList<>();
        for (JsonElement el : raw.getAsJsonArray()) {
            if (el == null || !(el.isJsonObject() || el.isJsonArray())) {
                continue;
            }
            JsonObject r = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
            String sound = string(r.get("sound"));
            sound = sound == null ? "" : sound.trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
            if (!SOUND.matcher(sound).matches()) {
                continue;
            }
            Sound s = new Sound();
            s.t = integer(r.get("t"), 0, lim.duration(), 0);
            s.sound = sound;
            if (defined(r, "volume")) {
                s.volume = num(r.get("volume"), 0, 4, 1);
            }
            if (defined(r, "pitch")) {
                s.pitch = num(r.get("pitch"), 0.5, 2, 1);
            }
            if (defined(r, "at")) {
                s.at = vec(r.get("at"), -lim.reach(), lim.reach(), new double[] {0, 0, 0});
            }
            out.add(s);
            if (out.size() >= lim.sounds()) {
                break;
            }
        }
        return out.isEmpty() ? null : out;
    }

    private interface Reader {
        double[] read(JsonElement raw);
    }

    private static Reader vecValue(double lo, double hi) {
        return raw -> {
            if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != 3) {
                return null;
            }
            for (JsonElement n : raw.getAsJsonArray()) {
                if (!Double.isFinite(jsNumber(n))) {
                    return null;
                }
            }
            return vec(raw, lo, hi, new double[] {0, 0, 0});
        };
    }

    /** A track, cleaned: a constant passes through {@code value}; keys are cleaned, capped and sorted. */
    private static Track track(JsonElement raw, Reader value, VfxData.Limits lim, double[] fallback) {
        if (raw != null && raw.isJsonArray() && raw.getAsJsonArray().size() > 0 && raw.getAsJsonArray().get(0).isJsonArray()) {
            List<double[]> values = new ArrayList<>();
            List<Double> times = new ArrayList<>();
            List<String> eases = new ArrayList<>();
            for (JsonElement k : raw.getAsJsonArray()) {
                if (!k.isJsonArray() || k.getAsJsonArray().size() < 2) {
                    continue;
                }
                JsonArray key = k.getAsJsonArray();
                double[] v = value.read(key.get(1));
                if (v == null) {
                    continue;
                }
                times.add(num(key.get(0), 0, lim.duration(), 0));
                values.add(v);
                String ease = key.size() > 2 ? string(key.get(2)) : null;
                eases.add(ease != null && List.of(EASES).contains(ease) ? ease : null);
                if (values.size() >= lim.keys()) {
                    break;
                }
            }
            if (values.isEmpty()) {
                return new Track(fallback);
            }
            // A stable sort by time, as JavaScript's is.
            Integer[] order = new Integer[values.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> Double.compare(times.get(a), times.get(b)));
            double[] t = new double[order.length];
            double[][] v = new double[order.length][];
            String[] e = new String[order.length];
            for (int i = 0; i < order.length; i++) {
                t[i] = times.get(order[i]);
                v[i] = values.get(order[i]);
                e[i] = eases.get(order[i]);
            }
            return new Track(t, v, e);
        }
        double[] v = value.read(raw);
        return new Track(v != null ? v : fallback);
    }

    // ---- JavaScript's view of a JSON value

    /** {@code Number(raw)}, for the kinds of value JSON has. */
    private static double jsNumber(JsonElement raw) {
        if (raw == null) {
            return Double.NaN;
        }
        if (raw.isJsonNull()) {
            return 0;
        }
        if (raw.isJsonPrimitive()) {
            JsonPrimitive p = raw.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean() ? 1 : 0;
            }
            if (p.isNumber()) {
                return p.getAsDouble();
            }
            return stringNumber(p.getAsString());
        }
        if (raw.isJsonArray()) {
            JsonArray a = raw.getAsJsonArray();
            if (a.isEmpty()) {
                return 0;
            }
            return a.size() == 1 ? jsNumber(a.get(0).isJsonArray() ? a.get(0) : new JsonPrimitive(jsString(a.get(0)))) : Double.NaN;
        }
        return Double.NaN;
    }

    private static double stringNumber(String s) {
        String t = s.strip();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            if (t.matches("^0[xX][0-9a-fA-F]+$")) {
                return Long.parseLong(t.substring(2), 16);
            }
            if (!t.matches("^[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)$")) {
                return Double.NaN;
            }
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String jsString(JsonElement raw) {
        if (raw == null) {
            return "undefined";
        }
        if (raw.isJsonNull()) {
            return "null";
        }
        if (raw.isJsonPrimitive()) {
            return raw.getAsString();
        }
        return raw.toString();
    }

    private static boolean defined(JsonObject o, String key) {
        return o.has(key);
    }

    private static boolean isNumber(JsonElement raw) {
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber();
    }

    private static boolean isTrue(JsonElement raw) {
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean() && raw.getAsBoolean();
    }

    private static String string(JsonElement raw) {
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString() ? raw.getAsString() : null;
    }

    private static String hex(JsonElement raw) {
        String s = string(raw);
        return s != null && HEX.matcher(s).matches() ? s.toLowerCase(Locale.ROOT) : null;
    }

    private static String text(JsonElement raw, int max) {
        String s = string(raw);
        if (s == null) {
            return null;
        }
        String t = s.strip();
        t = t.length() > max ? t.substring(0, max) : t;
        return t.isEmpty() ? null : t;
    }

    static double num(JsonElement raw, double lo, double hi, double fallback) {
        double n = isNumber(raw) ? raw.getAsDouble()
                : raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString() ? stringNumber(raw.getAsString())
                : Double.NaN;
        if (!Double.isFinite(n)) {
            return fallback;
        }
        return Math.min(hi, Math.max(lo, n));
    }

    static int integer(JsonElement raw, double lo, double hi, double fallback) {
        return (int) VfxMath.round(num(raw, lo, hi, fallback));
    }

    static double[] vec(JsonElement raw, double lo, double hi, double[] fallback) {
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != 3) {
            return fallback.clone();
        }
        JsonArray a = raw.getAsJsonArray();
        return new double[] {num(a.get(0), lo, hi, fallback[0]), num(a.get(1), lo, hi, fallback[1]), num(a.get(2), lo, hi, fallback[2])};
    }

    static double[] pair(JsonElement raw, double lo, double hi, double[] fallback) {
        if (isNumber(raw)) {
            double n = num(raw, lo, hi, fallback[0]);
            return new double[] {n, n};
        }
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != 2) {
            return fallback.clone();
        }
        double a = num(raw.getAsJsonArray().get(0), lo, hi, fallback[0]);
        double b = num(raw.getAsJsonArray().get(1), lo, hi, fallback[1]);
        return a <= b ? new double[] {a, b} : new double[] {b, a};
    }

    // ---------------------------------------------------------------- reading

    /** Every (sprite, palette) the recipe draws: what the pack has to carry for it. Palette is null for a sprite with its own colours. */
    List<String[]> spritesUsed() {
        VfxData data = VfxData.get();
        Map<String, String[]> seen = new LinkedHashMap<>();
        for (Layer layer : layers) {
            String sprite = layer.motes ? moteSprite(layer) : layer.sprite;
            String palette = data.spriteOf(sprite).colour() ? (layer.palette != null ? layer.palette : "sun") : null;
            seen.put(sprite + "|" + palette, new String[] {sprite, palette});
        }
        return new ArrayList<>(seen.values());
    }

    static String moteSprite(Layer layer) {
        return "glow".equals(layer.kind) ? "glow" : "streak".equals(layer.kind) ? "streak" : layer.sprite != null ? layer.sprite : "petal";
    }

    /** Every sound event it plays, for stopping them when it ends. */
    List<String> soundKeys() {
        List<String> keys = new ArrayList<>();
        if (sounds != null) {
            sounds.forEach(s -> keys.add(s.sound));
        }
        for (Layer layer : layers) {
            if (layer.sounds != null) {
                layer.sounds.forEach(s -> keys.add(s.sound));
            }
        }
        return keys;
    }

    // ---------------------------------------------------------------- writing

    /** The recipe as Studio would store it: what a test compares against, and what {@code /rp vfx} could show. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("duration", duration);
        if (loop != null) {
            JsonArray l = new JsonArray();
            l.add(loop[0]);
            l.add(loop[1]);
            o.add("loop", l);
        }
        if (!palettes.isEmpty()) {
            JsonObject p = new JsonObject();
            palettes.forEach((k, pal) -> {
                JsonObject one = new JsonObject();
                one.addProperty("deep", pal.deep);
                one.addProperty("mid", pal.mid);
                one.addProperty("bright", pal.bright);
                p.add(k, one);
            });
            o.add("palettes", p);
        }
        JsonArray ls = new JsonArray();
        for (Layer l : layers) {
            JsonObject j = new JsonObject();
            j.addProperty("type", l.motes ? "motes" : "sprite");
            if (l.name != null) {
                j.addProperty("name", l.name);
            }
            j.addProperty("start", l.start);
            j.addProperty("end", l.end);
            if (l.palette != null) {
                j.addProperty("palette", l.palette);
            }
            if (l.copies != null) {
                j.add("copies", copiesJson(l.copies));
            }
            if (l.sounds != null) {
                j.add("sounds", soundsJson(l.sounds));
            }
            if (l.motes) {
                j.addProperty("kind", l.kind);
                if (l.sprite != null) {
                    j.addProperty("sprite", l.sprite);
                }
                if (l.burst != null) {
                    j.addProperty("burst", l.burst);
                }
                if (l.rate != null) {
                    j.addProperty("rate", l.rate);
                }
                j.add("at", vecJson(l.at));
                j.addProperty("shape", l.shape);
                if (l.size != null) {
                    j.add("size", l.sizeNumber ? new JsonPrimitive(l.size[0]) : vecJson(l.size));
                }
                if (l.dir != null) {
                    j.add("dir", vecJson(l.dir));
                }
                j.addProperty("spread", l.spread);
                j.add("speed", vecJson(l.speed));
                if (l.gravity != null) {
                    j.addProperty("gravity", l.gravity);
                }
                j.addProperty("drag", l.drag);
                if (l.swirl != null) {
                    j.addProperty("swirl", l.swirl);
                }
                if (l.toward != null) {
                    j.add("toward", vecJson(l.toward));
                    j.addProperty("pull", l.pull);
                }
                j.add("scale", vecJson(l.moteScale));
                if (l.length != null) {
                    j.addProperty("length", l.length);
                }
                j.add("life", vecJson(l.life));
            } else {
                j.addProperty("sprite", l.sprite);
                if (l.billboard) {
                    j.addProperty("billboard", true);
                }
                if (l.faceMotion) {
                    j.addProperty("face", "motion");
                }
                j.add("pos", trackJson(l.pos));
                if (l.rot != null) {
                    j.add("rot", trackJson(l.rot));
                }
                if (l.spin != null) {
                    j.add("spin", vecJson(l.spin));
                }
                j.add("scale", trackJson(l.scale));
                if (l.fade != null) {
                    j.add("fade", trackJson(l.fade));
                }
                if (l.shuffle) {
                    j.addProperty("shuffle", true);
                }
                if (l.flicker != null) {
                    j.addProperty("flicker", l.flicker);
                }
            }
            ls.add(j);
        }
        o.add("layers", ls);
        if (sounds != null) {
            o.add("sounds", soundsJson(sounds));
        }
        return o;
    }

    private static JsonElement valueJson(double[] v) {
        return v.length == 1 ? new JsonPrimitive(v[0]) : vecJson(v);
    }

    private static JsonArray vecJson(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) {
            a.add(d);
        }
        return a;
    }

    private static JsonElement trackJson(Track t) {
        if (!t.keyed()) {
            return valueJson(t.constant);
        }
        JsonArray keys = new JsonArray();
        for (int i = 0; i < t.times.length; i++) {
            JsonArray k = new JsonArray();
            k.add(t.times[i]);
            k.add(valueJson(t.values[i]));
            if (t.eases[i] != null) {
                k.add(t.eases[i]);
            }
            keys.add(k);
        }
        return keys;
    }

    private static JsonObject copiesJson(Copies c) {
        JsonObject j = new JsonObject();
        j.addProperty("count", c.count);
        if (c.arc != null) {
            j.add("arc", vecJson(c.arc));
        }
        if (c.radius != null) {
            j.addProperty("radius", c.radius);
        }
        if (c.step != null) {
            j.add("step", vecJson(c.step));
        }
        if (c.scaleStep != null) {
            j.addProperty("scaleStep", c.scaleStep);
        }
        if (c.scatter != null) {
            j.add("scatter", vecJson(c.scatter));
        }
        if (c.randomYaw) {
            j.addProperty("randomYaw", true);
        }
        if (c.stagger != null) {
            j.addProperty("stagger", c.stagger);
        }
        if (c.jitter != null) {
            j.addProperty("jitter", c.jitter);
        }
        if (c.seed != null) {
            j.addProperty("seed", c.seed);
        }
        return j;
    }

    private static JsonArray soundsJson(List<Sound> sounds) {
        JsonArray a = new JsonArray();
        for (Sound s : sounds) {
            JsonObject j = new JsonObject();
            j.addProperty("t", s.t);
            j.addProperty("sound", s.sound);
            if (s.volume != null) {
                j.addProperty("volume", s.volume);
            }
            if (s.pitch != null) {
                j.addProperty("pitch", s.pitch);
            }
            if (s.at != null) {
                j.add("at", vecJson(s.at));
            }
            a.add(j);
        }
        return a;
    }
}
