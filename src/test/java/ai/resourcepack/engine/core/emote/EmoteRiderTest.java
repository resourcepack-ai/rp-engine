package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.core.model.DisplayLatency;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A worn rig whose wearer is RIDING something.
 *
 * <p>Every number in {@link EmoteDirector} that places or glides a rig was
 * derived for a wearer walking under their own power, and both of them are
 * wrong for a passenger in the same direction: they put the rig in front of
 * the vehicle. The seat rig made that reachable for the first time — before
 * it, nobody was in a vehicle wearing anything.
 *
 * <p>The distinction is not new and is not this class's: {@link DisplayLatency}
 * already says a rig judged against the WORLD and a rig judged against a RIDER
 * take different numbers, and the vehicle's own model already follows it. This
 * pins the emote half to the same rule.
 */
class EmoteRiderTest {

    // ---- the lead --------------------------------------------------------

    /**
     * The lead cancels how stale a player's SELF-REPORTED position is. A
     * passenger does not report one — the server moves the mount and their
     * location is read off it — so there is nothing to cancel and every block
     * of lead is overshoot.
     */
    @Test
    void aPassengerIsNotLedAtAll() {
        assertEquals(0.0, EmoteDirector.leadTicksFor(true, 0), 0.0);
        // Not even on a bad connection: the lead is sized by ping precisely
        // because ping is what makes a self-report stale, and a passenger has
        // no self-report for ping to make stale.
        assertEquals(0.0, EmoteDirector.leadTicksFor(true, 400), 0.0);
    }

    /** And a wearer on their own legs is unaffected — this is the old path. */
    @Test
    void aWalkingWearerIsLedExactlyAsBefore() {
        assertEquals(2.0, EmoteDirector.leadTicksFor(false, 0), 1e-9);
        // Half the round trip: 100ms of ping is one tick of staleness one way.
        assertEquals(3.0, EmoteDirector.leadTicksFor(false, 100), 1e-9);
        // And capped, however bad it gets.
        assertEquals(5.0, EmoteDirector.leadTicksFor(false, 100000), 1e-9);
    }

    /**
     * The lead is unwound rather than dropped, so climbing into a seat mid
     * stance walks the rig back onto the seat instead of snapping it there.
     *
     * <p>Zero lead TICKS rather than a zeroed vector is what buys that: the
     * existing smoothing does the walking, and it has to actually reach zero
     * or a moving vehicle re-arms "has it moved" on every tick for the rest of
     * the journey.
     */
    @Test
    void mountingUnwindsAnExistingLeadRatherThanSnappingIt() {
        Location from = new Location(null, 0, 64, 0);
        // A vehicle at 12 blocks a second, which is 0.6 of a block a tick —
        // far more per tick than any gait, and what pinned the lead at its cap.
        Location to = from.clone().add(0.6, 0, 0);
        Vector lead = new Vector(EmoteStance.MAX_LEAD, 0, 0);

        double previous = lead.getX();
        for (int pass = 0; pass < 4; pass++) {
            lead = EmoteStance.leadFor(lead, from, to, EmoteDirector.leadTicksFor(true, 0));
            assertTrue(lead.getX() < previous, "the lead has to keep shrinking");
            assertTrue(lead.getX() >= 0, "and never swing behind the wearer");
            previous = lead.getX();
        }
        for (int pass = 0; pass < 20; pass++) {
            lead = EmoteStance.leadFor(lead, from, to, EmoteDirector.leadTicksFor(true, 0));
        }
        assertEquals(0.0, lead.lengthSquared(), 0.0,
                "a rider's lead has to BE zero, not approach it — see EmoteStance.LEAD_DEAD_ZONE");
    }

    /**
     * What the bug looked like, as arithmetic. Left in because the number is
     * the argument: two thirds of a block in front of the hull, permanently,
     * is not a subtle misplacement.
     */
    @Test
    void theOldRuleParkedTheRigAheadOfTheHull() {
        Location from = new Location(null, 0, 64, 0);
        Location to = from.clone().add(0.6, 0, 0);
        Vector lead = new Vector();
        for (int pass = 0; pass < 20; pass++) {
            lead = EmoteStance.leadFor(lead, from, to, EmoteDirector.leadTicksFor(false, 0));
        }
        assertEquals(EmoteStance.MAX_LEAD, lead.getX(), 1e-6,
                "pinned at the cap the whole time the vehicle moves");
    }

    // ---- the glide window ------------------------------------------------

    /**
     * A rider is drawn wherever their mount is, which the client lerps over
     * {@link DisplayLatency#TRACKED_ENTITY_TICKS}. Anything that has to stay
     * on the seat has to be behind by the same amount — the vehicle's own
     * model already is, and being a tick tighter is what separates them.
     */
    @Test
    void aPassengersRigGlidesOnTheRidersWindow() {
        assertEquals(DisplayLatency.TRACKED_ENTITY_TICKS, EmoteDirector.carryTicksFor(true));
    }

    /** Off a mount the rig is judged against the world, where the old rule is right. */
    @Test
    void aWalkingWearersRigKeepsTheWorldWindow() {
        assertEquals(EmoteDirector.interpolationTicks(), EmoteDirector.carryTicksFor(false));
    }

    /**
     * The two windows must actually differ, or none of this bought anything.
     * If a future version makes vanilla's entity lerp equal the emote step,
     * this is the test that says the mismatch is gone rather than leaving two
     * names for one number.
     */
    @Test
    void theTwoWindowsAreDifferentNumbers() {
        assertTrue(EmoteDirector.carryTicksFor(true) != EmoteDirector.carryTicksFor(false),
                "a rider's window and the world's window are the whole point of the split");
    }
}
