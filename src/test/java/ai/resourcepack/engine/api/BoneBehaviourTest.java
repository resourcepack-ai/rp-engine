package ai.resourcepack.engine.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading what a bone does out of its name.
 *
 * <p>The prefixes are ModelEngine's on purpose, so a rig somebody already has
 * works here without being re-authored. That makes matching them exactly a
 * compatibility requirement rather than a preference.
 */
class BoneBehaviourTest {

    @Test
    void anOrdinaryBoneDoesNothingSpecial() {
        assertEquals(BoneBehaviour.NONE, BoneBehaviour.of("wing"));
        assertEquals(BoneBehaviour.NONE, BoneBehaviour.of(""));
        assertEquals(BoneBehaviour.NONE, BoneBehaviour.of(null));
    }

    @Test
    void everyPrefixIsRecognised() {
        assertEquals(BoneBehaviour.HEAD, BoneBehaviour.of("h_head"));
        assertEquals(BoneBehaviour.HEAD_INHERITED, BoneBehaviour.of("hi_head"));
        assertEquals(BoneBehaviour.SEAT, BoneBehaviour.of("p_seat1"));
        assertEquals(BoneBehaviour.HITBOX, BoneBehaviour.of("b_wing"));
        assertEquals(BoneBehaviour.HITBOX_ORIENTED, BoneBehaviour.of("ob_wing"));
        assertEquals(BoneBehaviour.NAMETAG, BoneBehaviour.of("tag_name"));
    }

    @Test
    void theDriverBoneIsAWholeNameRatherThanAPrefix() {
        // ModelEngine's one exception, and copying it exactly is the point.
        assertEquals(BoneBehaviour.DRIVER, BoneBehaviour.of("mount"));
        assertEquals(BoneBehaviour.NONE, BoneBehaviour.of("mountain"),
            "a bone called mountain is scenery, not a driver's seat");
    }

    @Test
    void theLongestPrefixWins() {
        // The one that actually bites: ob_ contains b_, so a wing matched as a
        // plain hitbox would quietly stop turning with its bone.
        assertEquals(BoneBehaviour.HITBOX_ORIENTED, BoneBehaviour.of("ob_wing"));
        assertEquals(BoneBehaviour.HEAD_INHERITED, BoneBehaviour.of("hi_head"));
    }

    @Test
    void caseDoesNotMatterBecauseBlockbenchDoesNotEnforceOne() {
        assertEquals(BoneBehaviour.HEAD, BoneBehaviour.of("H_Head"));
        assertEquals(BoneBehaviour.DRIVER, BoneBehaviour.of("Mount"));
    }

    @Test
    void aPrefixOnItsOwnIsStillThatBone() {
        assertEquals(BoneBehaviour.HEAD, BoneBehaviour.of("h_"));
        assertEquals(BoneBehaviour.HITBOX, BoneBehaviour.of("b_"));
    }

    @Test
    void theQuestionsTheEngineActuallyAsks() {
        assertTrue(BoneBehaviour.HITBOX.isHitbox());
        assertTrue(BoneBehaviour.HITBOX_ORIENTED.isHitbox());
        assertFalse(BoneBehaviour.SEAT.isHitbox());

        assertTrue(BoneBehaviour.HEAD.isHead());
        assertTrue(BoneBehaviour.HEAD_INHERITED.isHead());
        assertFalse(BoneBehaviour.NAMETAG.isHead());

        // A statue has nothing to look with and nobody to carry.
        assertTrue(BoneBehaviour.HEAD.needsAHost());
        assertTrue(BoneBehaviour.DRIVER.needsAHost());
        assertFalse(BoneBehaviour.HITBOX.needsAHost());
        assertFalse(BoneBehaviour.NAMETAG.needsAHost());
    }
}
