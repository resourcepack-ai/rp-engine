package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.core.Host;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Puts a rig's part displays into the world.
 *
 * <p>There are two ways a model gets placed — a studio item somebody was
 * pushed, and an item out of a server owner's own content folder — and they
 * differ in exactly one thing: what a part renders as. Studio's parts are
 * paper wearing a {@code custom_model_data} string; an authored part is an
 * ordinary item model with an id derived from the piece's own. Everything
 * else about a placed rig is identical, so everything else is here.
 *
 * <p>Written as its own class rather than shared through one of the two
 * listeners, because a listener holding another listener in order to borrow a
 * method from it is how the pair ends up impossible to change independently.
 */
final class RigSpawn {

    private final NamespacedKey modelKey;
    private final NamespacedKey partKey;
    private final NamespacedKey yawKey;
    private final NamespacedKey scaleKey;
    private final NamespacedKey animationKey;
    private final RigAnimator animator;

    RigSpawn(Host host, RigAnimator animator) {
        this.modelKey = host.key("model-id");
        this.partKey = host.key("part-index");
        this.yawKey = host.key("rig-yaw");
        this.scaleKey = host.key(RigAnimator.SCALE_KEY);
        this.animationKey = host.key(RigAnimator.ANIMATION_CHOICE_KEY);
        this.animator = animator;
    }

    /**
     * Spawns one display per part and returns their entity ids, in part
     * order, for the hitbox to remember.
     *
     * @param partItem what a part renders as — the one thing the two callers
     *                 disagree about
     */
    List<String> parts(Block target, String modelId, RigStore.Rig rig, float yaw,
                       String animation, float scale, Function<RigStore.Part, ItemStack> partItem) {
        World world = target.getWorld();
        // A display renders its model centred on the entity position, so
        // block-centre puts a 16px cube exactly in the block space.
        Location centre = target.getLocation().add(0.5, 0.5, 0.5);
        List<String> ids = new ArrayList<>(rig.parts.size());

        for (int p = 0; p < rig.parts.size(); p++) {
            RigStore.Part part = rig.parts.get(p);
            if (part == null || part.item == null) {
                continue;
            }
            ItemStack stack = partItem.apply(part);
            if (stack == null) {
                continue;
            }
            final int index = p;
            boolean moving = RigAnimator.hasAnimationProgram(part);
            Location where = centre.clone();
            // A still part keeps its yaw on the entity; a moving one has the
            // placement yaw baked into every pose instead, so its entity yaw
            // must stay at zero or the rotation is applied twice.
            if (!moving) {
                where.setYaw(yaw);
            }

            ItemDisplay display = world.spawn(where, ItemDisplay.class, d -> {
                d.setItemStack(stack);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, modelId);
                d.getPersistentDataContainer().set(partKey, PersistentDataType.INTEGER, index);
                // Every part carries the scale, moving or not: a still part of
                // an animated rig has to grow by exactly as much as the parts
                // around it, or the model comes apart at the joints.
                if (scale != 1f) {
                    d.getPersistentDataContainer().set(scaleKey, PersistentDataType.FLOAT, scale);
                    if (!moving) {
                        d.setTransformation(RigAnimator.scaledTransformation(scale));
                    }
                }
                if (moving) {
                    d.getPersistentDataContainer().set(yawKey, PersistentDataType.FLOAT, yaw);
                    // The part displays carry the choice too: the animator
                    // resolves a resting loop per display, with no hitbox in
                    // hand.
                    if (animation != null) {
                        d.getPersistentDataContainer().set(animationKey, PersistentDataType.STRING, animation);
                    }
                }
            });

            if (moving) {
                animator.track(display);
                // Immediately, so a freshly placed rig never flashes on screen
                // in its unrotated rest pose before the first tick.
                animator.poseNow(display);
            }
            ids.add(display.getUniqueId().toString());
        }
        return ids;
    }
}
