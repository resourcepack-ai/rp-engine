package ai.resourcepack.engine.core.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code plugin.yml} has to agree with itself.
 *
 * <p>Three lists in that file say the same thing in three ways — the usage
 * line a player is shown, the permission node each subcommand checks, and the
 * children of {@code rpengine.admin} — and nothing at runtime notices when one
 * of them is missing an entry. A subcommand with no node is unrunnable by
 * anybody; one missing from {@code admin} is unrunnable by the server owner
 * who granted the parent and reasonably expected everything.
 *
 * <p>Read as text rather than parsed as YAML: the assertion is about what the
 * file says, and there is no YAML library on the test classpath worth adding
 * for it.
 */
class PluginYmlTest {

    /**
     * Nodes that are not subcommands.
     *
     * <p>Two are qualifiers on {@code emote}. {@code chat.icons} is the first
     * that gates something which is not a command at all — typing
     * {@code :wave:} in chat — so it is also not something
     * {@code rpengine.admin} should grant: admin is "every command", and a
     * server that gives its ops every command has not thereby said only ops
     * may use an emoji.
     */
    private static final Set<String> NOT_SUBCOMMANDS = Set.of("emote.cast", "emote.force", "chat.icons");

    private static String pluginYml() throws IOException {
        return Files.readString(Path.of("src", "main", "resources", "plugin.yml"));
    }

    /** The `<a|b|c>` from the rpengine command's usage line. */
    private static List<String> usageSubcommands(String yml) {
        Matcher usage = Pattern.compile("usage: /<command> <([a-z|]+)>").matcher(yml);
        assertTrue(usage.find(), "no usage line for /rpengine");
        return List.of(usage.group(1).split("\\|"));
    }

    /** Every `rpengine.x:` node declared under `permissions:`. */
    private static Set<String> declaredNodes(String yml) {
        Set<String> nodes = new LinkedHashSet<>();
        Matcher node = Pattern.compile("(?m)^  rpengine\\.([a-z.]+):$").matcher(yml);
        while (node.find()) {
            nodes.add(node.group(1));
        }
        return nodes;
    }

    @Test
    void everySubcommandInTheUsageLineHasAPermissionNode() throws IOException {
        String yml = pluginYml();
        Set<String> nodes = declaredNodes(yml);
        for (String sub : usageSubcommands(yml)) {
            assertTrue(nodes.contains(sub),
                    "/rpengine " + sub + " has no rpengine." + sub + " permission");
        }
    }

    @Test
    void everyPermissionNodeIsASubcommandSomebodyCanRun() throws IOException {
        String yml = pluginYml();
        List<String> subs = usageSubcommands(yml);
        for (String node : declaredNodes(yml)) {
            if (node.equals("admin") || NOT_SUBCOMMANDS.contains(node)) {
                continue;
            }
            assertTrue(subs.contains(node) || node.equals("info"),
                    "rpengine." + node + " gates nothing: no such subcommand");
        }
    }

    @Test
    void adminGrantsEverythingExceptItself() throws IOException {
        String yml = pluginYml();
        Matcher end = Pattern.compile("(?m)^  rpengine\\.reload:$").matcher(yml);
        assertTrue(end.find(), "no rpengine.reload node");
        String children = yml.substring(yml.indexOf("children:"), end.start());
        for (String node : declaredNodes(yml)) {
            if (node.equals("admin") || NOT_SUBCOMMANDS.contains(node)) {
                continue;
            }
            assertTrue(children.contains("rpengine." + node + ": true"),
                    "rpengine.admin does not grant rpengine." + node);
        }
    }

    @Test
    void theCommandItselfCarriesNoPermission() throws IOException {
        // Deliberate: /rpengine with no arguments is the summary, and gating
        // the parent would hide every subcommand from a player who holds one.
        String yml = pluginYml();
        String block = yml.substring(yml.indexOf("  rpengine:"), yml.indexOf("  emote:"));
        assertEquals(-1, block.indexOf("\n    permission:"),
                "the rpengine command gates itself; each subcommand does that");
    }
}
