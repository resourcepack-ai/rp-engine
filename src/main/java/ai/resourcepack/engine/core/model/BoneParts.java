package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.BoneBehaviour;
import ai.resourcepack.engine.core.Host;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The bones that put something in the world besides a picture.
 *
 * <p>A hitbox you can hit on the wing you aimed at, and a place to sit. Both
 * are ordinary entities parented to the part display they belong to, which is
 * what makes them follow the animation for free — the display is already being
 * moved every couple of ticks, and a passenger goes where its vehicle goes.
 *
 * <p><strong>They forward rather than decide.</strong> A sub-hitbox that took
 * damage itself would be a second health bar on a mob that already has one, so
 * a hit on one is a hit on the thing the model belongs to, and the model
 * belongs to whoever spawned it. That is the whole of what "sub-hitbox" means
 * here and it is deliberately less than ModelEngine's, which can weight damage
 * per bone; weighting is a rule about somebody's game, and this is the seam it
 * would be built on.
 *
 * <p>Nothing here is saved. A rig's part displays are chunk-saved and these
 * hang off them, so they are rebuilt when the parts are tracked rather than
 * persisted — which also means a rig whose bones were renamed gets the right
 * ones back on the next reload instead of keeping the old set for ever.
 */
final class BoneParts {

    /** Marks an entity as ours, and says which display it belongs to. */
    private final NamespacedKey ownerKey;

    /** What the bone does, so a click knows whether to sit somebody down. */
    private final NamespacedKey roleKey;

    private final Host host;

    BoneParts(Host host) {
        this.host = host;
        this.ownerKey = host.key("bone-owner");
        this.roleKey = host.key("bone-role");
    }

    /**
     * Builds whatever {@code part} asks for, at the display's own position.
     *
     * <p>Sized off the bone rather than off the model: a wing hitbox that was
     * the size of the dragon would defeat the point of having one.
     */
    void attach(ItemDisplay display, RigStore.Part part) {
        BoneBehaviour behaviour = RigAnimator.behaviourOf(part);
        // Always cleared first: this runs whenever a part is tracked, which
        // includes every chunk load, and a hitbox spawned twice is two.
        detach(display);
        if (behaviour == BoneBehaviour.NONE) {
            return;
        }
        if (behaviour.isHitbox() || behaviour == BoneBehaviour.SEAT || behaviour == BoneBehaviour.DRIVER) {
            // A seat gets one too, and has to: sitting is a right-click, and a
            // display entity cannot be clicked at all. What differs is only
            // what a click MEANS, which is the role written on it.
            spawnHitbox(display, part, behaviour);
        }
    }

    private void spawnHitbox(ItemDisplay display, RigStore.Part part, BoneBehaviour behaviour) {
        float size = Math.max(0.25f, sizeOf(part));
        Location at = display.getLocation();
        Interaction hitbox = display.getWorld().spawn(at, Interaction.class, box -> {
            box.setInteractionWidth(size);
            box.setInteractionHeight(size);
            box.setResponsive(true);
            box.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
                    display.getUniqueId().toString());
            box.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, behaviour.name());
        });
        // A passenger of the display, so it goes wherever the animation puts
        // the bone with nothing per-tick of ours involved.
        display.addPassenger(hitbox);
    }

    /**
     * How big a bone's hitbox is: the bone's own widest side, measured when
     * the rig was built.
     *
     * <p>Measured there rather than guessed here because the geometry is in
     * the pack and this is the server. A manifest older than the measurement
     * has none, and half a block is the fallback — wrong, but present, which
     * beats a wing nobody can hit.
     */
    private static float sizeOf(RigStore.Part part) {
        return part == null || part.size <= 0 ? 0.5f : part.size;
    }

    /** Every sub-entity hanging off a part display. */
    List<Entity> of(ItemDisplay display) {
        List<Entity> mine = new ArrayList<>();
        for (Entity passenger : display.getPassengers()) {
            if (passenger.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                mine.add(passenger);
            }
        }
        return mine;
    }

    /** Takes them off again. True if there were any. */
    boolean detach(ItemDisplay display) {
        List<Entity> mine = of(display);
        mine.forEach(Entity::remove);
        return !mine.isEmpty();
    }

    /** The part display a sub-hitbox belongs to, or empty if it is not one of ours. */
    Optional<ItemDisplay> ownerOf(Entity hitbox) {
        String owner = hitbox == null
                ? null
                : hitbox.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (owner == null) {
            return Optional.empty();
        }
        try {
            Entity display = host.plugin().getServer().getEntity(UUID.fromString(owner));
            return display instanceof ItemDisplay ? Optional.of((ItemDisplay) display) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Whether this hitbox is a bone somebody can sit on. */
    boolean isSeat(Entity hitbox) {
        String role = hitbox == null
                ? null
                : hitbox.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        return BoneBehaviour.SEAT.name().equals(role) || BoneBehaviour.DRIVER.name().equals(role);
    }

    /** Whether {@code who} may sit here at all. Kept as its own question on purpose. */
    static boolean maySit(Player who) {
        return who != null && !who.isInsideVehicle();
    }
}
