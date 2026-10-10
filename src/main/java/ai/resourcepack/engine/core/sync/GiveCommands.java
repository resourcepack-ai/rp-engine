package ai.resourcepack.engine.core.sync;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which relayed give-commands the console will run.
 *
 * <p>A {@code GIVE} frame from Studio is a command string run as the console,
 * and the console can run anything: {@code op}, {@code stop}, whatever another
 * plugin registers. Studio builds every one of these commands from its own
 * templates and never from text a user typed, so the set it can send is small
 * and fixed. This is that set, and anything outside it is refused rather than
 * run — so that a relay, a leaked secret or a bug upstream can at worst hand
 * the addressed player an item or show them something, never administer the
 * server.
 *
 * <p>What Studio sends, after it has put the player's name where it wrote
 * {@code @p}:
 * <ul>
 *   <li>{@code give <player> <item>} — models, items, blocks, armour pieces;</li>
 *   <li>{@code item replace entity <player> <slot> with <item>} — a vanilla armour set, one per piece;</li>
 *   <li>{@code tellraw <player> <json>} — an icon, put in chat;</li>
 *   <li>{@code rp dialog|screen|hud|shader|sound <id> <player>} and {@code rp armor wear <id> <player>};</li>
 *   <li>{@code execute as <player> at @s rotated ~ 0 run summon text_display|item_display …}
 *       — a shader object placed in front of the player.</li>
 * </ul>
 *
 * <p>The target must be the addressed player (or {@code @p}, which the relay's
 * {@code execute at} resolves to them). A command naming somebody else would
 * not have reached the addressee anyway, so refusing it costs nothing that
 * worked.
 */
public final class GiveCommands {

    private GiveCommands() {
    }

    /** The engine's own subcommands Studio opens things with. */
    private static final Set<String> RP_SUBCOMMANDS = Set.of("dialog", "screen", "hud", "shader", "sound");

    /** The engine's command and its aliases, as plugin.yml declares them. */
    private static final Set<String> RP_ROOTS = Set.of("rp", "rpe", "rpengine");

    /** The only entities a relayed summon may make: a shader object's marker. */
    private static final Set<String> SUMMONABLE = Set.of(
            "text_display", "item_display", "minecraft:text_display", "minecraft:item_display");

    /** The exact prefix of a "place it in front of them" summon, with the target in the middle. */
    private static final Pattern EXECUTE_SUMMON = Pattern.compile(
            "^execute as (\\S+) at @s rotated ~ 0 run (summon .*)$", Pattern.DOTALL);

    /** A content id the way Studio writes one: {@code studio:<slug>}, a page suffix allowed. */
    private static final Pattern CONTENT_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$", Pattern.CASE_INSENSITIVE);

    /**
     * Why a relayed command may not run, or empty when it may.
     *
     * @param command the command as relayed, with or without its leading slash
     * @param player  the addressed player's name
     */
    public static Optional<String> refusal(String command, String player) {
        if (command == null || command.isBlank()) return Optional.of("empty");
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c < 0x20 || c == 0x7f) return Optional.of("control character");
        }
        String body = command.startsWith("/") ? command.substring(1) : command;
        String[] words = body.split(" ");
        String root = words[0].toLowerCase(Locale.ROOT);

        switch (root) {
            case "give":
            case "tellraw":
                if (words.length < 3) return Optional.of("too short");
                return target(words[1], player);
            case "item":
                // item replace entity <target> <slot> with <item>
                if (words.length < 7
                        || !words[1].equalsIgnoreCase("replace")
                        || !words[2].equalsIgnoreCase("entity")
                        || !words[5].equalsIgnoreCase("with")) {
                    return Optional.of("not an item replace on a player");
                }
                return target(words[3], player);
            case "execute": {
                var matcher = EXECUTE_SUMMON.matcher(body);
                if (!matcher.matches()) return Optional.of("execute is only allowed to place a shader object");
                Optional<String> who = target(matcher.group(1), player);
                if (who.isPresent()) return who;
                return summon(matcher.group(2));
            }
            case "summon":
                return summon(body);
            default:
                if (RP_ROOTS.contains(root)) return engine(words, player);
                return Optional.of("command '" + words[0] + "' is not one Studio sends");
        }
    }

    private static Optional<String> engine(String[] words, String player) {
        if (words.length < 2) return Optional.of("no subcommand");
        String sub = words[1].toLowerCase(Locale.ROOT);
        if (RP_SUBCOMMANDS.contains(sub)) {
            if (words.length != 4) return Optional.of("wrong shape for rp " + sub);
            if (!CONTENT_ID.matcher(words[2]).matches()) return Optional.of("bad content id");
            return target(words[3], player);
        }
        if (sub.equals("armor")) {
            if (words.length != 5 || !words[2].equalsIgnoreCase("wear")) return Optional.of("only rp armor wear is allowed");
            if (!CONTENT_ID.matcher(words[3]).matches()) return Optional.of("bad content id");
            return target(words[4], player);
        }
        return Optional.of("rp " + words[1] + " is not one Studio sends");
    }

    private static Optional<String> summon(String command) {
        String[] words = command.split(" ", 6);
        if (words.length < 5) return Optional.of("summon too short");
        if (!SUMMONABLE.contains(words[1].toLowerCase(Locale.ROOT))) {
            return Optional.of("summon of '" + words[1] + "' is not allowed");
        }
        // A display's NBT never needs riders; a rider is how a summon would
        // smuggle in an entity this list would not have allowed.
        if (command.toLowerCase(Locale.ROOT).contains("passengers")) return Optional.of("summon with passengers");
        return Optional.empty();
    }

    private static Optional<String> target(String word, String player) {
        if (word.equals("@p")) return Optional.empty();
        if (player != null && word.equalsIgnoreCase(player)) return Optional.empty();
        return Optional.of("target '" + word + "' is not the addressed player");
    }
}
