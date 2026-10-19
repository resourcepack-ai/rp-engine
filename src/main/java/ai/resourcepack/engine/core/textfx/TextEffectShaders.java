package ai.resourcepack.engine.core.textfx;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The text shader that draws a content folder's text effects, for every
 * version of the game this jar has one for.
 *
 * <p><strong>The shader is Studio's.</strong> An effect is one GLSL function
 * inside the game's text shader, and everything around it (vanilla's own file
 * for that version, the vertex work that finds a letter, the library every
 * effect calls) is the same whichever effects a pack has. So Studio writes
 * that file once per version group into this jar, with two stretches cut out
 * ({@code text-effects/} in the resources, from Studio's
 * {@code gen-rpe-text-effects} script), and this fills them back in: the pad
 * functions, and each effect's renamed source with the dispatch that calls
 * it. Same effects in, Studio's exact file out; the test holds it to that.
 *
 * <p>Those resources are generated: never edit them by hand. A new Minecraft
 * version is a re-run of Studio's script, not a change here.
 */
public final class TextEffectShaders {

    /** One effect as the shader takes it: a name for the comment above it, its index, its source and its room. */
    public record Effect(String name, int index, String glsl, int[] pad) {
    }

    /**
     * One version group: the overlay directory it goes in when it is not the
     * pack's base, the resource pack formats it is for, and its files,
     * relative to {@code assets/minecraft/shaders/}.
     */
    public record Group(String directory, int minFormat, int maxFormat, List<String> versions, List<String> files) {
        boolean covers(int format) {
            return format >= minFormat && format <= maxFormat;
        }
    }

    /** {@code shaders.json}'s shape. */
    private static final class Index {
        Map<String, String> markers;
        List<String> libraryNames;
        Limits limits;
        List<String> colors;
        List<GroupJson> groups;
    }

    /** A group as the file has it. Not the record: the Gson of an older server cannot build one. */
    private static final class GroupJson {
        String directory;
        int minFormat;
        int maxFormat;
        List<String> versions;
        List<String> files;
    }

    private static final class Limits {
        int maxId;
        int maxPad;
        int maxSource;
        int maxLoop;
    }

    private static final String ROOT = "/text-effects/";

    private static volatile TextEffectShaders loaded;

    private final Index index;
    private final List<Group> groups;
    private final Map<String, String> templates = new LinkedHashMap<>();

    private TextEffectShaders(Index index) {
        this.index = index;
        List<Group> read = new ArrayList<>();
        for (GroupJson g : index.groups) {
            read.add(new Group(g.directory, g.minFormat, g.maxFormat, List.copyOf(g.versions), List.copyOf(g.files)));
        }
        this.groups = List.copyOf(read);
        for (Group group : groups) {
            for (String file : group.files()) {
                templates.put(group.minFormat() + "/" + file, resource(group.minFormat() + "/" + file));
            }
        }
    }

    /** The jar's, read once. */
    public static TextEffectShaders get() {
        TextEffectShaders here = loaded;
        if (here == null) {
            synchronized (TextEffectShaders.class) {
                here = loaded;
                if (here == null) {
                    here = new TextEffectShaders(new Gson().fromJson(resource("shaders.json"), Index.class));
                    loaded = here;
                }
            }
        }
        return here;
    }

    /** How many effects one pack's shader can tell apart: the colour has room for this many. */
    public int maxEffects() {
        return index.limits.maxId;
    }

    /** The most room an effect may ask for on any side, in its letter's pixels. */
    public int maxPad() {
        return index.limits.maxPad;
    }

    /** The colour that IS the effect at {@code index}, as {@code #rrggbb}. Studio's table, not a sum done here. */
    public String color(int index) {
        return this.index.colors.get(index);
    }

    /** The library's names, which an effect may call and which the linker renames. */
    public List<String> libraryNames() {
        return index.libraryNames;
    }

    /** The version groups, oldest first. */
    public List<Group> groups() {
        return groups;
    }

    /** What is wrong with an effect's source; empty when nothing is. */
    public List<String> problems(String glsl) {
        return TextEffectGlsl.problems(glsl, index.limits.maxLoop, index.limits.maxSource);
    }

    /**
     * Every file of {@code group}'s shader carrying {@code effects}, keyed
     * relative to {@code assets/minecraft/shaders/}.
     */
    public Map<String, String> files(Group group, List<Effect> effects) {
        List<Effect> sorted = effects.stream().sorted(Comparator.comparingInt(Effect::index)).collect(Collectors.toList());
        String pads = padFunctions(sorted);
        String body = fragmentEffects(sorted);
        Map<String, String> out = new LinkedHashMap<>();
        for (String file : group.files()) {
            String text = templates.get(group.minFormat() + "/" + file);
            // Exact strings, never a regex: the replacement is GLSL full of
            // characters a regex replacement reads as references.
            text = text.replace(index.markers.get("pads"), pads).replace(index.markers.get("effects"), body);
            out.put(file, text);
        }
        return out;
    }

    /** An id is its index plus one, so that zero can mean "not an effect". */
    private static int id(int index) {
        return index + 1;
    }

    /** {@code bool rtx_known(int id)} and {@code vec4 rtx_pad(int id)}, which both stages need. */
    String padFunctions(List<Effect> effects) {
        String known = effects.isEmpty() ? "false"
                : effects.stream().map(e -> "id == " + id(e.index())).collect(Collectors.joining(" || "));
        List<String> pads = new ArrayList<>();
        for (Effect e : effects) {
            int[] pad = e.pad();
            pads.add("    if (id == " + id(e.index()) + ") return vec4(" + TextEffectGlsl.f1(pad[0]) + ", "
                    + TextEffectGlsl.f1(pad[1]) + ", " + TextEffectGlsl.f1(pad[2]) + ", " + TextEffectGlsl.f1(pad[3]) + ");");
        }
        return "bool rtx_known(int id) {\n    return " + known + ";\n}\n\nvec4 rtx_pad(int id) {\n"
                + String.join("\n", pads) + "\n    return vec4(0.0);\n}\n";
    }

    /** Every effect's module, the pad functions and the dispatch, with the library's names prefixed. */
    String fragmentEffects(List<Effect> effects) {
        List<String> modules = new ArrayList<>();
        List<String> dispatch = new ArrayList<>();
        for (Effect e : effects) {
            modules.add("// ---- " + e.name().replaceAll("[\\r\\n]", " ") + " (text effect " + id(e.index()) + ") ----\n"
                    + TextEffectGlsl.namespaceEffect(e.glsl(), "rtx" + e.index() + "_", index.libraryNames));
            dispatch.add("    if (id == " + id(e.index()) + ") return rtx" + e.index() + "_effect(f);");
        }
        return TextEffectGlsl.prefixLibrary(String.join("\n", modules) + "\n\n" + padFunctions(effects)
                + "\nvec4 rtx_dispatch(int id, Fx f) {\n" + String.join("\n", dispatch)
                + "\n    return vec4(0.0);\n}\n", index.libraryNames);
    }

    private static String resource(String path) {
        try (InputStream in = TextEffectShaders.class.getResourceAsStream(ROOT + path)) {
            if (in == null) {
                throw new IllegalStateException("The jar is missing text-effects/" + path);
            }
            // Read as LF whatever a checkout did to the line endings: the
            // shader Studio builds, and the hashes the test holds this to, are LF.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
