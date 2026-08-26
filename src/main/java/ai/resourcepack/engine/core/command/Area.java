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

    /** The subcommands this area answers to. Also what the router completes. */
    List<String> subcommands();

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
