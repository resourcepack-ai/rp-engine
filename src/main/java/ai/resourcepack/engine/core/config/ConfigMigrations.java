package ai.resourcepack.engine.core.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings a server's config.yml up to date with this version of the plugin.
 *
 * <p>Bukkit writes config.yml once, on first start, and never again, so a
 * setting added in a later release is invisible to everybody who installed an
 * earlier one: it works, at its default, and nothing in their file says it
 * exists. Each change that needs more than a default is therefore a
 * {@link Migration} here, numbered, and the file records the last one it has
 * had in {@code config-version}.
 *
 * <p>Migrations edit the file as TEXT rather than loading and saving it as
 * YAML, because a YAML round trip drops the owner's comments, blank lines and
 * key order, and an owner who opens their config to find it rewritten stops
 * trusting the plugin with it. Each one is small and anchored on something
 * that cannot be mistaken (a top-level key), and adds rather than rewrites.
 * The file as it was is kept beside it, once per migration run.
 *
 * <p><strong>Adding one:</strong> append a {@link Migration} to
 * {@link #MIGRATIONS} with the next number, and set {@code config-version} in
 * the default config.yml to that number, so a new install starts with
 * nothing to do. Never renumber or remove one: a server two releases behind
 * needs every step in between.
 */
public final class ConfigMigrations {

    /** One step: what it does, in words for the console, and the edit. */
    public record Migration(int version, String summary, Edit edit) {
    }

    /** Text in, text out. {@code parsed} is the same text read as YAML, without defaults. */
    @FunctionalInterface
    public interface Edit {
        String apply(String text, YamlConfiguration parsed);
    }

    static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "added host.mode, which chooses how players download packs", ConfigMigrations::hostMode),
            new Migration(2, "added shaders.merge, which combines RP Engine's text shader with another plugin's,"
                    + " and shaders.notify-players",
                    ConfigMigrations::shaderMerge),
            new Migration(3, "added chat.text-effects, which lets players with a permission use <fx:name> in chat",
                    ConfigMigrations::chatTextEffects),
            new Migration(4, "added vfx.max-sprites, how many VFX sprites may be alive at once",
                    ConfigMigrations::vfxMaxSprites),
            new Migration(5, "added host.fallback-on-download-failure, which serves a player this server's own copy"
                    + " when the pack host fails them",
                    ConfigMigrations::hostFallback)
    );

    /** The version a fully migrated file says it is at. */
    public static int latest() {
        return MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    }

    private static final String VERSION_KEY = "config-version";
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^config-version:[^\\n]*\\n?");

    private ConfigMigrations() {
    }

    /**
     * Migrates the file at {@code file} in place, if it needs it.
     *
     * @param say told one line per migration applied
     * @return how many were applied
     */
    public static int run(Path file, Consumer<String> say) throws IOException {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        String before = Files.readString(file, StandardCharsets.UTF_8);
        Result result = migrate(before);
        if (result.applied().isEmpty()) {
            return 0;
        }
        Path backup = file.resolveSibling(file.getFileName() + ".v" + result.from() + ".bak");
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(file, result.text(), StandardCharsets.UTF_8);
        for (Migration migration : result.applied()) {
            say.accept("config.yml: " + migration.summary() + ".");
        }
        say.accept("config.yml updated from version " + result.from() + " to " + result.to()
                + ". The old file is " + backup.getFileName() + ".");
        return result.applied().size();
    }

    /** What {@link #migrate} did. */
    public record Result(String text, int from, int to, List<Migration> applied) {
    }

    /** The pure part of {@link #run}: the migrated text, and what it took. */
    public static Result migrate(String text) {
        int from = versionOf(text);
        String current = text.replace("\r\n", "\n");
        List<Migration> applied = new ArrayList<>();
        for (Migration migration : MIGRATIONS) {
            if (migration.version() <= from) {
                continue;
            }
            current = migration.edit().apply(current, parse(current));
            applied.add(migration);
        }
        if (applied.isEmpty()) {
            return new Result(text, from, from, applied);
        }
        int to = applied.get(applied.size() - 1).version();
        current = withVersion(current, to);
        if (text.contains("\r\n")) {
            current = current.replace("\n", "\r\n");
        }
        return new Result(current, from, to, applied);
    }

    static int versionOf(String text) {
        return parse(text).getInt(VERSION_KEY, 0);
    }

    private static YamlConfiguration parse(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            // Broken YAML is the owner's to fix, and Bukkit says so when it
            // loads it. Nothing here edits what it cannot read.
            throw new IllegalArgumentException("config.yml is not valid YAML", e);
        }
        return yaml;
    }

    /** Sets {@code config-version}, as the file's first key, after its leading comments. */
    private static String withVersion(String text, int version) {
        String line = VERSION_KEY + ": " + version + "\n";
        Matcher existing = VERSION_LINE.matcher(text);
        if (existing.find()) {
            return existing.replaceFirst(Matcher.quoteReplacement(line));
        }
        String[] lines = text.split("\n", -1);
        int at = 0;
        while (at < lines.length && (lines[at].startsWith("#") || lines[at].isBlank())) {
            at++;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < at; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append("# Which of RP Engine's config updates this file has had. The plugin\n")
                .append("# sets it; leave it alone.\n")
                .append(line).append('\n');
        for (int i = at; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    // ---- the migrations ----------------------------------------------------

    /** What host.public-address said before anybody changed it. */
    static final String OLD_DEFAULT_ADDRESS = "http://127.0.0.1:8181";

    private static final String OLD_ENABLED_COMMENT =
            "  # Set false if you host the built zips yourself (a CDN, a web server) and\n"
            + "  # only want the plugin to build them.\n";

    /**
     * 1: {@code host.mode}. Set to what the file already meant, which is what
     * the plugin assumes for a file without it: hosting off is {@code none}, a
     * typed-in address is somebody's own port, and the untouched default (which
     * only ever worked on the server's own machine) becomes {@code upload}.
     * The old {@code enabled} line is taken out with it, since mode now says
     * the same thing.
     */
    static String hostMode(String text, YamlConfiguration parsed) {
        if (parsed.contains("host.mode")) {
            return text;
        }
        String mode;
        if (!parsed.getBoolean("host.enabled", true)) {
            mode = "none";
        } else {
            String address = parsed.getString("host.public-address");
            mode = address != null && !address.equals(OLD_DEFAULT_ADDRESS) ? "own-port" : "upload";
        }
        String block = String.join("\n",
                "# How players download the packs this server builds:",
                "#   upload     uploaded to ResourcePack AI's free pack host, which",
                "#              players download from. Nothing to open or set up.",
                "#   same-port  served by this server on its Minecraft port. Not",
                "#              behind a proxy (Velocity, BungeeCord).",
                "#   own-port   served by this server on the port and address below,",
                "#              which you open and set yourself.",
                "#   none       built into the output folder, not served.",
                "# https://resourcepack.ai/docs/rp-engine/config#serving-the-pack",
                "mode: " + mode);

        Matcher host = Pattern.compile("(?m)^host:[ \\t]*(#[^\\n]*)?$").matcher(text);
        if (!host.find()) {
            // No host section at all: everything in it is at its default.
            String section = "\nhost:\n" + indent(block, "  ") + "\n";
            return text.endsWith("\n") ? text + section.substring(1) : text + section;
        }
        String indent = childIndent(text, host.end());
        String head = text.substring(0, host.end());
        String rest = text.startsWith("\n", host.end()) ? text.substring(host.end() + 1) : text.substring(host.end());
        // The host section is everything up to the next top-level key, and the
        // old switch is removed from there only: other sections have an
        // `enabled` of their own.
        Matcher next = Pattern.compile("(?m)^[^\\s#]").matcher(rest);
        int end = next.find() ? next.start() : rest.length();
        String section = rest.substring(0, end);
        section = section.replace(OLD_ENABLED_COMMENT.replace("  ", indent) + indent + "enabled: true\n", "");
        section = section.replaceFirst("(?m)^" + Pattern.quote(indent) + "enabled:[ \\t]*(true|false)[ \\t]*(\\n|$)", "");
        return head + "\n" + indent(block, indent) + "\n" + section + rest.substring(end);
    }

    /**
     * 2: {@code shaders.merge}, on, at the end of the file. Nothing a server had
     * before means anything for it, so a file that already has the section is
     * left alone and one that does not gets it as the default config has it.
     */
    static String shaderMerge(String text, YamlConfiguration parsed) {
        if (parsed.contains("shaders")) {
            return text;
        }
        String section = String.join("\n",
                "",
                "# Another plugin's pack can replace the same core shader as one of RP Engine's",
                "# (BetterHud and Studio's HUD shader objects both replace the text shader), and",
                "# a player's game keeps only one of the two: the one higher in their list.",
                "# With merge on, RP Engine combines its text shader with the other plugin's so",
                "# both draw, inside its own pack. The console says what was combined, and",
                "# anything that could not be, and why.",
                "# https://resourcepack.ai/docs/rp-engine/config#other-plugins-shaders",
                "shaders:",
                "  merge: true",
                "  # Also say it in chat to players with rpengine.shaders (ops), when it",
                "  # happens and when they join. False keeps it to the console.",
                "  notify-players: true",
                "");
        return (text.endsWith("\n") || text.isEmpty() ? text : text + "\n") + section;
    }

    /**
     * 3: {@code chat.text-effects}, on, first under {@code chat:}. A file with
     * no chat section gets one holding only this; every other chat setting is
     * then at its default, as it already was.
     */
    static String chatTextEffects(String text, YamlConfiguration parsed) {
        if (parsed.contains("chat.text-effects")) {
            return text;
        }
        String block = String.join("\n",
                "# Typing <fx:fire>hello</fx> in chat draws \"hello\" in the Text Effect called",
                "# \"fire\", for anybody with rpengine.chat.effects.all (every effect) or",
                "# rpengine.chat.effects.<name> (that one). A tag somebody may not use stays as",
                "# typed. https://resourcepack.ai/docs/rp-engine/text-effects",
                "text-effects: true");
        Matcher chat = Pattern.compile("(?m)^chat:[ \t]*(#[^\n]*)?$").matcher(text);
        if (!chat.find()) {
            String section = "\nchat:\n" + indent(block, "  ") + "\n";
            return text.endsWith("\n") || text.isEmpty() ? text + section.substring(1) : text + section;
        }
        String indent = childIndent(text, chat.end());
        String rest = text.startsWith("\n", chat.end()) ? text.substring(chat.end() + 1) : text.substring(chat.end());
        return text.substring(0, chat.end()) + "\n" + indent(block, indent) + "\n" + rest;
    }

    static String vfxMaxSprites(String text, YamlConfiguration parsed) {
        if (parsed.contains("vfx.max-sprites")) {
            return text;
        }
        String block = String.join("\n",
                "# Every sprite of a VFX is a display entity, and this is how many may be",
                "# alive at once across the whole server. When a new effect would go over it,",
                "# the oldest effects playing end to make room, so a cast always plays. One",
                "# big effect is a couple of hundred. 0 for no limit.",
                "# https://resourcepack.ai/docs/rp-engine/vfx",
                "max-sprites: 4000");
        Matcher vfx = Pattern.compile("(?m)^vfx:[ \t]*(#[^\n]*)?$").matcher(text);
        if (!vfx.find()) {
            String section = "\nvfx:\n" + indent(block, "  ") + "\n";
            return text.endsWith("\n") || text.isEmpty() ? text + section.substring(1) : text + section;
        }
        String indent = childIndent(text, vfx.end());
        String rest = text.startsWith("\n", vfx.end()) ? text.substring(vfx.end() + 1) : text.substring(vfx.end());
        return text.substring(0, vfx.end()) + "\n" + indent(block, indent) + "\n" + rest;
    }

    /**
     * 5: {@code host.fallback-on-download-failure}, off, first under
     * {@code host:}. The port and address comments above it in older files
     * still say "own-port only"; this one says it uses them too.
     */
    static String hostFallback(String text, YamlConfiguration parsed) {
        if (parsed.contains("host.fallback-on-download-failure")) {
            return text;
        }
        String block = String.join("\n",
                "# upload only. Also serve the packs on port and public-address (as",
                "# own-port does), for a player whose game could not download one from the",
                "# pack host, and for everybody while an upload is failing. That port has to",
                "# be open and that address reachable, as for own-port. Read at startup.",
                "fallback-on-download-failure: false");
        Matcher host = Pattern.compile("(?m)^host:[ \t]*(#[^\n]*)?$").matcher(text);
        if (!host.find()) {
            String section = "\nhost:\n" + indent(block, "  ") + "\n";
            return text.endsWith("\n") || text.isEmpty() ? text + section.substring(1) : text + section;
        }
        String indent = childIndent(text, host.end());
        String rest = text.startsWith("\n", host.end()) ? text.substring(host.end() + 1) : text.substring(host.end());
        return text.substring(0, host.end()) + "\n" + indent(block, indent) + "\n" + rest;
    }

    /** The indentation of the first key under the section whose line ends at {@code from}. */
    private static String childIndent(String text, int from) {
        Matcher child = Pattern.compile("(?m)^([ \\t]+)[^\\s#]").matcher(text);
        if (child.find(from)) {
            return child.group(1);
        }
        return "  ";
    }

    private static String indent(String block, String indent) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\n")) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(indent).append(line);
        }
        return out.toString();
    }
}
