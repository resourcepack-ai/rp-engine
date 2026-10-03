package ai.resourcepack.engine.core.emote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code Emotes.wear(player, null)} after something was worn.
 *
 * <p>Null is documented as "this state wears nothing", and a vehicle seat with
 * the seat rig off, or an addon putting its rider back on their own feet, says
 * exactly that. The session it lands on was opened by {@code beginDriven},
 * which never gave it a stand-in to fall back to, so the swap set
 * {@code session.emote} to null, threw from {@code spawnProps}, and left the
 * session in the map. Every tick after that threw from {@code animationTime}
 * before reaching anybody later in the map, which stopped every emote and every
 * worn rig on the server. The test server logged it seventeen thousand times.
 */
class EmoteWearNothingTest {

    @Test
    void aDrivenSessionKeepsItsStandIn() {
        EmoteStore.Emote standIn = new EmoteStore.Emote();
        assertSame(standIn, EmoteDirector.restFor(null, true, standIn));
    }

    @Test
    void aGroupKeepsItsRestStateAsBefore() {
        EmoteStore.Emote rest = new EmoteStore.Emote();
        assertSame(rest, EmoteDirector.restFor(new EmoteStore.Group(), false, rest));
    }

    @Test
    void anOrdinaryEmoteHasNoRestState() {
        // A one-shot never swaps what it wears, so it never needs one.
        assertNull(EmoteDirector.restFor(null, false, new EmoteStore.Emote()));
    }

    @Test
    void wearingNothingNeverLeavesTheSessionWithoutAnEmote() {
        EmoteStore.Emote standIn = new EmoteStore.Emote();
        EmoteStore.Emote rest = EmoteDirector.restFor(null, true, standIn);
        assertNotNull(EmoteDirector.wornOrRest(null, rest));
    }
}
