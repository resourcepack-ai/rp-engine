package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.Emotes;
import ai.resourcepack.engine.api.event.EmoteRigSpawnEvent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a plugin carrying a rig on its own seat is handed: where the rig's
 * displays stand, and which displays they are.
 */
class EmoteRigApiTest {

    /**
     * The rig stands one block of skeleton space above the feet, scaled to
     * vanilla's player size. A seat puts its passenger point here, so this is
     * the number their riders sit at.
     */
    @Test
    void theRigStandsAtVanillaScaleAboveTheFeet() {
        assertEquals(15.0 / 16.0, EmoteDirector.rigOriginOffset(), 0.0);
    }

    /**
     * The API's default is a second copy of the engine's number — it exists
     * only so a foreign implementation compiled against an older API still
     * links — and a second copy is exactly the thing that drifts. This is
     * what stops it.
     */
    @Test
    void theApiDefaultIsTheEnginesNumber() {
        Emotes bare = defaultsOnly();
        assertEquals(EmoteDirector.rigOriginOffset(), bare.rigOriginOffset(), 0.0);
        // An implementation that knows no rigs has none to report, and says so
        // with an empty list rather than a null a caller would have to check.
        assertArrayEquals(new int[0], bare.passengerEntityIds(null));
    }

    /**
     * A listener is handed the list, not the engine's copy of it: mutating
     * what it was given, or what it passed in, changes nothing for the next
     * listener.
     */
    @Test
    void theEventsListCannotBeChangedFromOutside() {
        int[] given = {3, 1, 4};
        EmoteRigSpawnEvent event = new EmoteRigSpawnEvent(null, given);
        given[0] = 99;
        event.getPassengerEntityIds()[1] = 99;
        assertArrayEquals(new int[] {3, 1, 4}, event.getPassengerEntityIds());
        assertArrayEquals(new int[0], new EmoteRigSpawnEvent(null, null).getPassengerEntityIds());
    }

    /** An {@link Emotes} whose every abstract method throws, so only defaults answer. */
    private static Emotes defaultsOnly() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            throw new UnsupportedOperationException(method.getName());
        };
        return (Emotes) Proxy.newProxyInstance(
            Emotes.class.getClassLoader(), new Class<?>[] {Emotes.class}, handler);
    }
}
