package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3 of the parity plan: threat tables, the new triggers, mob variables and the new vocabulary. */
class SkillDepthTest {

    @TempDir
    Path content;

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);

    @Test
    void threatGoesToWhoeverHurtMost() {
        ThreatTable table = new ThreatTable();
        assertNull(table.choose(null));
        table.add(A, 10);
        table.add(B, 5);
        assertEquals(A, table.top());
        assertEquals(A, table.choose(null));
        // B overtakes, but by less than 10%: the mob stays on A.
        table.add(B, 5.5);
        assertEquals(B, table.top());
        assertEquals(A, table.choose(A));
        // Past 110% of A's: it turns.
        table.add(B, 1);
        assertEquals(B, table.choose(A));
        // A target off the table is replaced by the top at once.
        assertEquals(B, table.choose(C));
    }

    @Test
    void threatArithmetic() {
        ThreatTable table = new ThreatTable();
        table.set(A, 20);
        table.multiply(A, 0.5);
        assertEquals(10, table.get(A));
        table.add(A, -50);
        assertEquals(0, table.get(A), "threat never goes below nothing");
        table.add(B, 3);
        table.remove(B);
        assertEquals(0, table.get(B));
        table.add(C, 1);
        table.prune(uuid -> uuid.equals(C));
        assertFalse(table.entries().contains(C));
        table.clear();
        assertTrue(table.isEmpty());
        for (int i = 0; i < ThreatTable.MAX + 10; i++) {
            table.add(new UUID(1, i), i);
        }
        assertEquals(ThreatTable.MAX, table.size());
        assertEquals(0, table.get(new UUID(1, 0)), "the least threatening went first");
        assertTrue(table.entries().contains(table.random(new Random(3))));
    }

    @Test
    void newTriggersParse() {
        assertEquals("signal", SkillDefinitions.TRIGGERS.get("signal"));
        assertEquals("shoot", SkillDefinitions.TRIGGERS.get("shoot"));
        assertEquals("shoot", SkillDefinitions.TRIGGERS.get("bowshoot"));
        assertEquals("projectilehit", SkillDefinitions.TRIGGERS.get("projectilehit"));
        SkillParser.Parsed parsed = SkillParser.parse("skill{s=Enrage} @self ~onSignal:enrage");
        assertTrue(parsed.ok());
        assertEquals("enrage", parsed.line.trigger.arg);
    }

    @Test
    void theNewVocabularyIsRegistered() {
        SkillRuntime runtime = new SkillRuntime(plugin(), null);
        SkillDefinitions.Names names = runtime.names();
        for (String mechanic : new String[]{"projectile", "missile", "totem", "chain", "signal", "threat", "taunt",
                "clearthreat", "barcreate", "barset", "barremove", "setvariable", "setvar", "variableadd",
                "variablesubtract", "variableunset", "bonevisible", "partvisibility", "swapmodel", "model",
                "defaultstate"}) {
            assertTrue(names.mechanic(mechanic), mechanic);
        }
        for (String condition : new String[]{"variableequals", "varequals", "variableinrange", "variableisset",
                "level", "faction", "samefaction", "threat"}) {
            assertTrue(names.condition(condition), condition);
        }
        for (String targeter : new String[]{"threattable", "tt", "randomthreattarget", "rtt", "rttl"}) {
            assertTrue(names.targeter(targeter), targeter);
        }
    }

    @Test
    void aMobsThreatTableAndVariablesLoad() throws IOException {
        write("zoo/pack.yml", "{}\n");
        write("zoo/entities/boss.yml", """
                boss:
                  type: ZOMBIE
                  threat-table: true
                  variables: {phase: 1, Mood: calm}
                  skills:
                  - setvariable{var=phase;value=2} @self ~onDamaged =50%
                  - skill{s=Rage} @self ~onSignal:rage
                quiet:
                  type: ZOMBIE
                  Modules: {ThreatTable: true}
                plain:
                  type: ZOMBIE
                """);
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
        SkillDefinitions.Result result = SkillDefinitions.parse(loaded, new SkillRuntime(plugin(), null).names());
        SkillDefinitions.MobSkillSet boss = result.mobs.get(ContentId.parse("zoo:boss").orElseThrow());
        assertTrue(boss.threatTable);
        assertEquals(Map.of("phase", "1", "mood", "calm"), boss.variables);
        assertEquals(2, boss.lines.size());
        assertTrue(result.mobs.get(ContentId.parse("zoo:quiet").orElseThrow()).threatTable);
        assertFalse(result.mobs.containsKey(ContentId.parse("zoo:plain").orElseThrow()));
    }

    @Test
    void aProjectileLineKeepsAllThreeCallbacks() {
        SkillParser.Parsed parsed = SkillParser.parse("projectile{v=14;md=30;hR=1;vR=1.5;onTick=[ - particles{p=flame;a=2} ];"
                + "onHit=[ - damage{a=2} - command{c=\"say proj-hit <target.name>\"} ];"
                + "onEnd=[ - command{c=\"say proj-end\"} ]} @target ~onTimer:60");
        assertTrue(parsed.ok(), parsed.error);
        assertEquals("[ - command{c=\"say proj-end\"} ]", parsed.line.args.get("onend"));
        assertEquals(2, SkillParser.inline(parsed.line.args.get("onhit")).size());
    }

    @Test
    void variableNamesDropTheCasterScope() {
        assertEquals("phase", SkillState.varName("caster.Phase"));
        assertEquals("phase", SkillState.varName(" phase "));
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Enough of a plugin for NamespacedKey: a name. */
    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(SkillDepthTest.class.getClassLoader(), new Class<?>[]{Plugin.class},
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
                            return null;
                    }
                });
    }
}
