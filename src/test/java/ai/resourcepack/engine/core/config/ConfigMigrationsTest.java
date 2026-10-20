package ai.resourcepack.engine.core.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationsTest {

    @TempDir
    Path dir;

    private static String resource(String name) throws IOException {
        try (InputStream in = ConfigMigrationsTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void theReleasedConfigGetsUploadAndKeepsEverythingElse() throws Exception {
        String old = resource("/config-migrations/0.2.12.yml");
        ConfigMigrations.Result result = ConfigMigrations.migrate(old);
        YamlConfiguration before = yaml(old);
        YamlConfiguration after = yaml(result.text());

        assertEquals(0, result.from());
        assertEquals(ConfigMigrations.latest(), result.to());
        assertEquals("upload", after.getString("host.mode"));
        assertEquals(ConfigMigrations.latest(), after.getInt("config-version"));
        assertFalse(after.contains("host.enabled"), "the old switch goes, mode says it now");
        assertFalse(result.text().contains("only want the plugin to build them"), "and its comment with it");
        // Every other setting, including the other sections' own `enabled`, is untouched.
        for (String key : before.getKeys(true)) {
            if (key.equals("host.enabled") || before.isConfigurationSection(key)) {
                continue;
            }
            assertEquals(before.get(key), after.get(key), key);
        }
        // And so is every comment the owner might have written.
        assertTrue(result.text().contains("# How a CLIENT reaches this server."));
    }

    @Test
    void anAddressSomebodyTypedInStaysOnItsOwnPort() throws Exception {
        String customer = "host:\n  port: 8165\n  public-address: \"http://45.92.216.104:8165\"\n  enabled: true\n";
        YamlConfiguration after = yaml(ConfigMigrations.migrate(customer).text());
        assertEquals("own-port", after.getString("host.mode"));
        assertEquals(8165, after.getInt("host.port"));
        assertEquals("http://45.92.216.104:8165", after.getString("host.public-address"));
    }

    @Test
    void hostingOffBecomesNone() throws Exception {
        String off = "host:\n  port: 8181\n  enabled: false\nchat:\n  enabled: true\n";
        YamlConfiguration after = yaml(ConfigMigrations.migrate(off).text());
        assertEquals("none", after.getString("host.mode"));
        assertFalse(after.contains("host.enabled"));
        assertTrue(after.getBoolean("chat.enabled"), "another section's enabled is not the host's");
    }

    @Test
    void aModeAlreadyThereIsLeftAlone() throws Exception {
        String text = "host:\n  mode: same-port\n  port: 8181\n";
        YamlConfiguration after = yaml(ConfigMigrations.migrate(text).text());
        assertEquals("same-port", after.getString("host.mode"));
        assertEquals(ConfigMigrations.latest(), after.getInt("config-version"));
    }

    @Test
    void itFollowsTheFilesOwnIndentation() throws Exception {
        String text = "host:\n    port: 8181\n    public-address: \"http://play.example.com:8181\"\nother: 1\n";
        String migrated = ConfigMigrations.migrate(text).text();
        assertTrue(migrated.contains("\n    mode: own-port\n"), migrated);
        assertEquals(1, yaml(migrated).getInt("other"));
    }

    @Test
    void anOlderFileGetsTheShaderMergeSwitchOn() throws Exception {
        YamlConfiguration after = yaml(ConfigMigrations.migrate(resource("/config-migrations/0.2.12.yml")).text());
        assertTrue(after.getBoolean("shaders.merge"));
        YamlConfiguration off = yaml(ConfigMigrations.migrate("config-version: 1\nshaders:\n  merge: false\n").text());
        assertFalse(off.getBoolean("shaders.merge"), "an owner's own choice stays");
    }

    @Test
    void anOlderFileGetsTheChatTextEffectsSwitchUnderChat() throws Exception {
        String migrated = ConfigMigrations.migrate(resource("/config-migrations/0.2.12.yml")).text();
        YamlConfiguration after = yaml(migrated);
        assertTrue(after.getBoolean("chat.text-effects"));
        // Beside the owner's own chat settings, not a second chat: section.
        assertEquals(1, migrated.split("(?m)^chat:", -1).length - 1, migrated);
        assertEquals("RPEngine", after.getString("chat.prefix"));
        YamlConfiguration none = yaml(ConfigMigrations.migrate("config-version: 2\nhost:\n  mode: none\n").text());
        assertTrue(none.getBoolean("chat.text-effects"));
        YamlConfiguration off = yaml(ConfigMigrations.migrate("config-version: 2\nchat:\n  text-effects: false\n").text());
        assertFalse(off.getBoolean("chat.text-effects"), "an owner's own choice stays");
    }

    @Test
    void noHostSectionGetsOne() throws Exception {
        YamlConfiguration after = yaml(ConfigMigrations.migrate("chat:\n  icons: true\n").text());
        assertEquals("upload", after.getString("host.mode"));
        assertTrue(after.getBoolean("chat.icons"));
    }

    @Test
    void windowsLineEndingsStayWindowsLineEndings() throws Exception {
        String text = "# mine\r\nhost:\r\n  port: 8181\r\n";
        String migrated = ConfigMigrations.migrate(text).text();
        assertFalse(migrated.replace("\r\n", "").contains("\n"), "a bare \\n crept in");
        assertEquals("upload", yaml(migrated).getString("host.mode"));
    }

    @Test
    void anUpToDateFileIsNotTouched() throws Exception {
        String current = resource("/config.yml");
        ConfigMigrations.Result result = ConfigMigrations.migrate(current);
        assertTrue(result.applied().isEmpty(), "the shipped config.yml must be at the latest version");
        assertEquals(current, result.text());
    }

    @Test
    void runningItTwiceDoesNothingTheSecondTime() throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, resource("/config-migrations/0.2.12.yml"));
        List<String> said = new ArrayList<>();
        assertEquals(ConfigMigrations.MIGRATIONS.size(), ConfigMigrations.run(file, said::add));
        assertTrue(Files.exists(dir.resolve("config.yml.v0.bak")));
        assertEquals(resource("/config-migrations/0.2.12.yml"), Files.readString(dir.resolve("config.yml.v0.bak")));
        String once = Files.readString(file);
        assertEquals(0, ConfigMigrations.run(file, said::add));
        assertEquals(once, Files.readString(file));
        assertEquals(ConfigMigrations.MIGRATIONS.size() + 1, said.size());
    }

    @Test
    void addsLocalFallbackDisabledByDefault() throws Exception {
        String old = "config-version: 3\nhost:\n  mode: upload\n";

        String migrated = ConfigMigrations.migrate(old).text();
        YamlConfiguration after = yaml(migrated);

        assertEquals(ConfigMigrations.latest(), after.getInt("config-version"));
        assertFalse(after.getBoolean("host.fallback-on-download-failure"));

        String enabled = "config-version: 3\nhost:\n"
                + "  fallback-on-download-failure: true\n";

        YamlConfiguration preserved =
                yaml(ConfigMigrations.migrate(enabled).text());

        assertTrue(preserved.getBoolean("host.fallback-on-download-failure"));
    }
}
