package ai.resourcepack.engine.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MythicMobs' skill line grammar, as people write it. */
class SkillParserTest {

    private static SkillLine line(String text) {
        SkillParser.Parsed parsed = SkillParser.parse(text);
        assertTrue(parsed.ok(), () -> text + ": " + parsed.error);
        return parsed.line;
    }

    private static String error(String text) {
        SkillParser.Parsed parsed = SkillParser.parse(text);
        assertFalse(parsed.ok(), () -> text + " should not parse");
        return parsed.error;
    }

    @Test
    void theWholeLine() {
        SkillLine l = line("- damage{a=8;ignorearmor=true} @PIR{r=6;limit=3} ~onTimer:100 ?stance{s=angry} "
                + "?!haspotioneffect{type=weakness} ?~isplayer <50% 0.3");
        assertEquals("damage", l.mechanic);
        assertEquals("8", l.args.get("amount", "a"));
        assertEquals("true", l.args.get("ignorearmor"));
        assertEquals("pir", l.targeter.name);
        assertEquals("6", l.targeter.args.get("r"));
        assertEquals("3", l.targeter.args.get("limit"));
        assertEquals("timer", l.trigger.name);
        assertEquals("100", l.trigger.arg);
        assertEquals(3, l.conditions.size());
        assertFalse(l.conditions.get(0).negate);
        assertTrue(l.conditions.get(1).negate);
        assertEquals("haspotioneffect", l.conditions.get(1).ref.name);
        assertTrue(l.conditions.get(2).onTrigger);
        assertNotNull(l.health);
        assertFalse(l.health.once);
        assertEquals(0.3, l.chance, 1e-9);
    }

    @Test
    void namesAreCaseInsensitiveAndOnIsOptional() {
        SkillLine l = line("Damage{A=2} @Target ~OnAttack");
        assertEquals("damage", l.mechanic);
        assertEquals("2", l.args.get("a"));
        assertEquals("target", l.targeter.name);
        assertEquals("attack", l.trigger.name);
        assertEquals("damaged", SkillDefinitions.MobSkillSet.trigger(line("heal @self ~onHurt")));
    }

    @Test
    void noTriggerIsOnCombatAndBareMechanicsHaveNoArgs() {
        SkillLine l = line("extinguish");
        assertEquals("combat", l.triggerName());
        assertNull(l.targeter);
        assertTrue(l.args.values.isEmpty());
        assertEquals(1, l.chance, 1e-9);
    }

    @Test
    void squareBracketsAndSpacesInsideBracesAndQuotes() {
        SkillLine l = line("message[ m=\"Hello <target.name>; welcome\" ] @PIR{ r = 10 }");
        assertEquals("Hello <target.name>; welcome", l.args.get("m"));
        assertEquals("10", l.targeter.args.get("r"));
        SkillLine single = line("message{m='it is {hp} now'} @trigger");
        assertEquals("it is {hp} now", single.args.get("m"));
    }

    @Test
    void delayTakesItsTicksAndSkillTakesAColonName() {
        assertEquals("20", line("delay 20").args.get("ticks"));
        assertEquals("delay", line("delay 20").mechanic);
        SkillLine s = line("skill:GroundSlam @target");
        assertEquals("skill", s.mechanic);
        assertEquals("GroundSlam", s.args.get("s"));
    }

    @Test
    void effectPrefixesStayPartOfTheName() {
        assertEquals("effect:particles", line("effect:particles{p=flame;a=20} @self").mechanic);
    }

    @Test
    void healthModifiers() {
        SkillLine below = line("skill{s=Enrage} ~onDamaged <50%");
        assertTrue(below.health.test(40, 100));
        assertFalse(below.health.test(60, 100));
        assertFalse(below.health.once);
        SkillLine once = line("skill{s=Enrage} ~onDamaged =50%");
        assertTrue(once.health.once);
        assertTrue(once.health.test(49, 100), "=50% means below 50% in MythicMobs");
        SkillLine band = line("skill{s=Mid} ~onDamaged =30%-50%");
        assertTrue(band.health.test(40, 100));
        assertFalse(band.health.test(20, 100));
        SkillLine absolute = line("heal{a=1} ~onTimer:20 >200");
        assertTrue(absolute.health.test(250, 1000));
        assertFalse(absolute.health.test(150, 1000));
    }

    @Test
    void inlineConditionListsInTargeterOptions() {
        SkillLine l = line("damage{a=1} @PIR{r=10;conditions=[ - isplayer true - stance{s=x} false ]}");
        assertEquals("[ - isplayer true - stance{s=x} false ]", l.targeter.args.get("conditions"));
        assertEquals(List.of("isplayer true", "stance{s=x} false"),
                SkillParser.inline(l.targeter.args.get("conditions")));
    }

    @Test
    void inlineSkillListsAndBareNames() {
        assertEquals(List.of("damage{a=1}", "particles{p=flame;a=5} @self"),
                SkillParser.inline("[ - damage{a=1} - particles{p=flame;a=5} @self ]"));
        assertEquals(List.of("skill{s=Burn}"), SkillParser.inline("Burn"));
        // A dash inside an argument is not a new entry.
        assertEquals(List.of("message{m=\"a - b\"}"), SkillParser.inline("[ - message{m=\"a - b\"} ]"));
    }

    @Test
    void badLinesSayWhy() {
        assertTrue(error("damage{a=1").contains("never closed"));
        assertTrue(error("damage{a=1}} @self").contains("nothing open"));
        assertTrue(error("damage @self @target").contains("Two targeters"));
        assertTrue(error("damage ~onAttack ~onDamaged").contains("Two triggers"));
        assertTrue(error("damage 1.5").contains("chance"));
        assertTrue(error("damage{a=1} banana").contains("Did not understand banana"));
        assertTrue(error("damage <half").contains("health modifier"));
        assertTrue(error("message{m=\"open} @self").contains("quote"));
        assertTrue(error("   ").contains("empty"));
        assertTrue(error("damage @").contains("no targeter"));
    }

    @Test
    void conditionLinesWithActions() {
        SkillLine.Cond plain = SkillParser.condition("- stance{s=phase2}", false).line.conditions.get(0);
        assertEquals("true", plain.action);
        SkillLine.Cond orElse = SkillParser.condition("distance{d=<6} orElseCast Charge", false).line.conditions.get(0);
        assertEquals("orelsecast", orElse.action);
        assertEquals("Charge", orElse.actionArg);
        SkillLine.Cond power = SkillParser.condition("night power 2", false).line.conditions.get(0);
        assertEquals("power", power.action);
        assertEquals("2", power.actionArg);
        assertFalse(SkillParser.condition("night maybe", false).ok());
        assertFalse(SkillParser.condition("night cast", false).ok());
    }

    @Test
    void theLongFormCompilesToTheSameLine() {
        String text = SkillParser.longForm(Map.of(
                "mechanic", "leap",
                "args", Map.of("velocity", 140),
                "target", "@target",
                "trigger", "onTimer:100",
                "if", List.of("distance{d=4to16}"),
                "chance", 0.5));
        SkillLine l = line(text);
        assertEquals("leap", l.mechanic);
        assertEquals("140", l.args.get("velocity"));
        assertEquals("target", l.targeter.name);
        assertEquals("timer", l.trigger.name);
        assertEquals("distance", l.conditions.get(0).ref.name);
        assertEquals(0.5, l.chance, 1e-9);
        assertNull(SkillParser.longForm(Map.of("target", "@self")));
        // A message with spaces and a semicolon survives being made into a line.
        SkillLine said = line(SkillParser.longForm(Map.of("mechanic", "message",
                "args", Map.of("m", "Run; now"), "target", "PIR{r=5}")));
        assertEquals("Run; now", said.args.get("m"));
    }

    /** Lines lifted from public MythicMobs boss files, unchanged. */
    @Test
    void aCorpusOfRealLinesParses() {
        for (String text : List.of(
                "- skill{s=SkeletonKingSummon} @self ~onTimer:200 0.5",
                "- throw{velocity=15;velocityY=5} @PlayersInRadius{r=5}",
                "- effect:particles{p=largeexplode;amount=5;hS=1;vS=1} @self",
                "- effect:sound{s=entity.generic.explode;v=1;p=0.5} @self",
                "- message{m=\"<mob.name>&f: You dare challenge me?\"} @PlayersInRadius{r=30} ~onSpawn",
                "- potion{type=SLOW;duration=100;level=2} @target ~onAttack 0.25",
                "- leap{velocity=200} @target ~onTimer:100",
                "- summon{type=SkeletalMinion;amount=3;radius=4} @self ~onDamaged 0.1",
                "- lightning @target ~onAttack 0.1",
                "- ignite{ticks=100} @PIR{r=4} ~onTimer:60",
                "- heal{amount=20} @self ~onDamaged <25% 0.2",
                "- setstance{stance=enraged} @self ~onDamaged =50%",
                "- skill{s=Phase2Begin} @self ~onDamaged =50%",
                "- randomskill{skills=Slam,Charge,Roar} @target ~onTimer:160",
                "- command{c=\"broadcast <caster.name> has fallen!\"} @self ~onDeath",
                "- sendtitle{title=\"&4Phase 2\";subtitle=\"&cIt is angry\";d=40} @PIR{r=40} ~onDamaged =50%",
                "- particlering{particle=flame;radius=4;points=24;amount=1} @self ~onTimer:40",
                "- teleport{spreadh=5;spreadv=0} @target ~onTimer:300 ?targetwithin{d=20}",
                "- explosion{yield=2;fire=false;blockdamage=false} @self ~onDeath",
                "- potion{type=POISON;duration=60;level=1;force=true} @PIR{r=6} ~onTimer:40 ?!stance{s=calm}",
                "- damage{amount=6;ignorearmor=false} @EntitiesInRadius{r=4;ignore=ARMOR_STAND} ~onTimer:80",
                "- delay 10",
                "- skill{s=ChargeHit;delay=20} @target",
                "- velocity{m=set;x=0;y=1.2;z=0} @self",
                "- state{mid=golem;s=attack} @self ~onAttack",
                "- pull{velocity=8} @PIR{r=15} ~onTimer:240 0.5",
                "- shootfireball{y=1;v=1.5} @target ~onTimer:60 ?distance{d=5to20}",
                "- actionmessage{m=\"&cThe ground shakes!\"} @PIR{r=20}",
                "- aura{auraName=venom;onTick=[ - damage{a=1} - effect:particles{p=mobspell;a=5} ];interval=20;duration=100} @target ~onAttack")) {
            line(text);
        }
    }

    @Test
    void rangedValues() {
        Random random = new Random(1);
        assertTrue(Ranged.parse("2to10").get().test(5));
        assertFalse(Ranged.parse("2to10").get().test(11));
        assertTrue(Ranged.parse("2-10").get().test(2));
        assertTrue(Ranged.parse("<6").get().test(5.9));
        assertFalse(Ranged.parse("<6").get().test(6));
        assertTrue(Ranged.parse(">=6").get().test(7));
        assertTrue(Ranged.parse("50%").get().percent);
        assertTrue(Ranged.parse("-5to5").get().test(-3));
        assertTrue(Ranged.parse("nope").isEmpty());
        for (int i = 0; i < 50; i++) {
            double picked = Ranged.pick("2to5", random).get();
            assertTrue(picked >= 2 && picked <= 5 && picked == Math.rint(picked));
        }
        assertEquals(1.5, Ranged.pick("1.5", random).get(), 1e-9);
        assertTrue(Ranged.pick("<caster.hp>", random).isEmpty());
    }
}
