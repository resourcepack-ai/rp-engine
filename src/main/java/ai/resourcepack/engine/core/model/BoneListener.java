package ai.resourcepack.engine.core.model;

import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;

/**
 * What happens when somebody hits or clicks one of a model's bones.
 *
 * <p>A sub-hitbox is an {@link org.bukkit.entity.Interaction} riding a part
 * display. It exists so a dragon is hit on the wing you aimed at rather than
 * inside one rectangle around the whole animal — but the wing is not a
 * creature, so what a hit on it does is <strong>forwarded</strong> to whatever
 * the model belongs to.
 *
 * <p><strong>The forwarding is the entire feature and also the entire risk.</strong>
 * A hitbox rides a display which rides the mob, so damaging the mob could
 * arrive back here as damage to its own hitbox and go round for ever. It
 * cannot, because a hitbox is an Interaction and a host never is — the two
 * cases below are answered by different classes and nothing dispatches to
 * itself. Worth stating rather than trusting: the loop is the failure this
 * class is one line away from at all times.
 */
public final class BoneListener implements Listener {

    private final BoneParts bones;
    private final Seats seats;

    public BoneListener(BoneParts bones, Seats seats) {
        this.bones = bones;
        this.seats = seats;
    }

    /**
     * A hit on a bone is a hit on its owner.
     *
     * <p>At {@link EventPriority#LOW} so a protection plugin sees the damage
     * against the real mob rather than against an Interaction entity it has
     * never heard of and will not think to guard.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Optional<ItemDisplay> part = bones.ownerOf(event.getEntity());
        if (part.isEmpty()) {
            return;
        }
        // Ours either way: the click landed on a bone, so the vanilla answer
        // (nothing, since an Interaction has no health) is never the right one.
        event.setCancelled(true);

        Entity host = part.get().getVehicle();
        if (!(host instanceof LivingEntity) || host.equals(event.getDamager())) {
            return;
        }
        // The mob takes it, from whoever swung. Its own resistances, its own
        // death, its own loot — a bone is a place to aim at and nothing else.
        ((LivingEntity) host).damage(event.getDamage(), event.getDamager());
    }

    /** Right-clicking a seat bone sits you on it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !bones.isSeat(event.getRightClicked())) {
            return;
        }
        Optional<ItemDisplay> part = bones.ownerOf(event.getRightClicked());
        if (part.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!BoneParts.maySit(player)) {
            return;
        }
        // The bone's own position, which the animation is already moving —
        // so a seat on a walking mount goes with it, and Seats does not have
        // to know a rig exists.
        seats.sit(player, event.getRightClicked().getLocation());
    }
}
