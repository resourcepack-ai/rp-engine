package ai.resourcepack.engine.core.skill;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cooldowns, health crossings, timers, placeholders and geometry: the runtime's arithmetic. */
class SkillStateTest {

    private static SkillLine line(String text) {
        return SkillParser.parse(text).line;
    }

    @Test
    void cooldownsArePerKeyAndExpire() {
        SkillState state = new SkillState(null);
        assertFalse(state.cooling("skill:a", 0));
        state.cool("skill:a", 0, 12);
        assertTrue(state.cooling("skill:a", 11_999));
        assertFalse(state.cooling("skill:a", 12_000));
        assertFalse(state.cooling("skill:b", 1));
        assertEquals(2.0, state.remaining("skill:a", 10_000), 1e-9);
        state.cool("skill:c", 0, 0);
        assertFalse(state.cooling("skill:c", 0), "a zero cooldown sets nothing");
    }

    @Test
    void anEqualsThresholdFiresOncePerMob() {
        SkillState state = new SkillState(null);
        SkillLine enrage = line("skill{s=Enrage} ~onDamaged =50%");
        assertFalse(state.crossing(enrage, false), "above it");
        assertTrue(state.crossing(enrage, true), "first time below");
        assertFalse(state.crossing(enrage, true), "still below: not again");
        assertFalse(state.crossing(enrage, true));
        assertFalse(state.crossing(enrage, false), "healed above");
        assertFalse(state.crossing(enrage, true), "and below again: still not, or a phase that heals repeats");
        // Each line keeps its own.
        SkillLine other = line("skill{s=Other} ~onDamaged =25%");
        assertTrue(state.crossing(other, true));
    }

    @Test
    void timersWaitOneIntervalAndCatchUpOnce() {
        SkillState state = new SkillState(null);
        SkillLine every = line("damage{a=1} ~onTimer:100");
        assertFalse(state.due(every, 0, 100), "the first look only schedules it");
        assertFalse(state.due(every, 98, 100));
        assertTrue(state.due(every, 100, 100));
        assertFalse(state.due(every, 102, 100));
        assertTrue(state.due(every, 200, 100));
        // Unloaded for a long while: due once, then a full interval on.
        assertTrue(state.due(every, 10_000, 100));
        assertFalse(state.due(every, 10_002, 100));
        assertTrue(state.due(every, 10_100, 100));
    }

    @Test
    void lineCooldownKeysAreStablePerLine() {
        SkillState state = new SkillState("calm");
        SkillLine a = line("damage{a=1;cooldown=5}");
        SkillLine b = line("damage{a=1;cooldown=5}");
        assertEquals(state.lineKey(a), state.lineKey(a));
        assertFalse(state.lineKey(a).equals(state.lineKey(b)), "two identical lines are two cooldowns");
        assertEquals("calm", state.stance);
    }

    @Test
    void placeholdersInBothSpellingsAndTheEscapes() {
        Map<String, String> values = Map.of("caster/hp", "40", "target/name", "Steve", "caster/max.hp", "100");
        String filled = SkillText.fill("<caster.hp>/{caster_max_hp} hits <target.name><&sc> {trigger_name} <&eq>",
                (scope, field) -> values.get(scope + "/" + field));
        assertEquals("40/100 hits Steve; {trigger_name} =", filled);
        assertEquals("<mob.hp>", SkillText.fill("<mob.hp>", (scope, field) -> null));
        assertEquals("40", SkillText.fill("<mob.hp>", (scope, field) -> scope.equals("caster") ? "40" : null));
    }

    @Test
    void ringsConesAndSpheres() {
        Location centre = new Location(null, 0, 64, 0);
        List<Location> ring = SkillMath.ring(centre, 4, 8);
        assertEquals(8, ring.size());
        for (Location spot : ring) {
            assertEquals(4, Math.hypot(spot.getX(), spot.getZ()), 1e-9);
            assertEquals(64, spot.getY(), 1e-9);
        }
        // Yaw 0 faces +Z: the first point is straight ahead.
        assertEquals(4, ring.get(0).getZ(), 1e-9);
        for (Location spot : SkillMath.sphere(centre, 3, 30)) {
            assertEquals(3, spot.toVector().subtract(centre.toVector()).length(), 1e-9);
        }
        List<Location> cone = SkillMath.cone(centre, 60, 6, 18);
        assertFalse(cone.isEmpty());
        for (Location spot : cone) {
            assertTrue(spot.getZ() > 0, "a cone is in front");
            assertTrue(SkillMath.inCone(centre, spot, 60.001));
        }
        assertFalse(SkillMath.inCone(centre, new Location(null, 0, 64, -5), 90));
        Location ahead = SkillMath.forward(centre, 5, 0);
        assertEquals(5, ahead.getZ(), 1e-9);
    }

    @Test
    void aLeapAimsAtItsTargetAndRespectsItsCap() {
        Location from = new Location(null, 0, 64, 0);
        Location to = new Location(null, 0, 64, 10);
        org.bukkit.util.Vector v = SkillMath.leap(from, to, 0);
        assertTrue(v.getZ() > 0 && Math.abs(v.getX()) < 1e-9 && v.getY() > 0);
        assertTrue(SkillMath.leap(from, to, 0.5).length() <= 0.5 + 1e-9);
    }
}
