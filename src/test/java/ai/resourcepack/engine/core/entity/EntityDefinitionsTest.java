package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.EntityDrop;
import ai.resourcepack.engine.api.EntityInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.MobState;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityDefinitionsTest {

    @TempDir
    Path content;

    @BeforeEach
    void setUp() throws IOException {
        write("mypack/pack.yml", "{}\n");
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private EntityDefinitions.Result parse() {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl())
                .load(content, ContentSource.AUTHORED);
        return EntityDefinitions.parse(loaded);
    }

    private static boolean says(EntityDefinitions.Result result, String fragment) {
        return result.diagnostics().stream().anyMatch(d -> d.message().contains(fragment));
    }

    @Test
    void anAnimatedMobReadsEveryField() throws IOException {
        write("mypack/entities/rhino.yml", """
                rhino:
                  type: polar_bear
                  model: mypack:rhino
                  name: "&7Rhino"
                  name-visible: false
                  health: 40
                  damage: 6
                  speed: 1.5
                  knockback-resistance: 0.5
                  height: 1.4
                  turn: 180
                  head-bone: head
                  ai: true
                  burns: false
                  xp: 5
                  animations: {idle: idle, walk: walk, attack: bite, death: die}
                  sounds: {ambient: mypack:grunt, hurt: entity.pig.hurt}
                  drops:
                    - {item: minecraft:leather, min: 1, max: 3}
                    - {item: mypack:horn, chance: 0.25}
                    - minecraft:bone
                """);
        EntityDefinitions.Result result = parse();
        EntityInfo rhino = result.entities().get(ContentId.parse("mypack:rhino").orElseThrow());

        assertEquals("POLAR_BEAR", rhino.type());
        assertFalse(rhino.nameVisible());
        assertEquals(40, rhino.health());
        assertEquals(6, rhino.damage());
        assertEquals(1.5, rhino.speed());
        assertEquals(0.5, rhino.knockbackResistance());
        assertEquals(1.4, rhino.height());
        assertEquals(180f, rhino.turn());
        assertEquals("head", rhino.headBone().orElseThrow());
        assertEquals(5, rhino.xp());
        assertEquals(Map.of(MobState.IDLE, "idle", MobState.WALK, "walk",
                MobState.ATTACK, "bite", MobState.DEATH, "die"), rhino.animations());
        assertEquals("mypack:grunt", rhino.sounds().get("ambient"));
        assertTrue(rhino.silent(), "a mob with sounds of its own is silenced");

        List<EntityDrop> drops = rhino.drops();
        assertEquals(3, drops.size());
        assertEquals(3, drops.get(0).max());
        assertEquals(0.25, drops.get(1).chance());
        assertEquals("minecraft:bone", drops.get(2).item());
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
    }

    @Test
    void theOldShapeStillMeansWhatItMeant() throws IOException {
        write("mypack/entities/guard.yml", """
                guard:
                  type: ZOMBIE
                  name: "&cTemple Guard"
                  health: 40
                  tags: [temple]
                """);
        EntityInfo guard = parse().entities().get(ContentId.parse("mypack:guard").orElseThrow());
        assertTrue(guard.nameVisible(), "a named entity showed its name before name-visible existed");
        assertTrue(guard.ai());
        assertFalse(guard.burns());
        assertEquals(1, guard.speed());
        assertTrue(guard.animations().isEmpty());
        assertTrue(guard.drops().isEmpty());
    }

    @Test
    void mistakesAreSaidAndDoNotCostTheMob() throws IOException {
        write("mypack/entities/odd.yml", """
                odd:
                  type: ZOMBIE
                  model: mypack:odd
                  speed: 9
                  damage: lots
                  animations: {idle: idle, dance: boogie}
                  sounds: {growl: mypack:grr}
                  drops: minecraft:dirt
                """);
        EntityDefinitions.Result result = parse();
        EntityInfo odd = result.entities().get(ContentId.parse("mypack:odd").orElseThrow());
        assertEquals(4, odd.speed(), "clamped to the most there is");
        assertEquals(0, odd.damage());
        assertEquals(Map.of(MobState.IDLE, "idle"), odd.animations());
        assertTrue(odd.sounds().isEmpty());
        assertTrue(says(result, "speed: 9 is outside"));
        assertTrue(says(result, "damage: lots is not a number"));
        assertTrue(says(result, "dance is not a state"));
        assertTrue(says(result, "growl is not one of"));
        assertTrue(says(result, "drops: is not a list"));
    }

    @Test
    void animationsWithNoModelAreSaid() throws IOException {
        write("mypack/entities/ghost.yml", """
                ghost:
                  type: ZOMBIE
                  animations: {idle: idle}
                """);
        assertTrue(says(parse(), "no model: to play them on"));
    }
}
