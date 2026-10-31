package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loading skills from a content folder: a bad line costs that line. */
class SkillDefinitionsTest {

    @TempDir
    Path content;

    /** A fixed vocabulary, so the test does not need the runtime. */
    private static final SkillDefinitions.Names NAMES = new SkillDefinitions.Names() {
        @Override
        public boolean mechanic(String name) {
            return Set.of("damage", "skill", "message", "throw", "setstance", "delay", "sound", "leap").contains(name);
        }

        @Override
        public boolean targeter(String name) {
            return Set.of("self", "target", "pir", "trigger").contains(name);
        }

        @Override
        public boolean condition(String name) {
            return Set.of("stance", "distance", "isplayer").contains(name);
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        write("zoo/pack.yml", "{}\n");
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private SkillDefinitions.Result parse() {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
        return SkillDefinitions.parse(loaded, NAMES);
    }

    private static Diagnostic said(SkillDefinitions.Result result, String fragment) {
        return result.diagnostics().stream().filter(d -> d.message().contains(fragment)).findFirst()
                .orElseThrow(() -> new AssertionError("no diagnostic with " + fragment + " in " + result.diagnostics()));
    }

    @Test
    void aMobsLinesLoadAndBadOnesAreSkippedWithTheirPlace() throws IOException {
        write("zoo/entities/golem.yml", """
                golem:
                  type: IRON_GOLEM
                  stance: calm
                  Skills:
                  - damage{a=8} @target ~onAttack
                  - explode @self ~onDeath
                  - damage{a=1} @everyone ~onAttack
                  - damage{a=1} ~onBanana
                  - damage{a=1} ~onTimer
                  - damage{a=1 @self
                  - mechanic: throw
                    args: {velocity: 15, velocityY: 5}
                    target: "@PIR{r=5}"
                    trigger: onTimer:100
                  - message{m=hi} @trigger ~onInteract ?isplayer
                  - message{m="&6King&f: Who goes there?"} @PIR{r=30} ~onSpawn
                  - myplugin:smite{d=4} @target ~onAttack
                """);
        SkillDefinitions.Result result = parse();
        SkillDefinitions.MobSkillSet golem = result.mobs.get(ContentId.parse("zoo:golem").orElseThrow());
        assertEquals(5, golem.lines.size(), result.diagnostics().toString());
        // A plugin's mechanic not registered yet is kept: it registers after this load.
        assertEquals("myplugin:smite", golem.lines.get(4).mechanic);
        said(result, "not registered yet");
        // YAML read the last line as a map (it has ": " in it); it is put back together.
        assertEquals("&6King&f: Who goes there?", golem.lines.get(3).args.get("m"));
        assertEquals("calm", golem.stance);
        assertEquals("throw", golem.lines.get(1).mechanic);
        assertEquals("timer", SkillDefinitions.MobSkillSet.trigger(golem.lines.get(1)));
        Diagnostic explode = said(result, "no mechanic explode");
        assertTrue(explode.origin().contains("golem.yml"));
        assertEquals("golem.skills[2]", explode.where().orElseThrow());
        said(result, "no targeter @everyone");
        said(result, "no trigger ~onbanana");
        said(result, "~onTimer needs its interval");
        said(result, "never closed");
    }

    @Test
    void metaskillsUseMythicMobsShapeAndAreFoundIgnoringCase() throws IOException {
        write("zoo/skills/boss.yml", """
                GroundSlam:
                  Cooldown: 12
                  Conditions:
                  - stance{s=phase2}
                  - distance{d=<6} orElseCast Charge
                  TargetConditions:
                  - isplayer
                  Skills:
                  - sound{s=entity.generic.explode} @self
                  - delay 12
                  - damage{a=10} @PIR{r=6}
                  - throw{velocity=8;velocityY=12} @PIR{r=6} ~onAttack
                  OnCooldownSkill: Grumble
                Broken:
                  Conditions:
                  - wishful{x=1}
                  Skills:
                  - nonsense
                """);
        SkillDefinitions.Result result = parse();
        SkillDefinitions.Metaskill slam = result.metaskills.get(ContentId.parse("zoo:groundslam").orElseThrow());
        assertEquals(12, slam.cooldown, 1e-9);
        assertEquals(2, slam.conditions.size());
        assertEquals("orelsecast", slam.conditions.get(1).action);
        assertEquals(1, slam.targetConditions.size());
        assertEquals(4, slam.lines.size());
        assertEquals("Grumble", slam.onCooldownSkill);
        said(result, "is ignored");
        SkillDefinitions.Metaskill broken = result.metaskills.get(ContentId.parse("zoo:broken").orElseThrow());
        assertTrue(broken.lines.isEmpty());
        assertTrue(broken.conditions.isEmpty());
        said(result, "no condition wishful");
        said(result, "no mechanic nonsense");
    }

    @Test
    void tooManyLinesAreCapped() throws IOException {
        StringBuilder yaml = new StringBuilder("spam:\n  type: ZOMBIE\n  skills:\n");
        for (int i = 0; i < SkillDefinitions.MAX_LINES + 5; i++) {
            yaml.append("  - damage{a=1} @target ~onAttack\n");
        }
        write("zoo/entities/spam.yml", yaml.toString());
        SkillDefinitions.Result result = parse();
        assertEquals(SkillDefinitions.MAX_LINES,
                result.mobs.get(ContentId.parse("zoo:spam").orElseThrow()).lines.size());
        said(result, "More than");
    }

    @Test
    void aMobWithoutSkillsIsNotListed() throws IOException {
        write("zoo/entities/plain.yml", "plain:\n  type: ZOMBIE\n");
        assertFalse(parse().mobs.containsKey(ContentId.parse("zoo:plain").orElseThrow()));
    }
}
