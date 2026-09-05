package ai.resourcepack.engine.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a change of animation crossfades over.
 *
 * <p>The case behind it: a vehicle's wheel cycle runs 0 to 360 forwards and 360
 * to 0 in reverse, and the runtime swaps between them off the vehicle's state.
 * Restarted at frame 0, a wheel part way round its turn had nowhere to be but
 * back at the top — a jump, because nothing on a carried rig ever authored a
 * {@code blend} and Studio has no field for one.
 */
class SwapBlendTest {

    private static RigStore.Animation animation(String blendJson) {
        return new com.google.gson.Gson()
                .fromJson("{\"name\":\"spin\",\"length\":1" + blendJson + "}", RigStore.Animation.class);
    }

    private static final RigStore.Animation NONE = animation("");
    private static final RigStore.Animation SHORT = animation(",\"blend\":0.1");
    private static final RigStore.Animation LONG = animation(",\"blend\":1.5");

    /** The rule that was there before, and still is for anything an author triggers. */
    @Test
    void aPlacedRigCutsWhenNobodyAuthoredABlend() {
        assertEquals(0, RigAnimations.swapBlendSeconds(NONE, NONE, false));
    }

    /** The fix: nobody has to author a number for the rig that swaps constantly. */
    @Test
    void aCarriedRigEasesWhenNobodyAuthoredABlend() {
        assertEquals(RigAnimations.CARRIED_SWAP_BLEND, RigAnimations.swapBlendSeconds(NONE, NONE, true));
    }

    /**
     * The longer of the two sides wins, which is what makes going back to rest
     * ease out without a second setting.
     */
    @Test
    void theLongerOfTheTwoSidesWins() {
        assertEquals(1.5, RigAnimations.swapBlendSeconds(SHORT, LONG, false));
        assertEquals(1.5, RigAnimations.swapBlendSeconds(LONG, SHORT, false));
    }

    /** An author asking for MORE than the automatic window still gets it. */
    @Test
    void anAuthoredBlendLongerThanTheFloorIsKept() {
        assertEquals(1.5, RigAnimations.swapBlendSeconds(LONG, NONE, true));
    }

    /**
     * And one asking for less is raised to it. A carried rig's swap is the
     * engine's decision, not a moment the author is choosing to make sharp.
     */
    @Test
    void anAuthoredBlendShorterThanTheFloorIsRaised() {
        assertEquals(RigAnimations.CARRIED_SWAP_BLEND, RigAnimations.swapBlendSeconds(SHORT, NONE, true));
    }

    /**
     * The first pose of all passes null for what came before. It must not throw,
     * and on a placed rig it must still be a cut — the caller separately refuses
     * to start a blend with nothing to blend FROM.
     */
    @Test
    void noPreviousAnimationIsHandled() {
        assertEquals(0, RigAnimations.swapBlendSeconds(NONE, null, false));
        assertEquals(RigAnimations.CARRIED_SWAP_BLEND, RigAnimations.swapBlendSeconds(NONE, null, true));
    }
}
