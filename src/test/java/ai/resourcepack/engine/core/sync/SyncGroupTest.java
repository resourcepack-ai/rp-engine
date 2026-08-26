package ai.resourcepack.engine.core.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncGroupTest {

    private SyncGroup group;

    @BeforeEach
    void setUp() {
        group = new SyncGroup();
        group.claim("48213097", "Notch");
    }

    private void join(String player) {
        group.invite("Notch", player);
        group.accept(player);
    }

    @Test
    void theOwnerReceivesTheirOwnPushes() {
        assertEquals(List.of("Notch"), group.recipients("48213097"));
        assertEquals("48213097", group.codeOf("Notch").orElseThrow());
    }

    @Test
    void anInviteHasToBeAcceptedBeforeAnythingReachesThem() {
        assertEquals(SyncGroup.Result.OK, group.invite("Notch", "Steve"));

        // A push changes somebody's client mid-session. It should not happen
        // because a stranger typed their name.
        assertEquals(List.of("Notch"), group.recipients("48213097"));
        assertTrue(group.invited("Steve"));

        assertEquals("48213097", group.accept("Steve").orElseThrow());
        assertEquals(List.of("Notch", "Steve"), group.recipients("48213097"));
    }

    @Test
    void membersKeepTheOrderTheyAcceptedIn() {
        join("Steve");
        join("Alex");

        assertEquals(List.of("Notch", "Steve", "Alex"), group.recipients("48213097"));
    }

    @Test
    void acceptTakesNoArgumentBecauseThereIsOnlyEverOneInvite() {
        group.claim("11112222", "Herobrine");
        group.invite("Notch", "Steve");
        group.invite("Herobrine", "Steve");

        // The second invite replaces the first, so accept never has to ask
        // which one. Two pending invites would need a token, and a token is a
        // thing to copy and paste.
        assertEquals("11112222", group.accept("Steve").orElseThrow());
    }

    @Test
    void denyingLeavesNothingBehind() {
        group.invite("Notch", "Steve");

        assertEquals(SyncGroup.Result.OK, group.deny("Steve"));
        assertFalse(group.invited("Steve"));
        assertEquals(SyncGroup.Result.NO_INVITE, group.deny("Steve"));
        assertTrue(group.accept("Steve").isEmpty());
    }

    @Test
    void namesAreMatchedRegardlessOfCase() {
        group.invite("notch", "steve");

        // Minecraft names are case-insensitive to type and case-preserving to
        // display, so a comparison that is not folded fails for the one person
        // who typed their friend's name in lowercase.
        assertTrue(group.invited("STEVE"));
        assertEquals("48213097", group.accept("Steve").orElseThrow());
    }

    @Test
    void somebodyWithNoCodeHasNothingToShare() {
        assertEquals(SyncGroup.Result.NOT_SYNCED, group.invite("Steve", "Alex"));
    }

    @Test
    void youCannotInviteYourself() {
        assertEquals(SyncGroup.Result.SELF, group.invite("Notch", "notch"));
    }

    @Test
    void somebodyAlreadyReceivingCannotBeInvitedAway() {
        join("Steve");

        assertEquals(SyncGroup.Result.ALREADY, group.invite("Notch", "Steve"));
        group.claim("11112222", "Herobrine");
        assertEquals(SyncGroup.Result.ALREADY, group.invite("Herobrine", "Steve"));
    }

    @Test
    void removingSomebodySaysWhichSyncTheyWereOn() {
        join("Steve");

        // The code comes back so the caller can take the pack away too. A
        // removal that only stops FUTURE pushes leaves them holding what they
        // already had, which is not what anybody means by remove.
        assertEquals("48213097", group.remove("Notch", "Steve").orElseThrow());
        assertEquals(List.of("Notch"), group.recipients("48213097"));
        assertTrue(group.remove("Notch", "Steve").isEmpty());
    }

    @Test
    void leavingIsTheSameFromTheOtherSide() {
        join("Steve");

        assertEquals("48213097", group.leave("Steve").orElseThrow());
        assertEquals(List.of("Notch"), group.recipients("48213097"));
        assertTrue(group.leave("Steve").isEmpty());
    }

    @Test
    void stoppingEndsItForEverybodyAndSaysWho() {
        join("Steve");
        join("Alex");

        assertEquals(List.of("Notch", "Steve", "Alex"), group.stop("Notch"));
        assertTrue(group.recipients("48213097").isEmpty());
        assertTrue(group.codeOf("Notch").isEmpty());
        assertTrue(group.receiving("Steve").isEmpty());
    }

    @Test
    void stoppingAlsoDropsInvitesNobodyAnsweredYet() {
        group.invite("Notch", "Steve");

        group.stop("Notch");

        assertFalse(group.invited("Steve"));
        assertTrue(group.accept("Steve").isEmpty());
    }

    @Test
    void theOwnerLeavingEndsTheWholeThing() {
        join("Steve");

        // The group lives exactly as long as the sync. Nobody inherits it,
        // which is the whole reason this is not a party.
        assertEquals(List.of("Notch", "Steve"), group.forget("Notch"));
        assertTrue(group.receiving("Steve").isEmpty());
    }

    @Test
    void aMemberLeavingEndsNothingButTheirOwnShare() {
        join("Steve");
        join("Alex");

        assertTrue(group.forget("Steve").isEmpty());
        assertEquals(List.of("Notch", "Alex"), group.recipients("48213097"));
    }

    @Test
    void anInviteToASyncThatHasEndedAcceptsNothing() {
        group.invite("Notch", "Steve");
        group.forget("Notch");

        assertTrue(group.accept("Steve").isEmpty());
    }

    @Test
    void receivingAnswersForBothSides() {
        join("Steve");

        assertEquals("48213097", group.receiving("Notch").orElseThrow());
        assertEquals("48213097", group.receiving("Steve").orElseThrow());
        assertTrue(group.receiving("Alex").isEmpty());
    }

    @Test
    void nullArgumentsAnswerEmpty() {
        assertTrue(group.codeOf(null).isEmpty());
        assertTrue(group.receiving(null).isEmpty());
        assertTrue(group.leave(null).isEmpty());
        assertTrue(group.recipients(null).isEmpty());
        assertFalse(group.invited(null));
        assertEquals(SyncGroup.Result.NO_INVITE, group.deny(null));
    }
}
