package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.core.sync.StudioContent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mob's abilities as Studio pushes them, through the reader a push goes
 * through: {@code skill-lines/studio-mob.json} is written by Studio's
 * {@code verify:mob-skills} from its own compiler and presets, read here by
 * {@link StudioContent} and loaded by {@link SkillDefinitions#withPushed} with
 * the engine's real mechanics, targeters and conditions registered. A line
 * Studio sends that the engine would skip is a failure here, not a warning in
 * somebody's console.
 */
class StudioMobSkillsTest {

    /** The runtime's registry with every built-in installed; nothing in it touches a server. */
    private static SkillDefinitions.Names realNames() {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getName":
                            return "RPEngine";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "RPEngine";
                        default:
                            return method.getReturnType() == boolean.class ? false : null;
                    }
                });
        return new SkillRuntime(plugin, null).names();
    }

    private static String studioManifest() throws IOException {
        try (InputStream in = StudioMobSkillsTest.class.getResourceAsStream("/skill-lines/studio-mob.json")) {
            assertNotNull(in, "skill-lines/studio-mob.json is missing: run Studio's npm run verify:mob-skills -- --write");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyLineStudioSendsLoadsWithNoWarning() throws IOException {
        StudioContent content = StudioContent.read(studioManifest(), "studio-mob.json", null);
        assertNotNull(content);
        ContentId mob = ContentId.of("studio", "golem_king").orElseThrow();
        assertTrue(content.mobs().containsKey(mob), "the mob itself was read");
        StudioContent.PushedMobSkills pushed = content.mobSkills().get(mob);
        assertNotNull(pushed, "its skills were read");
        assertTrue(pushed.lines().size() >= 6);
        assertEquals(pushed.lines().size(), pushed.metaskills().size(), "one metaskill per ability");

        Map<ContentId, List<String>> lines = new LinkedHashMap<>();
        lines.put(mob, pushed.lines());
        SkillDefinitions.Result empty = new SkillDefinitions.Result(Map.of(), Map.of(), List.of());
        SkillDefinitions.Result loaded = SkillDefinitions.withPushed(empty, "studio", lines, Map.of(),
                pushed.metaskills(), "Studio push", realNames());

        List<Diagnostic> problems = loaded.diagnostics();
        assertTrue(problems.isEmpty(), () -> "the engine would skip or warn about: " + problems);
        assertEquals(pushed.lines().size(), loaded.mobs.get(mob).lines.size());
        for (SkillLine line : loaded.mobs.get(mob).lines) {
            String name = line.args.get("s");
            assertNotNull(name, line.text);
            // Every line calls a metaskill that loaded, by the id it is registered under.
            ContentId called = ContentId.parse(name.toLowerCase(java.util.Locale.ROOT)).orElseThrow();
            assertTrue(loaded.metaskills.containsKey(called), () -> line.text + " calls " + called + ", which did not load");
            assertTrue(!loaded.metaskills.get(called).lines.isEmpty());
        }
    }

    /**
     * Studio's mob bench writes {@code skills.json} beside every mob it
     * plans: run with {@code RPE_STUDIO_MOB_JSON} pointing at one to load what
     * the AI planned through this reader. Skipped otherwise.
     */
    @Test
    void aBenchMobLoadsWithNoWarning() throws IOException {
        String path = System.getenv("RPE_STUDIO_MOB_JSON");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isBlank(), "RPE_STUDIO_MOB_JSON not set");
        com.google.gson.JsonObject bench = com.google.gson.JsonParser.parseString(
                java.nio.file.Files.readString(java.nio.file.Path.of(path))).getAsJsonObject();
        StudioContent content = StudioContent.read(bench.get("manifest").toString(), path, null);
        assertNotNull(content);
        SkillDefinitions.Names names = realNames();
        Map<ContentId, List<String>> lines = new LinkedHashMap<>();
        Map<String, Map<String, Object>> metaskills = new LinkedHashMap<>();
        content.mobSkills().forEach((id, skills) -> {
            lines.put(id, skills.lines());
            metaskills.putAll(skills.metaskills());
        });
        assertTrue(!lines.isEmpty(), "the bench mob has no abilities to load");
        SkillDefinitions.Result loaded = SkillDefinitions.withPushed(
                new SkillDefinitions.Result(Map.of(), Map.of(), List.of()), "studio", lines, Map.of(), metaskills,
                path, names);
        assertTrue(loaded.diagnostics().isEmpty(), () -> "the engine would skip or warn about: " + loaded.diagnostics());
        System.out.println("Loaded " + lines.values().stream().mapToInt(List::size).sum() + " mob lines and "
                + loaded.metaskills.size() + " metaskills from " + path);
    }

    @Test
    void aRestartWritesThemBackUnchanged() throws IOException {
        StudioContent first = StudioContent.read(studioManifest(), "studio-mob.json", null);
        assertNotNull(first);
        java.io.File dir = java.nio.file.Files.createTempDirectory("studio-content").toFile();
        StudioContent stored = new StudioContent(dir);
        assertTrue(stored.updateFromJson(studioManifest()).ok());
        stored.save(java.util.logging.Logger.getAnonymousLogger());
        StudioContent reread = new StudioContent(dir);
        reread.load(java.util.logging.Logger.getAnonymousLogger());
        ContentId mob = ContentId.of("studio", "golem_king").orElseThrow();
        assertEquals(first.mobSkills().get(mob).lines(), reread.mobSkills().get(mob).lines());
        assertEquals(first.mobSkills().get(mob).metaskills(), reread.mobSkills().get(mob).metaskills());
    }

    /**
     * A stranger's push (read as untrusted) is held to an allowlist. Every
     * line Studio sends must pass it, or the test server would quietly drop
     * real abilities; and the ways round the old text filter must not.
     */
    @Test
    void everyLineStudioSendsIsOneAStrangerMayRun() throws IOException {
        SkillDefinitions.Names names = realNames();
        StudioContent content = StudioContent.read(studioManifest(), "studio-mob.json", null);
        assertNotNull(content);
        content.mobSkills().forEach((id, skills) -> {
            for (String line : skills.lines()) {
                assertNull(SkillDefinitions.strangerRefusal(line, false, names), line);
            }
            skills.metaskills().forEach((name, body) -> {
                for (String key : List.of("Skills", "Conditions", "TargetConditions")) {
                    if (body.get(key) instanceof List<?> written) {
                        for (Object entry : written) {
                            assertNull(SkillDefinitions.strangerRefusal(String.valueOf(entry), !key.equals("Skills"), names),
                                    name + " " + key + ": " + entry);
                        }
                    }
                }
            });
        });
    }

    @Test
    void aStrangerRunsNothingAsTheServerAndReachesNoOtherWorld() {
        SkillDefinitions.Names names = realNames();
        for (String line : List.of(
                "command{c=op x} @self",
                "command[c=op x] @self",
                "command\u001f{c=op x} @self",
                "\u0001CMD{c=op x}",
                "consolecommand[c=stop]",
                "skill{s=[ - command[c=op x] ]} @self ~onSpawn",
                "skill{s=[ - skill{s=[ - cmd{c=op x} ]} ]} @self",
                "skill{s=myserver:reward} @self ~onTimer:20",
                "skill:myserver:reward @self",
                "teleportto{l=0,64,0,world} @self",
                "damage{a=1} @playersonserver",
                "message{m=hi} @playersinworld",
                "summon{type=tnt;a=32} @self ~onTimer:1",
                "summon{type=minecraft:wither} @self",
                "summon{type=end_crystal} @self",
                "projectile{onHit=[ - damage{a=1} ]} @target",
                "aura{onTick=[ - damage{a=1} ]} @self",
                "not a line {{{")) {
            assertNotNull(SkillDefinitions.strangerRefusal(line, false, names), line);
        }
        assertNotNull(SkillDefinitions.strangerRefusal("incombat castinstead myserver:reward", true, names));
        assertNotNull(SkillDefinitions.strangerRefusal("incombat cast [ - command{c=op x} ]", true, names));
        for (String line : List.of(
                "damage{a=10} @playersinradius{r=6}",
                "delay 10",
                "summon{type=zombie;a=2} @self",
                "summon{type=studio:golem_minion} @self",
                "skill{s=studio:golem_king.slam} @self ~onTimer:120 ?incombat",
                "skill{s=[ - damage{a=1} - sound{s=entity.generic.explode} ]} @self")) {
            assertNull(SkillDefinitions.strangerRefusal(line, false, names), line);
        }
        assertNull(SkillDefinitions.strangerRefusal("incombat castinstead studio:golem_king.slam", true, names));
    }
}
