package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.EntityInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnRulesTest {

    private static EntityInfo.Spawning rules(List<String> biomes, List<String> replaces, String time) {
        return EntityInfo.Spawning.of(List.of("world"), biomes, replaces, 0.5, 0, 7, time, 1, 2, 2);
    }

    @Test
    void aMonsterStandsInForMonstersAndAnAnimalForAnimals() {
        EntityInfo.Spawning any = rules(List.of(), List.of(), "any");
        assertEquals("monster", SpawnRules.kind(org.bukkit.entity.Zombie.class));
        assertEquals("monster", SpawnRules.kind(org.bukkit.entity.Phantom.class), "an Enemy, not a Monster");
        assertEquals("animal", SpawnRules.kind(org.bukkit.entity.PolarBear.class));
        assertEquals(SpawnRules.OTHER, SpawnRules.kind(org.bukkit.entity.IronGolem.class));
        assertTrue(SpawnRules.replaces(any, "SKELETON", "monster", "monster"));
        assertFalse(SpawnRules.replaces(any, "COW", "animal", "monster"));
        assertFalse(SpawnRules.replaces(any, "IRON_GOLEM", SpawnRules.OTHER, SpawnRules.OTHER),
                "nothing spawns naturally as a golem, so a golem-brained mob needs replaces:");
    }

    @Test
    void replacesNamesTheTypesWhateverTheirKind() {
        EntityInfo.Spawning husks = rules(List.of(), List.of("husk"), "any");
        assertTrue(SpawnRules.replaces(husks, "HUSK", "monster", "animal"));
        assertFalse(SpawnRules.replaces(husks, "ZOMBIE", "monster", "monster"));
    }

    @Test
    void worldBiomeLightAndTime() {
        EntityInfo.Spawning night = rules(List.of("desert", "*badlands*"), List.of(), "night");
        assertTrue(SpawnRules.where(night, "world", "desert", 3, 18000));
        assertTrue(SpawnRules.where(night, "world", "wooded_badlands", 3, 18000));
        assertFalse(SpawnRules.where(night, "world_nether", "desert", 3, 18000), "another world");
        assertFalse(SpawnRules.where(night, "world", "plains", 3, 18000), "another biome");
        assertFalse(SpawnRules.where(night, "world", "desert", 12, 18000), "too bright");
        assertFalse(SpawnRules.where(night, "world", "desert", 3, 6000), "daytime");
        assertTrue(SpawnRules.where(night, "world", "desert", 3, 18000 + 24000 * 5), "any day's night");
        EntityInfo.Spawning day = rules(List.of(), List.of(), "day");
        assertTrue(SpawnRules.where(day, "world", "plains", 0, 1000));
        assertFalse(SpawnRules.where(day, "world", "plains", 0, 13000));
    }

    @Test
    void globs() {
        assertTrue(SpawnRules.glob("*forest*", "old_growth_birch_forest"));
        assertTrue(SpawnRules.glob("*forest", "forest"));
        assertTrue(SpawnRules.glob("snowy_*", "snowy_plains"));
        assertFalse(SpawnRules.glob("snowy_*", "plains"));
        assertTrue(SpawnRules.glob("desert", "desert"));
        assertFalse(SpawnRules.glob("desert", "deserts"));
    }
}
