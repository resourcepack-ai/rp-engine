package ai.resourcepack.engine.core.edit;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replacing one top-level entry in a YAML file, and leaving the rest of it
 * exactly as it was.
 *
 * <p><strong>This is why the wire carries JSON and not YAML.</strong> A
 * {@code vehicles/cars.yml} is somebody's own file: it holds several vehicles,
 * their comments, their blank lines and whatever order they liked. Reading it
 * with SnakeYAML and dumping it back would return a file with the same
 * meaning and none of that — every comment gone, the keys reordered, the
 * strings requoted. For an author who typed it, that reads as the plugin
 * having eaten their work.
 *
 * <p>So the file is treated as text and only the edited entry's lines are
 * spliced out and replaced. The comments <em>inside</em> that one entry do go,
 * which is honest and is the smallest thing that could go: those lines
 * annotate values that have just been replaced by an editor.
 *
 * <h2>Where an entry ends</h2>
 *
 * <p>At the next line that begins a top-level key — but the blank lines and
 * whole-line comments immediately before it are given back to it. That is the
 * one rule here worth stating, because getting it wrong is destructive rather
 * than untidy: a comment written above the NEXT vehicle sits, textually,
 * inside the previous one's block, and swallowing it would delete somebody's
 * heading every time they saved.
 *
 * <p>No Bukkit and no YAML library, so all of it is testable as strings.
 */
final class YamlBlocks {

    /**
     * A top-level mapping key: no indentation, a name, a colon.
     *
     * <p>Quoted forms are matched too because a key with a dot or a leading
     * digit is often written that way, and an author who quoted their vehicle
     * id should still be able to edit it.
     */
    private static final Pattern TOP_LEVEL_KEY =
            Pattern.compile("^(?:\"([^\"]+)\"|'([^']+)'|([^\\s#:][^:]*?))\\s*:(?:\\s|$)");

    private YamlBlocks() {
    }

    /** Where an entry's lines are, as {@code [firstLine, endExclusive]}, or null. */
    static int[] find(String text, String key) {
        if (text == null || key == null) {
            return null;
        }
        List<String> lines = List.of(text.split("\n", -1));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String name = keyOf(lines.get(i));
            if (name == null) {
                continue;
            }
            if (start < 0) {
                if (name.equals(key)) {
                    start = i;
                }
                continue;
            }
            // The next top-level key ends the block. Wind back over what
            // belongs to it rather than to us: blank lines, and comments
            // written as its heading.
            int end = i;
            while (end > start + 1) {
                String previous = lines.get(end - 1).trim();
                if (previous.isEmpty() || previous.startsWith("#")) {
                    end--;
                } else {
                    break;
                }
            }
            return new int[] {start, end};
        }
        return start < 0 ? null : new int[] {start, lines.size()};
    }

    /**
     * {@code text} with the entry named by {@code key} replaced by
     * {@code block}, or null when there is no such entry.
     *
     * @param block the whole replacement including its own {@code key:} line,
     *              without a trailing newline
     */
    static String replace(String text, String key, String block) {
        int[] range = find(text, key);
        if (range == null) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < range[0]; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append(block);
        if (range[1] < lines.length) {
            out.append('\n');
            for (int i = range[1]; i < lines.length; i++) {
                out.append(lines[i]);
                if (i < lines.length - 1) {
                    out.append('\n');
                }
            }
        } else {
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * The key a line declares, or null if it declares none.
     *
     * <p>A line inside a block is indented and so declares nothing here, which
     * is the whole of how nesting is handled: this class never looks inside an
     * entry, it only finds where one starts.
     */
    private static String keyOf(String line) {
        String withoutCarriageReturn = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        if (withoutCarriageReturn.isEmpty()
                || Character.isWhitespace(withoutCarriageReturn.charAt(0))
                || withoutCarriageReturn.charAt(0) == '#'
                || withoutCarriageReturn.startsWith("---")
                || withoutCarriageReturn.startsWith("-")) {
            return null;
        }
        Matcher matcher = TOP_LEVEL_KEY.matcher(withoutCarriageReturn);
        if (!matcher.find()) {
            return null;
        }
        for (int group = 1; group <= 3; group++) {
            if (matcher.group(group) != null) {
                return matcher.group(group).trim();
            }
        }
        return null;
    }
}
