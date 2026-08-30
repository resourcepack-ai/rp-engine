package ai.resourcepack.engine.core.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * One group of related subcommands, and their completions.
 *
 * <p>The command surface is split by what a subcommand is <em>about</em>
 * rather than by arity or by permission: {@code items} and {@code give} belong
 * together because they are two ends of one question, and neither belongs
 * beside {@code hud}. Each area takes only the collaborators its own group
 * needs, which is the whole point — a class that can reach everything ends up
 * doing everything.
 *
 * <p>Package-private on purpose. {@link EngineCommand} takes the concrete
 * areas, so nothing outside this package has any reason to name the seam.
 */
interface Area {

    /** What to call this group in the help. Two or three words. */
    String title();

    /**
     * Every subcommand this area answers to, with its arguments and what it
     * does — one entry per line of help, in the order they should be read.
     *
     * <p>{@link #subcommands()} is derived from this rather than listed
     * separately, so a subcommand cannot exist without a line of help
     * explaining it. That is the same rule {@link Completions} states about
     * tab completion, for the same reason.
     */
    List<Help> help();

    /**
     * The subcommands this area answers to. Also what the router completes.
     *
     * <p>Derived, and deduplicated: several lines of help may be about one
     * subcommand ({@code sync}, {@code sync add}), and the router only ever
     * dispatches on the first word.
     */
    default List<String> subcommands() {
        return help().stream().map(Help::command).distinct().toList();
    }

    /**
     * Every command this area answers to, as its words, for the chat linker.
     *
     * <p>Defaults to the help, which is right wherever every command has a
     * line of its own. It is <strong>not</strong> right where a line covers
     * several verbs at once — {@code sync who|leave|stop} is one line and
     * three commands, and {@code sync accept} is a command with no line at
     * all. An area in that position overrides this, and until one did, a
     * message naming one of those verbs was truncated at the last word the
     * help happened to spell. See {@link ai.resourcepack.engine.core.Chat}.
     */
    default List<String> signatures() {
        return help().stream().map(Help::signature).distinct().toList();
    }

    /**
     * Runs one.
     *
     * @param sub  the subcommand, already lowercased and known to be ours
     * @param args the whole argument list, {@code args[0]} being {@code sub}
     */
    boolean run(CommandSender sender, String sub, String[] args);

    /**
     * Suggestions for the argument being typed.
     *
     * <p>Empty is a legitimate answer — a code off a web page cannot be
     * completed — but a missing one is not. See {@link Completions}.
     */
    default List<String> complete(CommandSender sender, String sub, String[] args) {
        return List.of();
    }
}
