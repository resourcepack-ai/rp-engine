package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.BoneBehaviour;
import ai.resourcepack.engine.core.Host;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * What a hit here is multiplied by.
     *
     * <p>Written on the hitbox rather than looked up when somebody swings:
     * the listener has the entity that was hit and nothing else, and reaching
     * back through the display to the part to the rig on every blow would be
     * three lookups in the damage path for a number that never changes.
     */
    private final NamespacedKey damageKey;

    BoneParts(Host host) {
        this.ownerKey = host.key("bone-owner");
        this.roleKey = host.key("bone-role");
        this.damageKey = host.key("bone-damage");
    }

    /**
     * Builds whatever {@code part} asks for, at the display's own position.
     *
     * <p>Sized off the bone rather than off the model: a wing hitbox that was
     * the size of the dragon would defeat the point of having one.
     */
    void attach(ItemDisplay display, RigStore.Part part) {
        BoneBehaviour behaviour = HeadLook.behaviourOf(part);
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
        } else if (behaviour == BoneBehaviour.NAMETAG) {
            spawnNametag(display);
        }
    }

    /**
     * A name floating at a bone rather than at the entity's own head.
     *
     * <p>Which is the whole point of it: vanilla puts a name a fixed distance
     * above the hitbox, and on a model three blocks tall and five wide that is
     * somewhere inside its knee.
     *
     * <p>The text is the HOST's custom name, read once here and refreshed by
     * nothing. A name that changes is rare, and a per-tick poll of every rig on
     * the server to catch it is not a trade worth making — a reload or a
     * rebind picks it up.
     */
    private void spawnNametag(ItemDisplay display) {
        // "wearer", not "host": this class already has a Host field, and a
        // local shadowing it is the exact mistake that cost this project a
        // compile once already.
        Entity wearer = display.getVehicle();
        String name = wearer == null ? null : wearer.getCustomName();
        if (name == null || name.isEmpty()) {
            // Nothing to show. A blank tag is an invisible entity that costs a
            // packet per player per tick for ever.
            return;
        }
        TextDisplay tag = display.getWorld().spawn(display.getLocation(), TextDisplay.class, text -> {
            text.setText(name);
            text.setBillboard(Display.Billboard.CENTER);
            text.setDefaultBackground(false);
            text.setSeeThrough(false);
            text.setPersistent(false);
            text.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
                    display.getUniqueId().toString());
        });
        display.addPassenger(tag);
        // And vanilla's own is turned off, or there are two names: one at the
        // bone and one in the model's knee.
        if (wearer != null) {
            wearer.setCustomNameVisible(false);
        }
    }

    private void spawnHitbox(ItemDisplay display, RigStore.Part part, BoneBehaviour behaviour) {
        float size = Math.max(0.25f, sizeOf(part));
        Location at = display.getLocation();
        Interaction hitbox = display.getWorld().spawn(at, Interaction.class, box -> {
            box.setInteractionWidth(size);
            box.setInteractionHeight(size);
            box.setResponsive(true);
            // Never saved with the chunk. These are derived from the rig and
            // rebuilt whenever a part is tracked, so persisting them risks the
            // one failure that accumulates silently: a chunk load where the
            // old passengers are not visible yet spawns a second set, and the
            // first are invisible entities nobody can find.
            box.setPersistent(false);
            box.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
                    display.getUniqueId().toString());
            box.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, behaviour.name());
            if (part != null && part.damage > 0 && part.damage != 1) {
                box.getPersistentDataContainer().set(damageKey, PersistentDataType.DOUBLE, part.damage);
            }
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
        boolean hadNametag = mine.stream().anyMatch(TextDisplay.class::isInstance);
        mine.forEach(Entity::remove);
        if (hadNametag) {
            // The host's own name was hidden so there would not be two. It has
            // to come back, or unbinding a model leaves a nameless mob and
            // nothing anywhere says why.
            Entity wearer = display.getVehicle();
            if (wearer != null && wearer.getCustomName() != null) {
                wearer.setCustomNameVisible(true);
            }
        }
        return !mine.isEmpty();
    }

    /**
     * The part display a sub-hitbox belongs to, or empty if it is not one of
     * ours.
     *
     * <p>Its VEHICLE, because that is what it is: these are spawned as
     * passengers of the display so they follow the animation. The id written
     * on them says they are ours; it is not how they are found. Looking one up
     * by uuid would be a server-wide entity search in the damage path, for an
     * answer already in hand.
     */
    Optional<ItemDisplay> ownerOf(Entity hitbox) {
        if (hitbox == null
                || !hitbox.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            return Optional.empty();
        }
        Entity vehicle = hitbox.getVehicle();
        return vehicle instanceof ItemDisplay ? Optional.of((ItemDisplay) vehicle) : Optional.empty();
    }

    /**
     * What a hit on this one is worth. 1 unless the pack said otherwise.
     */
    double damageOf(Entity hitbox) {
        Double multiplier = hitbox == null
                ? null
                : hitbox.getPersistentDataContainer().get(damageKey, PersistentDataType.DOUBLE);
        return multiplier == null || multiplier <= 0 ? 1 : multiplier;
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
