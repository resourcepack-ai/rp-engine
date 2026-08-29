package ai.resourcepack.engine.core.item;

import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why {@link ItemListener#onUse} is not {@code ignoreCancelled}.
 *
 * <p>Bukkit builds a {@code PlayerInteractEvent} with
 * {@code useInteractedBlock = DENY} whenever there is no block, and
 * {@code isCancelled()} is defined as that field being DENY. So <strong>every
 * right-click into the air arrives already cancelled</strong>, before any
 * plugin has said anything about it.
 *
 * <p>This is a fact about the API rather than about our code, which is exactly
 * why it is worth a test: it cost a release in which every custom item worked
 * against a wall and did nothing facing the sky, and nothing in our own source
 * would ever show why.
 */
class AirClickTest {

    private static PlayerInteractEvent click(Action action) {
        // A null player is fine: nothing below reads it. Bukkit's own state is
        // what is under test.
        return new PlayerInteractEvent(null, action, null, null, BlockFace.SELF);
    }

    @Test
    void bukkitCallsAnAirClickCancelledBeforeAnybodyTouchesIt() {
        assertTrue(click(Action.RIGHT_CLICK_AIR).isCancelled());
        assertTrue(click(Action.LEFT_CLICK_AIR).isCancelled());
    }

    @Test
    void andTheItemItselfIsNotDenied() {
        // The field a protection plugin actually sets, and therefore the one
        // the listener checks instead.
        assertFalse(click(Action.RIGHT_CLICK_AIR).useItemInHand()
                == org.bukkit.event.Event.Result.DENY);
    }
}
