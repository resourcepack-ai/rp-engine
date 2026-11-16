package ai.resourcepack.engine.core.model;

import org.bukkit.entity.Interaction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A carried rig's anchor is never treated as a placed model's hitbox. */
class CarriedAnchorTest {

    private static Interaction box(float width, float height) {
        return (Interaction) Proxy.newProxyInstance(Interaction.class.getClassLoader(), new Class<?>[]{Interaction.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInteractionWidth" -> width;
                    case "getInteractionHeight" -> height;
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    @Test
    void theZeroSizedAnchorIsRecognised() {
        assertTrue(RigPlacementListener.isCarriedAnchor(box(0f, 0f)));
        assertTrue(RigPlacementListener.isCarriedAnchor(box(1f, 0f)));
    }

    @Test
    void aPlacedHitboxIsNot() {
        assertFalse(RigPlacementListener.isCarriedAnchor(box(1f, 1f)));
        // The smallest a placed rig is drawn at still has a box.
        assertFalse(RigPlacementListener.isCarriedAnchor(box(0.25f, 0.25f)));
    }
}
