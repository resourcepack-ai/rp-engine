package ai.resourcepack.engine.core.pack.shader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough reading of a GLSL file to merge another one into it: what it
 * declares at the top level, which of those declarations depend on a
 * preprocessor condition (and which condition), what it imports and what that
 * brings, and where its {@code main} is.
 *
 * <p>Not a parser, and it does not try to be. A shader somebody else's plugin
 * generates can be minified onto one line (BetterHud's is: every statement
 * that is not a directive is joined to the one before it), so nothing here
 * depends on where a line breaks except a preprocessor directive, which GLSL
 * itself requires to be on a line of its own.
 *
 * <p><strong>Conditions are evaluated where the file settles them.</strong>
 * BetterHud picks its branch per version with {@code #define SHADER_VERSION 2}
 * and {@code #if SHADER_VERSION >= 3}, so a branch that a constant in the file
 * decides is read as taken or not taken, exactly as the compiler will. One
 * that depends on something the file does not define ({@code #ifdef IS_GUI},
 * which the game sets per variant) is left open: what is inside it is
 * "conditional", under the condition written down so it can be inverted.
 */
public final class GlslScan {

    /**
     * One name a file declares at the top level.
     *
     * @param condition when {@code conditional}, the preprocessor expression it
     *                  depends on, or null when that is not one this can state
     */
    public record Declaration(String name, Kind kind, String signature, boolean conditional, String condition) {
    }

    /** What a top-level declaration is. */
    public enum Kind { VARIABLE, FUNCTION, STRUCT, BLOCK, BLOCK_MEMBER }

    /** One {@code #moj_import} or {@code #include}, by the file's name without its namespace. */
    public record Import(String file, boolean conditional, String line) {
    }

    /** What an include file declares: the names it brings, or null when it is not one this knows. */
    @FunctionalInterface
    public interface Includes {
        Set<String> namesOf(String file);

        /** No knowledge of any include: an import brings nothing this can name. */
        Includes NONE = file -> null;
    }

    private static final Pattern IMPORT = Pattern.compile("^\\s*#\\s*(?:moj_import|include)\\s*[<\"]([^>\"]+)[>\"]");
    private static final Pattern DIRECTIVE = Pattern.compile("^#\\s*(\\w+)\\b\\s*(.*)$");
    private static final Pattern FUNCTION = Pattern.compile("(\\w+)\\s*\\(");
    private static final Pattern STRUCT = Pattern.compile("^\\s*struct\\s+(\\w+)\\s*$");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern LAYOUT = Pattern.compile("layout\\s*\\([^)]*\\)");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private static final Set<String> QUALIFIERS = Set.of(
            "in", "out", "inout", "uniform", "const", "flat", "smooth", "noperspective", "centroid",
            "invariant", "highp", "mediump", "lowp", "layout", "attribute", "varying", "precise", "buffer");

    /** Whether a branch of an {@code #if} is taken: known yes, known no, or up to the variant. */
    private enum Taken { YES, NO, OPEN }

    /** One open {@code #if}: whether its current branch is taken, and the condition it stands for. */
    private static final class Branch {
        Taken taken;
        /** The current branch's condition as an expression, or null when it cannot be stated. */
        String condition;
        /** Whether any earlier branch of this #if was certainly taken. */
        boolean settled;
        /** The conditions of the earlier, open branches, for an #else to negate. */
        final List<String> earlier = new ArrayList<>();

        Branch(Taken taken, String condition) {
            this.taken = taken;
            this.condition = condition;
            this.settled = taken == Taken.YES;
        }
    }

    private final String source;
    private final String code;
    private final Includes includes;
    private final List<Declaration> declarations = new ArrayList<>();
    private final List<Import> imports = new ArrayList<>();
    private final Map<String, String> defines = new HashMap<>();
    private final Deque<Branch> branches = new ArrayDeque<>();

    private GlslScan(String source, Includes includes) {
        this.source = source;
        this.code = stripComments(source);
        this.includes = includes == null ? Includes.NONE : includes;
        scan();
    }

    public static GlslScan of(String source) {
        return new GlslScan(source == null ? "" : source, Includes.NONE);
    }

    public static GlslScan of(String source, Includes includes) {
        return new GlslScan(source == null ? "" : source, includes);
    }

    public List<Declaration> declarations() {
        return declarations;
    }

    public List<Import> imports() {
        return imports;
    }

    /** Every declaration of {@code name}, in file order. */
    public List<Declaration> declarationsOf(String name) {
        List<Declaration> out = new ArrayList<>();
        for (Declaration d : declarations) {
            if (d.name().equals(name)) {
                out.add(d);
            }
        }
        return out;
    }

    /** The file's import of {@code file}, preferring an unconditional one, or null. */
    public Import importOf(String file) {
        Import found = null;
        for (Import i : imports) {
            if (i.file().equals(file) && (found == null || !i.conditional())) {
                found = i;
            }
        }
        return found;
    }

    /**
     * Every name the file can be relied on to have, at the top level: its own
     * declarations and those its imports bring. Conditional ones included;
     * whether that matters is the caller's question.
     */
    public Set<String> provided() {
        Set<String> out = new LinkedHashSet<>();
        for (Declaration d : declarations) {
            out.add(d.name());
        }
        for (Import i : imports) {
            Set<String> names = includes.namesOf(i.file());
            if (names != null) {
                out.addAll(names);
            }
        }
        return out;
    }

    /**
     * Whether an import brings {@code name}: null when none does, false when
     * one is imported unconditionally, true when only under a condition.
     */
    public Boolean importedConditionally(String name) {
        Boolean answer = null;
        for (Import i : imports) {
            Set<String> names = includes.namesOf(i.file());
            if (names != null && names.contains(name)) {
                answer = answer == null ? i.conditional() : answer && i.conditional();
            }
        }
        return answer;
    }

    /**
     * The names this file declares itself, unconditionally, that an import it
     * makes unconditionally ALSO declares. Not empty means the file cannot
     * compile as it stands, whoever merges what into it: a name declared twice
     * is an error on every driver.
     */
    public List<String> declaredTwice() {
        List<String> out = new ArrayList<>();
        for (Import i : imports) {
            if (i.conditional()) {
                continue;
            }
            Set<String> names = includes.namesOf(i.file());
            if (names == null) {
                continue;
            }
            for (Declaration d : declarations) {
                if (!d.conditional() && d.kind() != Kind.FUNCTION && names.contains(d.name())) {
                    out.add(d.name() + " (also in " + i.file() + ")");
                }
            }
        }
        return out;
    }

    /** How many {@code main} definitions the file has. A merge needs exactly one. */
    public int mainCount() {
        Matcher m = MAIN.matcher(code);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    /**
     * The offsets of the one {@code main}: where {@code void} starts, and just
     * after its opening brace, or null unless there is exactly one. Offsets are
     * into the ORIGINAL source, which the comment-stripped copy keeps character
     * for character.
     */
    public int[] main() {
        Matcher m = MAIN.matcher(code);
        if (!m.find()) {
            return null;
        }
        int[] at = {m.start(), m.end()};
        return m.find() ? null : at;
    }

    /** Every identifier the file uses anywhere, comments aside. */
    public Set<String> identifiers() {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IDENTIFIER.matcher(code);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    public String source() {
        return source;
    }

    /**
     * The source with every comment replaced by spaces, newlines kept, so an
     * offset into one is an offset into the other.
     */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < s.length() && !(s.charAt(i) == '*' && i + 1 < s.length() && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < s.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ---- the walk ---------------------------------------------------------

    /** Whether the code here is certainly not compiled. */
    private boolean skipped() {
        for (Branch b : branches) {
            if (b.taken == Taken.NO) {
                return true;
            }
        }
        return false;
    }

    /** Whether the code here depends on the variant. */
    private boolean conditional() {
        for (Branch b : branches) {
            if (b.taken == Taken.OPEN) {
                return true;
            }
        }
        return false;
    }

    /** The condition the code here depends on, "" for none, null when it cannot be stated. */
    private String condition() {
        List<String> parts = new ArrayList<>();
        for (Iterator<String> it = openConditions(); it.hasNext(); ) {
            String part = it.next();
            if (part == null) {
                return null;
            }
            parts.add("(" + part + ")");
        }
        return String.join(" && ", parts);
    }

    private Iterator<String> openConditions() {
        List<String> out = new ArrayList<>();
        for (Iterator<Branch> it = branches.descendingIterator(); it.hasNext(); ) {
            Branch b = it.next();
            if (b.taken == Taken.OPEN) {
                out.add(b.condition);
            }
        }
        return out.iterator();
    }

    private void scan() {
        int depth = 0;
        // What the open top-level brace belongs to, and whether a `;` (and
        // perhaps instance names) is still owed after one closed.
        Kind open = null;
        boolean owed = false;
        StringBuilder statement = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                directive(trimmed);
                continue;
            }
            if (skipped()) {
                continue;
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '{') {
                    if (depth == 0) {
                        open = opened(statement.toString().trim());
                    }
                    depth++;
                    statement.setLength(0);
                } else if (c == '}') {
                    depth = Math.max(0, depth - 1);
                    statement.setLength(0);
                    if (depth == 0) {
                        owed = open == Kind.STRUCT || open == Kind.BLOCK;
                        open = null;
                    }
                } else if (c == ';') {
                    String text = statement.toString().trim();
                    if (depth == 0 && owed) {
                        Matcher id = IDENTIFIER.matcher(text);
                        while (id.find()) {
                            add(id.group(), Kind.VARIABLE, "");
                        }
                        owed = false;
                    } else if (depth == 0) {
                        declared(text);
                    } else if (depth == 1 && open == Kind.BLOCK) {
                        // A uniform block's members are names of the whole file.
                        String name = lastIdentifier(text.replaceAll("\\[[^\\]]*\\]", ""));
                        if (name != null) {
                            add(name, Kind.BLOCK_MEMBER, "");
                        }
                    }
                    statement.setLength(0);
                } else {
                    statement.append(c);
                }
            }
            statement.append(' ');
        }
    }

    private void directive(String line) {
        Matcher d = DIRECTIVE.matcher(line);
        if (!d.find()) {
            return;
        }
        String name = d.group(1);
        String argument = d.group(2).trim();
        switch (name) {
            case "if" -> push(evaluate(argument), argument);
            case "ifdef" -> push(defines.containsKey(word(argument)) ? Taken.YES : Taken.OPEN, "defined(" + word(argument) + ")");
            case "ifndef" -> push(defines.containsKey(word(argument)) ? Taken.NO : Taken.OPEN, "!defined(" + word(argument) + ")");
            case "elif", "else" -> branchAgain(name.equals("else") ? null : argument);
            case "endif" -> branches.poll();
            case "define" -> {
                if (!skipped() && !conditional()) {
                    Matcher def = Pattern.compile("^(\\w+)(?:\\s+(.*))?$").matcher(argument);
                    if (def.find() && !argument.matches("^\\w+\\(.*")) {
                        defines.put(def.group(1), def.group(2) == null ? "" : def.group(2).trim());
                    }
                }
            }
            case "undef" -> defines.remove(word(argument));
            case "moj_import", "include" -> {
                if (!skipped()) {
                    Matcher im = IMPORT.matcher(line);
                    if (im.find()) {
                        String file = im.group(1);
                        file = file.substring(file.indexOf(':') + 1);
                        imports.add(new Import(file.substring(file.lastIndexOf('/') + 1), conditional(), line));
                    }
                }
            }
            default -> {
                // #version, #extension, #line and friends say nothing a merge needs.
            }
        }
    }

    private void push(Taken taken, String condition) {
        // Inside a branch that is not compiled, nothing below it is either.
        branches.push(new Branch(skipped() ? Taken.NO : taken, taken == Taken.OPEN ? condition : null));
    }

    /** An #elif (with its expression) or an #else (null). */
    private void branchAgain(String elif) {
        Branch b = branches.peek();
        if (b == null) {
            return;
        }
        if (b.taken == Taken.OPEN && b.condition != null) {
            b.earlier.add(b.condition);
        } else if (b.taken == Taken.OPEN) {
            b.earlier.add(null);
        }
        boolean outer = false;
        for (Branch o : branches) {
            if (o != b && o.taken == Taken.NO) {
                outer = true;
            }
        }
        if (outer || b.settled) {
            b.taken = Taken.NO;
            b.condition = null;
            return;
        }
        Taken now = elif == null ? Taken.YES : evaluate(elif);
        // Not taken if an earlier open branch was; so it is open as soon as any was.
        boolean earlierOpen = !b.earlier.isEmpty();
        if (now == Taken.NO) {
            b.taken = Taken.NO;
            b.condition = null;
            return;
        }
        if (now == Taken.YES && !earlierOpen) {
            b.taken = Taken.YES;
            b.settled = true;
            b.condition = null;
            return;
        }
        b.taken = Taken.OPEN;
        // An #else after open branches is "none of them": stateable when every
        // one of them was. An #elif adds its own test to that.
        StringBuilder c = new StringBuilder();
        for (String e : b.earlier) {
            if (e == null) {
                b.condition = null;
                return;
            }
            if (c.length() > 0) {
                c.append(" && ");
            }
            c.append("!(").append(e).append(")");
        }
        if (elif != null && now == Taken.OPEN) {
            if (c.length() > 0) {
                c.append(" && ");
            }
            c.append("(").append(elif).append(")");
        }
        b.condition = c.toString();
    }

    private static String word(String argument) {
        Matcher m = IDENTIFIER.matcher(argument);
        return m.find() ? m.group() : argument;
    }

    // ---- a small #if evaluator --------------------------------------------

    /**
     * An {@code #if} expression: taken, not taken, or open when it reads a name
     * the file does not define (which the game may define per variant).
     */
    private Taken evaluate(String expression) {
        try {
            Evaluator e = new Evaluator(expression);
            Long value = e.or();
            if (!e.done()) {
                return Taken.OPEN;
            }
            return value == null ? Taken.OPEN : value != 0 ? Taken.YES : Taken.NO;
        } catch (RuntimeException e) {
            return Taken.OPEN;
        }
    }

    /** Recursive descent over integers, defined(), names, !, comparisons, &&, || and brackets. Null is "cannot know". */
    private final class Evaluator {
        private final String s;
        private int at;

        Evaluator(String s) {
            this.s = s;
        }

        boolean done() {
            skip();
            return at >= s.length();
        }

        private void skip() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) {
                at++;
            }
        }

        private boolean eat(String token) {
            skip();
            if (s.startsWith(token, at)) {
                at += token.length();
                return true;
            }
            return false;
        }

        Long or() {
            Long left = and();
            while (eat("||")) {
                Long right = and();
                if (Long.valueOf(1).equals(truth(left)) || Long.valueOf(1).equals(truth(right))) {
                    left = 1L;
                } else {
                    left = left == null || right == null ? null : 0L;
                }
            }
            return left;
        }

        Long and() {
            Long left = comparison();
            while (eat("&&")) {
                Long right = comparison();
                if (Long.valueOf(0).equals(truth(left)) || Long.valueOf(0).equals(truth(right))) {
                    left = 0L;
                } else {
                    left = left == null || right == null ? null : 1L;
                }
            }
            return left;
        }

        private Long truth(Long v) {
            return v == null ? null : v != 0 ? 1L : 0L;
        }

        Long comparison() {
            Long left = unary();
            for (String op : new String[] {"==", "!=", ">=", "<=", ">", "<"}) {
                if (eat(op)) {
                    Long right = unary();
                    if (left == null || right == null) {
                        return null;
                    }
                    boolean r = switch (op) {
                        case "==" -> left.longValue() == right.longValue();
                        case "!=" -> left.longValue() != right.longValue();
                        case ">=" -> left >= right;
                        case "<=" -> left <= right;
                        case ">" -> left > right;
                        default -> left < right;
                    };
                    return r ? 1L : 0L;
                }
            }
            return left;
        }

        Long unary() {
            if (eat("!")) {
                Long v = unary();
                return v == null ? null : v == 0 ? 1L : 0L;
            }
            if (eat("(")) {
                Long v = or();
                eat(")");
                return v;
            }
            skip();
            Matcher num = Pattern.compile("\\d+").matcher(s).region(at, s.length());
            if (num.lookingAt()) {
                at = num.end();
                return Long.parseLong(num.group());
            }
            Matcher id = IDENTIFIER.matcher(s).region(at, s.length());
            if (id.lookingAt()) {
                at = id.end();
                String name = id.group();
                if (name.equals("defined")) {
                    boolean bracket = eat("(");
                    skip();
                    Matcher target = IDENTIFIER.matcher(s).region(at, s.length());
                    if (!target.lookingAt()) {
                        throw new IllegalArgumentException("defined of nothing");
                    }
                    at = target.end();
                    if (bracket) {
                        eat(")");
                    }
                    // Defined here: yes. Not defined here: the game may.
                    return defines.containsKey(target.group()) ? 1L : null;
                }
                String value = defines.get(name);
                if (value != null && value.matches("\\d+")) {
                    return Long.parseLong(value);
                }
                return null;
            }
            throw new IllegalArgumentException("cannot read " + s.substring(at));
        }
    }

    // ---- declarations -----------------------------------------------------

    private void add(String name, Kind kind, String signature) {
        boolean conditional = conditional();
        declarations.add(new Declaration(name, kind, signature, conditional, conditional ? condition() : null));
    }

    /** A top-level `{`: a function's body, a struct, or an interface block. */
    private Kind opened(String head) {
        Matcher struct = STRUCT.matcher(head);
        if (struct.find()) {
            add(struct.group(1), Kind.STRUCT, "struct");
            return Kind.STRUCT;
        }
        String unlaid = LAYOUT.matcher(head).replaceAll(" ").trim();
        if (unlaid.contains("(")) {
            String name = lastIdentifier(unlaid.substring(0, unlaid.indexOf('(')));
            if (name != null) {
                add(name, Kind.FUNCTION, "function");
            }
            return Kind.FUNCTION;
        }
        // `uniform DynamicTransforms`, `layout(std140) uniform Fog`.
        String name = lastIdentifier(unlaid);
        if (name != null) {
            add(name, Kind.BLOCK, "block");
        }
        return Kind.BLOCK;
    }

    /** A top-level `...;`: a variable (or several) or a function prototype. */
    private void declared(String text) {
        // `layout(location = 0)` is a qualifier, not a call.
        String unlaid = LAYOUT.matcher(text).replaceAll(" ").trim();
        if (unlaid.isEmpty() || unlaid.startsWith("precision")) {
            return;
        }
        Matcher fn = FUNCTION.matcher(unlaid);
        int eq = unlaid.indexOf('=');
        if (fn.find() && (eq < 0 || fn.start() < eq) && !unlaid.substring(0, fn.start()).contains(",")) {
            add(fn.group(1), Kind.FUNCTION, "function");
            return;
        }
        // Declarators are split on the commas outside any brackets, so
        // `float A = f(1, 2), B;` is two names, then each loses its initialiser.
        List<String> parts = splitTopLevel(unlaid.replaceAll("\\[[^\\]]*\\]", ""));
        String first = beforeInitialiser(parts.get(0));
        String name = lastIdentifier(first);
        if (name == null) {
            return;
        }
        String signature = normalise(first.substring(0, first.lastIndexOf(name)));
        add(name, Kind.VARIABLE, signature);
        for (int i = 1; i < parts.size(); i++) {
            String more = lastIdentifier(beforeInitialiser(parts.get(i)));
            if (more != null) {
                add(more, Kind.VARIABLE, signature);
            }
        }
    }

    private static String beforeInitialiser(String declarator) {
        int eq = declarator.indexOf('=');
        return (eq >= 0 ? declarator.substring(0, eq) : declarator).trim();
    }

    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(text.substring(from, i));
                from = i + 1;
            }
        }
        out.add(text.substring(from));
        return out;
    }

    /** The qualifiers and type of a declaration, layout and whitespace normalised, for comparing two. */
    static String normalise(String typeAndQualifiers) {
        return LAYOUT.matcher(typeAndQualifiers).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private static String lastIdentifier(String text) {
        Matcher m = IDENTIFIER.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return last == null || QUALIFIERS.contains(last) ? null : last;
    }
}
