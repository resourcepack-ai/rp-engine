package ai.resourcepack.engine;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * <b>That {@code plugin.yml} is valid YAML at all.</b>
 *
 * <p>This exists because it was not, and the cost is not proportionate to the
 * mistake: a plugin.yml that does not parse is not a broken permission line or
 * a missing command, it is {@code InvalidDescriptionException} at boot and a
 * server that does not start. Every other plugin on the box goes down with it.
 *
 * <p>The one that shipped was
 * {@code description: Use :name: shortcodes for icons in chat.} — a plain YAML
 * scalar ends at the first colon-space, so the value read as a nested mapping
 * and snakeyaml threw. Nothing in this repository looked at this file: it is a
 * resource, the build only string-substitutes the version into it, and the
 * plugin's own tests never load it. It reached a real server to be found.
 *
 * <p>So this parses the PROCESSED copy — {@code build/resources/main}, the one
 * that goes into the jar with {@code ${version}} already expanded — because
 * that is the file Paper will read and the only one worth an assertion.
 */
class PluginYmlTest {

    private static Map<String, Object> parsed() {
        try (InputStream in = PluginYmlTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "plugin.yml is not on the test classpath — has processResources run?");
            Object loaded = new Yaml().load(in);
            assertInstanceOf(Map.class, loaded, "plugin.yml should parse to a mapping");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) loaded;
            return map;
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read plugin.yml", e);
        }
    }

    @Test
    void pluginYmlIsValidYaml() {
        Map<String, Object> yml = parsed();
        // The three Paper refuses to load without.
        for (String key : new String[] {"name", "main", "version"}) {
            assertNotNull(yml.get(key), key + " is missing from plugin.yml");
        }
        // Load-bearing beyond this file: every model standing in somebody's
        // world is keyed to the plugin's name, so renaming it orphans every
        // one of them. See README.md.
        assertTrue("RPEngine".equals(yml.get("name")), "the plugin's name may not change");
        assertTrue(!String.valueOf(yml.get("version")).contains("${"),
            "processResources should have expanded the version, got " + yml.get("version"));
    }

    /**
     * <b>Every description is a STRING.</b>
     *
     * <p>The specific shape of the failure, pinned separately: a colon-space in
     * an unquoted description does not usually make the file unparseable — it
     * quietly turns that one value into a nested map, and only fails loudly
     * when the result cannot be a mapping either. Asserting the type catches it
     * in the cases where YAML is happy and Paper is not.
     */
    @Test
    void everyDescriptionIsAString() {
        Map<String, Object> yml = parsed();
        for (String section : new String[] {"commands", "permissions"}) {
            Object block = yml.get(section);
            if (block == null) continue;
            assertInstanceOf(Map.class, block, section + " should be a mapping");
            @SuppressWarnings("unchecked")
            Map<String, Object> entries = (Map<String, Object>) block;
            for (Map.Entry<String, Object> entry : entries.entrySet()) {
                assertInstanceOf(Map.class, entry.getValue(),
                    section + "." + entry.getKey() + " should be a mapping");
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) entry.getValue();
                Object description = body.get("description");
                if (description == null) continue;
                assertInstanceOf(String.class, description,
                    section + "." + entry.getKey() + ".description parsed as "
                        + description.getClass().getSimpleName()
                        + " rather than a string — an unquoted colon-space in it is read as"
                        + " a nested mapping. Quote the value.");
            }
        }
    }

    /** The line that actually broke a server, kept by name. */
    @Test
    void theIconShortcodePermissionSurvivesItsColons() {
        Map<String, Object> yml = parsed();
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) yml.get("permissions");
        assertNotNull(permissions, "plugin.yml has no permissions block");
        @SuppressWarnings("unchecked")
        Map<String, Object> icons = (Map<String, Object>) permissions.get("rpengine.chat.icons");
        assertNotNull(icons, "rpengine.chat.icons is missing");
        assertInstanceOf(String.class, icons.get("description"),
            "rpengine.chat.icons.description must stay quoted — it contains :name:");
        assertTrue(String.valueOf(icons.get("description")).contains(":name:"),
            "the description should still document the :name: shortcode form");
    }
}
