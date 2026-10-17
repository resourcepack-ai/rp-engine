package ai.resourcepack.engine.core.pack.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes Studio's edits to somebody else's text shader, so both draw.
 *
 * <p>A core shader is one file per name and a client keeps only the top pack's
 * copy, so a HUD plugin that draws through the text shader (BetterHud) and a
 * Studio shader object cannot both work. They do not actually want different
 * things, though: each recognises its own text and leaves the rest alone. And
 * Studio never writes a text shader from nothing. It takes vanilla's, keeps it
 * whole, and adds to it in four places, which it marks in the file:
 *
 * <ul>
 *   <li>declarations, before {@code main};</li>
 *   <li>a prologue, at the top of {@code main} (vertex stage only);</li>
 *   <li>{@code main} renamed, so a new one can call it;</li>
 *   <li>an epilogue, the new {@code main} and what it needs, at the end.</li>
 * </ul>
 *
 * <p>Vanilla's part of Studio's file is therefore replaceable by anything that
 * is shaped like vanilla's, which another plugin's text shader is, because it
 * was made the same way. This class lifts the four marked pieces out of
 * Studio's file and makes the same four edits to the other one. Studio also
 * lists vanilla's own declarations (commented out), and any that Studio's code
 * uses and the other file lacks are added back.
 *
 * <p>It refuses, with the reason, rather than guess. Two files that declare one
 * name differently, a file with no single {@code main}, or a file that already
 * cannot compile on its own would all come out as a shader the client cannot
 * compile, and a core shader that fails takes its pack with it. Refusing
 * leaves the player exactly where they would have been without this: one of
 * the two drawing, and the warning saying which.
 */
public final class ShaderMerge {

    /** The prefix of every marker line Studio writes. Matched to Studio's `MERGE_MARKER` in its shader patcher. */
    public static final String MARKER = "// rpai-merge:";

    /** The line this puts in a file it produced, so a merged file is never merged into again. */
    public static final String MERGED = MARKER + "merged v1";

    private static final Pattern BLOCK = Pattern.compile(
            "(?ms)^[ \\t]*" + Pattern.quote(MARKER) + "begin ([a-z-]+) v(\\d+)[ \\t]*\\n(.*?)^[ \\t]*"
                    + Pattern.quote(MARKER) + "end \\1[ \\t]*$\\n?");
    private static final Pattern RENAMES = Pattern.compile(
            "(?m)^[ \\t]*" + Pattern.quote(MARKER) + "renames main (\\w+)[ \\t]*$");
    private static final Pattern PROVIDES = Pattern.compile(
            "^[ \\t]*" + Pattern.quote(MARKER) + "provides ([\\w ]+)$");
    private static final Pattern IMPORT = Pattern.compile("^\\s*#\\s*(?:moj_import|include)\\s*[<\"]([^>\"]+)[>\"]");

    /** A shader stage. */
    public enum Stage {
        VERTEX("vertex"), FRAGMENT("fragment");

        private final String prefix;

        Stage(String prefix) {
            this.prefix = prefix;
        }

        public String label() {
            return prefix;
        }
    }

    /** Studio's additions to one stage, as read off its markers. */
    public record Additions(String declarations, List<String> vanilla, String prologue, String epilogue,
                            String renamedMain) {

        boolean isEmpty() {
            return declarations.isBlank() && prologue.isBlank() && epilogue.isBlank();
        }
    }

    /** Why a merge was refused. */
    public static final class Refused extends Exception {
        public Refused(String reason) {
            super(reason);
        }
    }

    private ShaderMerge() {
    }

    /** Whether a file carries Studio's merge markers at all. */
    public static boolean marked(String source) {
        return source != null && source.contains(MARKER + "begin ");
    }

    /** Whether a file is one this class produced. */
    public static boolean merged(String source) {
        return source != null && source.contains(MERGED);
    }

    /**
     * Reads Studio's additions to one stage off its markers.
     *
     * @throws Refused when the file has none, or a version of them this does not read
     */
    public static Additions additions(String ours, Stage stage) throws Refused {
        if (!marked(ours)) {
            throw new Refused("Studio's shader was built before shaders could be merged. Push or export the pack again.");
        }
        Map<String, String> blocks = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(ours);
        while (m.find()) {
            if (!m.group(2).equals("1")) {
                throw new Refused("Studio's shader was built by a newer Studio than this RP Engine knows (merge markers v"
                        + m.group(2) + "). Update RP Engine.");
            }
            blocks.put(m.group(1), m.group(3));
        }
        String epilogue = blocks.getOrDefault(stage.prefix + "-epilogue", "");
        String renamed = null;
        Matcher r = RENAMES.matcher(epilogue);
        if (r.find()) {
            renamed = r.group(1);
        }
        if (!epilogue.isBlank() && renamed == null) {
            throw new Refused("Studio's " + stage.prefix + " shader adds a main without saying what the old one became.");
        }
        List<String> vanilla = new ArrayList<>();
        for (String line : blocks.getOrDefault(stage.prefix + "-vanilla", "").split("\n")) {
            String t = line.trim();
            if (t.startsWith("// ")) {
                vanilla.add(t.substring(3).trim());
            }
        }
        return new Additions(
                blocks.getOrDefault(stage.prefix + "-declarations", ""),
                vanilla,
                blocks.getOrDefault(stage.prefix + "-prologue", ""),
                epilogue,
                renamed);
    }

    /**
     * One stage: {@code base} (somebody else's file) with Studio's additions
     * from {@code ours} made to it.
     *
     * @param includes what the include files a client on this version reads declare
     * @throws Refused with the reason, when the two cannot share one file
     */
    public static String mergeStage(String base, String ours, Stage stage, GlslScan.Includes includes) throws Refused {
        Additions add = additions(ours, stage);
        if (add.isEmpty()) {
            // Studio changed nothing in this stage, so theirs is already right.
            return base;
        }
        if (merged(base) || marked(base)) {
            throw new Refused("the other pack's shader already carries Studio's code.");
        }
        GlslScan scan = GlslScan.of(base, includes);
        List<String> broken = scan.declaredTwice();
        if (!broken.isEmpty()) {
            throw new Refused("the other pack's own " + stage.prefix + " shader cannot compile on this version: it declares "
                    + String.join(", ", broken) + ". That is a bug in the plugin that made it; an update may fix it.");
        }
        if (scan.mainCount() != 1) {
            throw new Refused("the other pack's " + stage.prefix + " shader has " + scan.mainCount()
                    + " main functions, and a merge needs exactly one.");
        }

        Set<String> shared = new HashSet<>();
        String declarations = keptDeclarations(add.declarations(), scan, stage, shared);
        String vanilla = vanillaLines(add, declarations, scan, stage, includes, shared);

        // Every name Studio's code brings must be one the other file does not
        // already have. Theirs are kept as they are; ours are the guests. The
        // names both declare were settled above, line by line.
        Set<String> ourNames = new HashSet<>();
        for (GlslScan.Declaration d : GlslScan.of(vanilla + declarations, includes).declarations()) {
            if (!shared.contains(d.name())) {
                ourNames.add(d.name());
            }
        }
        for (GlslScan.Declaration d : GlslScan.of(add.epilogue()).declarations()) {
            if (!d.name().equals("main")) {
                ourNames.add(d.name());
            }
        }
        if (add.renamedMain() != null) {
            ourNames.add(add.renamedMain());
        }
        Set<String> theirs = scan.provided();
        for (String name : ourNames) {
            if (theirs.contains(name)) {
                throw new Refused("both shaders declare '" + name + "' in the " + stage.prefix + " stage.");
            }
        }

        int[] main = scan.main();
        StringBuilder out = new StringBuilder(base.length() + declarations.length() + add.prologue().length()
                + add.epilogue().length() + 200);
        String head = base.substring(0, main[0]);
        // Before main, on a line of its own: a directive in Studio's block
        // must start a line, and theirs may have minified the line before.
        out.append(head);
        if (!head.isEmpty() && !head.endsWith("\n")) {
            out.append('\n');
        }
        out.append(MERGED).append('\n');
        out.append(vanilla);
        out.append(declarations);
        String opening = base.substring(main[0], main[1]);
        if (add.renamedMain() != null) {
            opening = opening.replaceFirst("\\bmain\\b", add.renamedMain());
        }
        out.append(opening).append('\n');
        out.append(add.prologue());
        out.append(base, main[1], base.length());
        if (!add.epilogue().isBlank()) {
            if (out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(add.epilogue());
        }
        return out.toString();
    }

    /**
     * Vanilla's declarations that Studio's code uses and the other file does
     * not have, to be added to it. An import comes back whole; a declaration
     * the other file has only under {@code #if X} comes back under
     * {@code #if !(X)}, so every variant has it once.
     */
    private static String vanillaLines(Additions add, String declarations, GlslScan base, Stage stage,
                                       GlslScan.Includes includes, Set<String> shared) throws Refused {
        Set<String> uses = new HashSet<>();
        uses.addAll(GlslScan.of(declarations).identifiers());
        uses.addAll(GlslScan.of(add.prologue()).identifiers());
        uses.addAll(GlslScan.of(add.epilogue()).identifiers());
        StringBuilder out = new StringBuilder();
        Set<String> theirs = base.provided();
        for (String line : add.vanilla()) {
            Matcher im = IMPORT.matcher(line);
            Set<String> names = new HashSet<>();
            if (im.find()) {
                String file = im.group(1);
                file = file.substring(file.indexOf(':') + 1);
                file = file.substring(file.lastIndexOf('/') + 1);
                if (base.importOf(file) != null) {
                    continue;
                }
                Set<String> brings = includes.namesOf(file);
                if (brings != null) {
                    names.addAll(brings);
                }
            } else {
                for (GlslScan.Declaration d : GlslScan.of(line).declarations()) {
                    names.add(d.name());
                }
            }
            Set<String> needed = new HashSet<>(names);
            needed.retainAll(uses);
            needed.removeAll(theirs);
            if (needed.isEmpty()) {
                continue;
            }
            // Adding this line adds every name in it, not just the needed ones.
            for (String name : names) {
                if (theirs.contains(name)) {
                    throw new Refused("Studio's " + stage.prefix + " shader needs " + String.join(", ", needed)
                            + " from vanilla's '" + line + "', which also declares '" + name
                            + "', and the other pack's shader already has that.");
                }
            }
            out.append(line).append('\n');
            shared.addAll(names);
        }
        return out.toString();
    }

    /**
     * Studio's declaration lines, less the ones the other file already has.
     *
     * <p>A line both files declare the same way, unconditionally, is dropped:
     * GLSL refuses a second declaration. One theirs declares the same way but
     * only inside {@code #if X} is kept under {@code #if !(X)}, so that every
     * variant of the file has it exactly once (BetterHud's 26.2 shader leaves
     * {@code Sampler0} out of its GUI variant, where a full-screen object needs
     * it). One they declare differently is a refusal, because no single file
     * can be right for both then. An import is dropped when theirs imports the
     * same file, or already has every name the line was there for (Studio says
     * which, on a {@code provides} line).
     *
     * @param shared filled with every name both end up declaring through this,
     *               which the caller's collision check must not count twice
     */
    static String keptDeclarations(String declarations, GlslScan base, Stage stage, Set<String> shared) throws Refused {
        StringBuilder out = new StringBuilder();
        Set<String> provides = Set.of();
        for (String line : declarations.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher pm = PROVIDES.matcher(line);
            if (pm.find()) {
                provides = Set.of(pm.group(1).trim().split("\\s+"));
                continue;
            }
            if (trimmed.startsWith(MARKER)) {
                continue;
            }
            Matcher im = IMPORT.matcher(line);
            if (im.find()) {
                String file = im.group(1);
                file = file.substring(file.indexOf(':') + 1);
                file = file.substring(file.lastIndexOf('/') + 1);
                GlslScan.Import theirs = base.importOf(file);
                boolean keep;
                if (theirs != null) {
                    if (theirs.conditional()) {
                        throw new Refused("the other pack's " + stage.prefix + " shader imports " + file
                                + " only under a condition, and Studio's needs it always.");
                    }
                    keep = false;
                } else {
                    keep = !alreadyHas(base, provides, stage);
                }
                if (keep) {
                    out.append(line).append('\n');
                } else {
                    shared.addAll(provides);
                }
                provides = Set.of();
                continue;
            }
            if (trimmed.startsWith("#")) {
                out.append(line).append('\n');
                continue;
            }
            List<GlslScan.Declaration> ours = GlslScan.of(line).declarations();
            String unless = null;
            boolean keep = true;
            for (GlslScan.Declaration mine : ours) {
                List<GlslScan.Declaration> theirs = base.declarationsOf(mine.name());
                Boolean imported = base.importedConditionally(mine.name());
                if (theirs.isEmpty() && imported == null) {
                    continue;
                }
                if (ours.size() != 1) {
                    throw new Refused("both shaders declare '" + mine.name() + "' in the " + stage.prefix + " stage.");
                }
                shared.add(mine.name());
                if (imported != null) {
                    if (imported) {
                        throw new Refused("the other pack's " + stage.prefix + " shader has '" + mine.name()
                                + "' only from an import under a condition, and Studio's needs it always.");
                    }
                    keep = false;
                    continue;
                }
                for (GlslScan.Declaration d : theirs) {
                    if (!d.signature().equals(mine.signature())) {
                        throw new Refused("both shaders declare '" + mine.name() + "' in the " + stage.prefix
                                + " stage, differently.");
                    }
                }
                if (theirs.stream().anyMatch(d -> !d.conditional())) {
                    // Theirs has it everywhere, the same way.
                    keep = false;
                    continue;
                }
                String condition = theirs.get(0).condition();
                boolean one = condition != null && !condition.isEmpty()
                        && theirs.stream().allMatch(d -> condition.equals(d.condition()));
                if (!one) {
                    throw new Refused("the other pack's " + stage.prefix + " shader declares '" + mine.name()
                            + "' under conditions this cannot invert, and Studio's needs it always.");
                }
                unless = condition;
            }
            if (keep && unless != null) {
                out.append("#if !(").append(unless).append(")\n").append(line).append('\n').append("#endif\n");
            } else if (keep) {
                out.append(line).append('\n');
            }
            provides = Set.of();
        }
        return out.toString();
    }

    /** Whether the base already declares, unconditionally, every name an import was there for. */
    private static boolean alreadyHas(GlslScan base, Set<String> names, Stage stage) throws Refused {
        if (names.isEmpty()) {
            return false;
        }
        for (String name : names) {
            List<GlslScan.Declaration> theirs = base.declarationsOf(name);
            Boolean imported = base.importedConditionally(name);
            if (theirs.isEmpty() && imported == null) {
                return false;
            }
            boolean conditional = (imported != null && imported) || (!theirs.isEmpty()
                    && theirs.stream().allMatch(GlslScan.Declaration::conditional));
            if (conditional) {
                throw new Refused("the other pack's " + stage.prefix + " shader has '" + name
                        + "' only under a condition, and Studio's needs it always.");
            }
        }
        return true;
    }

    /**
     * A program definition (up to 1.21.4, the JSON beside the two stages): the
     * other pack's, with every sampler and uniform Studio's lists and theirs
     * does not added to it. A uniform both list is theirs; the game ignores a
     * listed uniform a stage does not use, so a list can only be too short.
     */
    public static String mergeProgram(String base, String ours) throws Refused {
        JsonObject theirs;
        JsonObject mine;
        try {
            theirs = JsonParser.parseString(base).getAsJsonObject();
            mine = JsonParser.parseString(ours).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new Refused("a shader program definition could not be read.");
        }
        for (String list : List.of("samplers", "uniforms")) {
            JsonArray into = theirs.has(list) && theirs.get(list).isJsonArray() ? theirs.getAsJsonArray(list) : new JsonArray();
            Set<String> names = new HashSet<>();
            for (JsonElement e : into) {
                names.add(nameOf(e));
            }
            if (mine.has(list) && mine.get(list).isJsonArray()) {
                for (JsonElement e : mine.getAsJsonArray(list)) {
                    if (names.add(nameOf(e))) {
                        into.add(e.deepCopy());
                    }
                }
            }
            theirs.add(list, into);
        }
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(theirs) + "\n";
    }

    private static String nameOf(JsonElement e) {
        return e.isJsonObject() && e.getAsJsonObject().has("name") ? e.getAsJsonObject().get("name").getAsString() : e.toString();
    }

    /**
     * Every {@code in} the fragment stage reads that the vertex stage does not
     * write. Empty is right; anything else is a program the game cannot link.
     */
    public static List<String> unlinked(String vertex, String fragment) {
        Set<String> outs = new HashSet<>();
        for (GlslScan.Declaration d : GlslScan.of(vertex).declarations()) {
            if (d.kind() == GlslScan.Kind.VARIABLE && hasWord(d.signature(), "out")) {
                outs.add(d.name());
            }
        }
        List<String> missing = new ArrayList<>();
        for (GlslScan.Declaration d : GlslScan.of(fragment).declarations()) {
            if (d.kind() == GlslScan.Kind.VARIABLE && hasWord(d.signature(), "in") && !outs.contains(d.name())) {
                missing.add(d.name());
            }
        }
        return missing;
    }

    private static boolean hasWord(String signature, String word) {
        return (" " + signature + " ").contains(" " + word + " ");
    }

    /** The text shader's file name on a pack format: renamed `text` in 26.2. */
    public static String textShaderName(int format) {
        return format >= 88 ? "text" : "rendertype_text";
    }

    /** Where a core shader file lives in a pack, without any overlay directory. */
    public static String corePath(String name, String extension) {
        return "assets/minecraft/shaders/core/" + name + "." + extension;
    }
}
