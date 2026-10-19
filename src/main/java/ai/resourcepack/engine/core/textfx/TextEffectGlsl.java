package ai.resourcepack.engine.core.textfx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text a text effect's GLSL is turned into on its way into the pack: its
 * names made unique to it, the library's names prefixed, and the rules every
 * effect is held to. Free of Bukkit, and tested.
 *
 * <p>A PORT of Studio's text effect linker, and it has to agree with it byte
 * for byte, because the shader around this text is Studio's own (see
 * {@link TextEffectShaders}) and a pack built here must be the pack Studio
 * would have built from the same effects. {@code TextEffectShadersTest} holds
 * the two together on Studio's real output.
 */
public final class TextEffectGlsl {

    private TextEffectGlsl() {
    }

    // ---- comments and names ------------------------------------------------

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** Comments out, newlines kept, so a line number still points at the line that was written. */
    public static String stripComments(String glsl) {
        Matcher block = BLOCK_COMMENT.matcher(glsl);
        StringBuilder out = new StringBuilder();
        while (block.find()) {
            block.appendReplacement(out, Matcher.quoteReplacement(block.group().replaceAll("[^\\n]", " ")));
        }
        block.appendTail(out);
        return LINE_COMMENT.matcher(out.toString()).replaceAll("");
    }

    private static final Pattern TRAILING_NAME = Pattern.compile("(\\w+)\\s*$");
    private static final Pattern FUNCTION_HEAD = Pattern.compile("^(?:(?:const|highp|mediump|lowp)\\s+)*\\w+\\s+\\w+$");
    private static final Pattern STRUCT = Pattern.compile("^struct\\s+(\\w+)");
    private static final Pattern CONSTANT = Pattern.compile("^const\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?\\w+\\s+([\\s\\S]*)$");
    private static final Pattern LEADING_NAME = Pattern.compile("^\\s*(\\w+)");

    /** The names a source declares at the top level: its functions, structs and constants, in order. */
    public static List<String> topLevelNames(String glsl) {
        Set<String> names = new LinkedHashSet<>();
        int depth = 0;
        int statementStart = 0;
        String text = stripComments(glsl);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{' || ch == '(') {
                if (depth == 0 && ch == '{') {
                    collect(text.substring(statementStart, i), names);
                }
                if (depth == 0 && ch == '(') {
                    String before = text.substring(statementStart, i);
                    Matcher fn = TRAILING_NAME.matcher(before);
                    // A function definition or prototype: `type name(`.
                    if (fn.find() && FUNCTION_HEAD.matcher(before.strip()).matches()) {
                        names.add(fn.group(1));
                    }
                }
                depth++;
            } else if (ch == '}' || ch == ')') {
                depth--;
                if (depth == 0 && ch == '}') {
                    statementStart = i + 1;
                }
            } else if (ch == ';' && depth == 0) {
                collect(text.substring(statementStart, i), names);
                statementStart = i + 1;
            }
        }
        return new ArrayList<>(names);
    }

    private static void collect(String statement, Set<String> names) {
        String s = statement.strip();
        Matcher struct = STRUCT.matcher(s);
        if (struct.find()) {
            names.add(struct.group(1));
            return;
        }
        Matcher constant = CONSTANT.matcher(s);
        if (constant.find()) {
            for (String part : splitTopLevel(constant.group(1))) {
                Matcher name = LEADING_NAME.matcher(part);
                if (name.find()) {
                    names.add(name.group(1));
                }
            }
        }
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Every whole-word use of one of {@code names}, never a member ({@code .p}) or part of a longer name. */
    private static Pattern words(List<String> names) {
        return Pattern.compile("(?<![\\w.])(" + String.join("|", names) + ")\\b");
    }

    /** An effect's source, comments out, with its own top-level names made unique to it. */
    public static String namespaceEffect(String glsl, String prefix, List<String> libraryNames) {
        List<String> names = new ArrayList<>(topLevelNames(glsl));
        names.removeAll(libraryNames);
        String text = stripComments(glsl);
        if (names.isEmpty()) {
            return text;
        }
        return words(names).matcher(text).replaceAll(m -> Matcher.quoteReplacement(prefix + m.group(1)));
    }

    /** Renames every library name in {@code glsl} to {@code rtx_<name>}. */
    public static String prefixLibrary(String glsl, List<String> libraryNames) {
        return words(libraryNames).matcher(glsl).replaceAll(m -> Matcher.quoteReplacement("rtx_" + m.group(1)));
    }

    // ---- the rules ----------------------------------------------------------

    private static final Pattern LOOPS = Pattern.compile("\\bwhile\\b|\\bdo\\s*\\{");
    private static final Pattern DIRECTIVE = Pattern.compile("#\\s*\\w+");
    private static final Pattern GLOBALS = Pattern.compile(
            "\\buniform\\b|\\b(?:in|out|varying|attribute)\\s+\\w+\\s+\\w+\\s*;|\\bsampler\\w*\\b|\\btexture\\w*\\s*\\(|\\bSampler\\d\\b|\\bGameTime\\b");
    private static final Pattern BUILT_IN = Pattern.compile("\\bgl_\\w+");
    private static final Pattern MAIN = Pattern.compile("\\bmain\\s*\\(");
    private static final Pattern DISCARD = Pattern.compile("\\bdiscard\\b");
    private static final Pattern RESERVED = Pattern.compile("\\brtx\\w*");
    private static final Pattern FOR = Pattern.compile("\\bfor\\s*\\(([^;]*);([^;]*);([^)]*)\\)");
    private static final Pattern BOUND = Pattern.compile("(?:<=?|>=?)\\s*(-?\\d+)\\s*$");
    private static final Pattern ENTRY = Pattern.compile("\\bvec4\\s+effect\\s*\\(\\s*Fx\\s+\\w+\\s*\\)");

    /**
     * What is wrong with an effect's source, in words for its author; empty
     * when nothing is. The same rules Studio holds its own effects to, because
     * a text shader that does not compile does not degrade: the client drops
     * the whole pack. These are not a compiler, so they cannot promise it
     * compiles; they rule out what would hang it or reach past the effect.
     */
    public static List<String> problems(String glsl, int maxLoop, int maxSource) {
        String text = stripComments(glsl);
        List<String> problems = new ArrayList<>();
        if (LOOPS.matcher(text).find()) {
            problems.add("while/do loops aren't allowed: use a for with a constant bound.");
        }
        if (DIRECTIVE.matcher(text).find()) {
            problems.add("Preprocessor directives (#define, #if...) aren't allowed: use const values.");
        }
        if (GLOBALS.matcher(text).find()) {
            problems.add("No uniforms, samplers, textures or GameTime: read the letter with G(p) and the time from f.t.");
        }
        if (BUILT_IN.matcher(text).find()) {
            problems.add("No gl_ built-ins.");
        }
        if (MAIN.matcher(text).find()) {
            problems.add("Don't write main: write vec4 effect(Fx f).");
        }
        if (DISCARD.matcher(text).find()) {
            problems.add("Don't discard: return alpha 0.");
        }
        if (RESERVED.matcher(text).find()) {
            problems.add("Names starting rtx are the linker's.");
        }
        Matcher loop = FOR.matcher(text);
        while (loop.find()) {
            Matcher bound = BOUND.matcher(loop.group(2).strip());
            if (!bound.find()) {
                problems.add("Loop for (" + loop.group(1) + ";" + loop.group(2) + ";" + loop.group(3)
                        + ") needs a literal bound, like i < 8.");
                continue;
            }
            if (Math.abs(Long.parseLong(bound.group(1))) > maxLoop) {
                problems.add("A loop runs to " + bound.group(1) + "; the limit is " + maxLoop + ".");
            }
        }
        if (!ENTRY.matcher(text).find()) {
            problems.add("There's no vec4 effect(Fx f) function.");
        }
        if (text.length() > maxSource) {
            problems.add("The source is " + text.length() + " characters; the limit is " + maxSource + ".");
        }
        return problems;
    }

    /** A number as Studio writes it into the shader: one decimal place. */
    static String f1(int n) {
        return String.format(Locale.ROOT, "%.1f", (double) n);
    }
}
