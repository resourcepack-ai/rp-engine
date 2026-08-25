package ai.resourcepack.engine.api;

import java.util.Objects;
import java.util.Optional;

/**
 * One thing that went wrong while loading content.
 *
 * <p>Collected rather than thrown. A server owner should fix ten problems in
 * one pass, not restart ten times, and a stack trace on the console tells them
 * about our code rather than about their file.
 *
 * <p>It carries where and what, never the finished sentence in somebody's chat
 * colours. Formatting is the host's job, the same rule the rest of the engine
 * follows.
 */
public final class Diagnostic {

    /** How much a diagnostic cost. */
    public enum Severity {

        /** Something was skipped. The content it describes does not exist. */
        ERROR,

        /** Everything loaded, but something is probably not what was meant. */
        WARNING
    }

    private final Severity severity;
    private final String origin;
    private final String where;
    private final String message;

    private Diagnostic(Severity severity, String origin, String where, String message) {
        this.severity = severity;
        this.origin = origin;
        this.where = where;
        this.message = message;
    }

    /**
     * @param origin the file or folder it came from, as a path relative to the
     *               content root. Never absolute: a server owner reading their
     *               console does not need our directory layout
     * @param where  the key inside that file, or empty for a whole-file problem
     */
    public static Diagnostic of(Severity severity, String origin, String where, String message) {
        return new Diagnostic(
                Objects.requireNonNull(severity, "severity"),
                origin == null ? "" : origin,
                where == null ? "" : where,
                message == null ? "" : message);
    }

    /** An error against a whole file or folder. */
    public static Diagnostic error(String origin, String message) {
        return of(Severity.ERROR, origin, "", message);
    }

    /** An error against one key within a file. */
    public static Diagnostic error(String origin, String where, String message) {
        return of(Severity.ERROR, origin, where, message);
    }

    /** A warning against a whole file or folder. */
    public static Diagnostic warning(String origin, String message) {
        return of(Severity.WARNING, origin, "", message);
    }

    /** A warning against one key within a file. */
    public static Diagnostic warning(String origin, String where, String message) {
        return of(Severity.WARNING, origin, where, message);
    }

    /** How much this cost. */
    public Severity severity() {
        return severity;
    }

    /** The file or folder, as a path relative to the content root. */
    public String origin() {
        return origin;
    }

    /** The key inside it, if the problem was that specific. */
    public Optional<String> where() {
        return where.isEmpty() ? Optional.empty() : Optional.of(where);
    }

    /** What went wrong, in one sentence and no colours. */
    public String message() {
        return message;
    }

    /** {@code path: key: message}, which is the console line minus the prefix. */
    @Override
    public String toString() {
        return origin + (where.isEmpty() ? "" : ": " + where) + ": " + message;
    }
}
