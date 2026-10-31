package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.EntityInfo;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mob options past EntityInfo, templates, MythicMobs drop lines, drop tables and spawner files. */
class MobOptionsTest {

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

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private static ContentId id(String text) {
        return ContentId.parse(text).orElseThrow();
    }

    @Test
    void optionsAreRead() throws IOException {
        write("mypack/entities/knight.yml", """
                knight:
                  type: zombie
                  armor: 12
                  armor-toughness: 4
                  equipment:
                    head: minecraft:iron_helmet
                    hand: {item: minecraft:iron_sword, drop-chance: 0.5}
                  damage-modifiers: {FIRE: 0, PROJECTILE: 0.5, MELEE: 2}
                  despawn: chunk
                  faction: Undead
                  level: 2to4
                  per-level: {health: 10, damage: 1, power: 0.25}
                  invincible: false
                  collidable: false
                  prevent: [leash, rename]
                  kill-messages: ["<target.name> was knighted"]
                  mount: minecraft:horse
                  xp: 5to10
                  boss-bar: {color: RED, fog: true, darken-sky: true}
                """);
        EntityDefinitions.Result result = EntityDefinitions.parse(load());
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics()::toString);
        MobOptions knight = result.options().get(id("mypack:knight"));
        assertEquals(12, knight.armor());
        assertEquals(4, knight.armorToughness());
        assertEquals("minecraft:iron_helmet", knight.equipment().get("head").item);
        assertEquals(0.5, knight.equipment().get("hand").dropChance);
        assertEquals(0, knight.modifier("FIRE"));
        assertEquals(0.5, knight.modifier("PROJECTILE"));
        assertEquals(2, knight.modifier("ENTITY_ATTACK"));
        assertEquals(1, knight.modifier("FALL"));
        assertEquals("chunk", knight.despawn().orElseThrow());
        assertEquals("undead", knight.faction().orElseThrow());
        assertEquals(2, knight.levelMin());
        assertEquals(4, knight.levelMax());
        assertEquals(40, knight.scaled("health", 20, 3));
        assertEquals(1.5, knight.levelPower(3));
        assertFalse(knight.collidable);
        assertTrue(knight.prevents("leash") && knight.prevents("rename"));
        assertEquals("minecraft:horse", knight.mount().orElseThrow());
        assertEquals(5, knight.xpMin);
        assertEquals(10, knight.xpMax);
        assertTrue(knight.barFog && knight.barDarken);
    }

    @Test
    void mythicMobsEquipmentListAndBadValuesWarn() throws IOException {
        write("mypack/entities/a.yml", """
                a:
                  type: zombie
                  equipment: ["iron_helmet HEAD", "DIAMOND_SWORD:0", "stick"]
                  damage-modifiers: ["FIRE 0", "NOT_A_CAUSE 2"]
                  despawn: sometimes
                  level: 0
                """);
        EntityDefinitions.Result result = EntityDefinitions.parse(load());
        MobOptions a = result.options().get(id("mypack:a"));
        assertEquals("iron_helmet", a.equipment().get("head").item);
        assertEquals("DIAMOND_SWORD", a.equipment().get("hand").item);
        assertEquals(0, a.modifier("FIRE"));
        assertTrue(a.despawn().isEmpty());
        assertEquals(1, a.levelMin());
        assertEquals(4, result.diagnostics().size(), result.diagnostics()::toString);
    }

    @Test
    void templatesMergeMapsAndJoinSkills() throws IOException {
        write("mypack/entities/mobs.yml", """
                base:
                  abstract: true
                  health: 40
                  sounds: {hurt: minecraft:entity.pig.hurt, death: minecraft:entity.pig.death}
                  skills: ["heal{a=1} @self ~onTimer:20"]
                brute:
                  extends: base
                  type: zombie
                  sounds: {hurt: minecraft:entity.cow.hurt}
                  Skills: ["damage{a=2} @target ~onAttack"]
                elite:
                  template: brute
                  name: Elite
                looped:
                  type: zombie
                  extends: looped2
                looped2:
                  type: zombie
                  extends: looped
                """);
        MobTemplates.Result templated = MobTemplates.resolve(load());
        EntityDefinitions.Result result = EntityDefinitions.parse(templated.report());
        assertFalse(result.entities().containsKey(id("mypack:base")), "an abstract template is not a mob");
        EntityInfo elite = result.entities().get(id("mypack:elite"));
        assertNotNull(elite);
        assertEquals("ZOMBIE", elite.type());
        assertEquals(40, elite.health());
        assertEquals("minecraft:entity.cow.hurt", elite.sounds().get("hurt"));
        assertEquals("minecraft:entity.pig.death", elite.sounds().get("death"));
        Object skills = templated.report().definitions().stream()
                .filter(d -> d.id().equals(id("mypack:elite"))).findFirst().orElseThrow().body().raw("Skills");
        assertEquals(List.of("heal{a=1} @self ~onTimer:20", "damage{a=2} @target ~onAttack"), skills);
        assertTrue(templated.diagnostics().stream().anyMatch(d -> d.message().contains("loop")));
    }

    @Test
    void dropLinesInMythicMobsForm() {
        List<String> problems = new ArrayList<>();
        MobLoot.Line item = MobLoot.line("minecraft:leather 1to3 0.5", problems).orElseThrow();
        assertEquals(MobLoot.Kind.ITEM, item.kind);
        assertEquals(1, item.min);
        assertEquals(3, item.max);
        assertEquals(0.5, item.third);
        MobLoot.Line exp = MobLoot.line("exp 10to20", problems).orElseThrow();
        assertEquals(MobLoot.Kind.EXP, exp.kind);
        assertEquals(20, exp.max);
        MobLoot.Line table = MobLoot.line("droptable rare 2", problems).orElseThrow();
        assertEquals(MobLoot.Kind.TABLE, table.kind);
        assertEquals("rare", table.what);
        assertEquals(2, table.min);
        MobLoot.Line command = MobLoot.line("command{c=\"say hi <trigger.name>; again\"} 1 0.25", problems).orElseThrow();
        assertEquals(MobLoot.Kind.COMMAND, command.kind);
        assertEquals("say hi <trigger.name>; again", command.what);
        assertEquals(0.25, command.third);
        assertEquals(MobLoot.Kind.NOTHING, MobLoot.line("nothing 1 5", problems).orElseThrow().kind);
        MobLoot.Line map = MobLoot.line(Map.of("item", "zoo:horn", "amount", "2to4", "chance", 0.3), problems)
                .orElseThrow();
        assertEquals(2, map.min);
        assertEquals(4, map.max);
        assertTrue(problems.isEmpty(), problems::toString);
        assertTrue(MobLoot.line("diamond lots", problems).isEmpty());
        assertEquals(1, problems.size());
    }

    @Test
    void weightedTablesPickExactlyTotalItems() throws IOException {
        write("mypack/drop-tables/loot.yml", """
                Rare:
                  TotalItems: 2
                  Drops:
                  - minecraft:diamond 1 1
                  - minecraft:emerald 1 3
                  - nothing 1 0
                Chanced:
                  Drops:
                  - minecraft:stick 1 1
                  - minecraft:bone 1 0
                  - exp 7
                Ranged:
                  MinItems: 1
                  MaxItems: 3
                  Drops:
                  - minecraft:dirt 1 1
                Gated:
                  Conditions: [ "isplayer" ]
                  Drops:
                  - minecraft:gold_ingot 1 1
                """);
        MobLoot.Tables tables = MobLoot.tables(load());
        assertTrue(tables.diagnostics().isEmpty(), tables.diagnostics()::toString);
        Map<ContentId, MobLoot.Table> all = tables.tables();
        assertEquals(4, all.size());
        Random random = new Random(42);
        int diamonds = 0;
        int emeralds = 0;
        for (int i = 0; i < 2000; i++) {
            MobLoot.Loot loot = MobLoot.roll(List.of(MobLoot.line("droptable Rare", new ArrayList<>()).orElseThrow()),
                    all, id("mypack:mob"), c -> true, random);
            assertEquals(2, loot.items.size());
            for (Map.Entry<String, Integer> item : loot.items) {
                if (item.getKey().equals("minecraft:diamond")) {
                    diamonds++;
                } else {
                    emeralds++;
                }
            }
        }
        // Weights 1:3, so about a quarter diamonds.
        double share = diamonds / (double) (diamonds + emeralds);
        assertTrue(share > 0.2 && share < 0.3, "diamond share " + share);

        MobLoot.Loot chanced = MobLoot.roll(List.of(MobLoot.line("table chanced", new ArrayList<>()).orElseThrow()),
                all, id("mypack:mob"), c -> true, random);
        assertEquals(List.of(Map.entry("minecraft:stick", 1)), chanced.items);
        assertEquals(7, chanced.xp);

        for (int i = 0; i < 50; i++) {
            MobLoot.Loot ranged = MobLoot.roll(List.of(MobLoot.line("dt ranged", new ArrayList<>()).orElseThrow()),
                    all, id("mypack:mob"), c -> true, random);
            assertTrue(ranged.items.size() >= 1 && ranged.items.size() <= 3);
        }
        assertTrue(MobLoot.roll(List.of(MobLoot.line("dt gated", new ArrayList<>()).orElseThrow()),
                all, id("mypack:mob"), c -> false, random).items.isEmpty());
        assertEquals(1, MobLoot.roll(List.of(MobLoot.line("dt gated", new ArrayList<>()).orElseThrow()),
                all, id("mypack:mob"), c -> c.equals("isplayer"), random).items.size());
    }

    @Test
    void aTableNamingItselfStops() {
        MobLoot.Table loop = MobLoot.table(id("mypack:loop"), Map.of("Drops", List.of("droptable loop",
                "minecraft:stone 1 1")), "x", new ArrayList<>()).orElseThrow();
        MobLoot.Loot loot = MobLoot.roll(List.of(MobLoot.line("dt loop", new ArrayList<>()).orElseThrow()),
                Map.of(loop.id, loop), id("mypack:mob"), c -> true, new Random(1));
        assertEquals(MobLoot.MAX_DEPTH, loot.items.size());
    }

    @Test
    void spawnerFilesReadBothSpellings() throws IOException {
        write("mypack/spawners/arena.yml", """
                pit:
                  mob: mypack:knight
                  location: world,10.5,64,-3.5
                  max-mobs: 3
                  cooldown: 5
                  leash-range: 12
                  heal-on-leash: true
                mm_style:
                  MobName: mypack:knight
                  World: world
                  X: 1
                  Y: 2
                  Z: 3
                  MaxMobs: 200
                  ActivationRange: 20
                broken:
                  mob: mypack:knight
                """);
        Spawners.Result result = Spawners.parse(load());
        assertEquals(2, result.size());
        Spawners.Spawner pit = result.spawners.get("mypack:pit");
        assertEquals(3, pit.maxMobs);
        assertEquals(5, pit.cooldown);
        assertEquals(12, pit.leashRange);
        assertTrue(pit.healOnLeash);
        assertEquals(-3.5, pit.z);
        Spawners.Spawner mm = result.spawners.get("mypack:mm_style");
        assertEquals(Spawners.MAX_MOBS, mm.maxMobs);
        assertEquals(20, mm.activationRange);
        assertEquals(1, result.diagnostics().size());
    }
}
