package ai.resourcepack.engine.api;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Models standing in the world, and the animations they play.
 *
 * <p>In game an animation runs because something triggered it - the model was
 * placed, clicked, or walked up to. This is the other way in: name the
 * animation and it plays, whatever triggers it does or doesn't claim. An
 * animation with no triggers at all is editor-only in game and perfectly
 * playable through here, which is the main reason this exists.
 *
 * <p><b>Main thread only</b>, except the catalogue queries ({@code ids},
 * {@code info}) which read concurrent state and are safe anywhere.
 *
 * <p>Every method tolerates a null argument by answering empty or false, so a
 * model id read out of somebody's config never becomes a stack trace.
 */
public interface Models {

    /**
     * Every model id this server holds a rig for, in no particular order.
     *
     * <p>A model with no rig - one that places as a single still display - is
     * NOT here, because nothing was ever sent about it. That does not stop
     * {@link #itemFor} minting its item: only the client knows what art a pack
     * holds.
     */
    List<String> ids();

    /** What a model is, in one object: whether it animates, and what it can play. */
    Optional<RigInfo> info(String modelId);

    /**
     * Every animation this model has, in the order the editor lists them.
     * Empty for a model this server has never been sent, or one with no
     * animations - a caller can't pass a name it has no way of learning.
     *
     * <p>Names are free text and nothing makes them unique. Where a model has
     * two of the same name, {@link Placement#play} takes the first.
     */
    List<String> animationsOf(String modelId);

    /** Whether this model places as an animated rig rather than one still display. */
    boolean isAnimated(String modelId);

    /**
     * Whether an entity is part of a placed model - its clickable hitbox or
     * any of its part displays. The cheap early-out for an event handler:
     *
     * <pre>{@code
     * @EventHandler(priority = EventPriority.LOWEST)
     * public void onUse(PlayerInteractAtEntityEvent event) {
     *     if (event.getHand() != EquipmentSlot.HAND) return;
     *     if (!rpai.models().isModel(event.getRightClicked())) return;
     *     // ...
     * }
     * }</pre>
     *
     * <p><b>Listen at LOWEST or LOW.</b> This library handles that event at
     * NORMAL and CANCELS it when the model has a right-click trigger, so a
     * handler at NORMAL or later sees a cancelled event for some models and
     * not others depending on how they were set up in the editor. Left click
     * is {@code EntityDamageByEntityEvent}, and that one is cancelled for
     * EVERY rig - punching a model must not break it - so the same rule
     * applies with no exceptions.
     *
     * <p>Equivalent to {@code at(entity).isPresent()}, without building the
     * handle you're about to throw away.
     */
    boolean isModel(Entity entity);

    /**
     * The placement an entity belongs to: either the rig's own hitbox (what a
     * {@code PlayerInteractAtEntityEvent} hands you) or any one of its part
     * displays. Empty for anything else, including a model placed before its
     * rig was known.
     */
    Optional<Placement> at(Entity entity);

    /**
     * Placements whose hitbox is within {@code radius} blocks, nearest first.
     *
     * <p>Only loaded chunks have entities in them, so a rig standing in
     * unloaded terrain isn't here and can't be animated - it will still be
     * carrying whatever it was last told to do when its chunk comes back.
     *
     * <p>There is deliberately no "every placement on the server" call: that
     * means walking every entity in every world, and a cost like that should
     * be something a caller opts into with a radius rather than something the
     * API makes look free. {@link #in(Chunk)} is the bounded way to sweep.
     */
    List<Placement> near(Location centre, double radius);

    /** Placements anchored in one loaded chunk. Empty if the chunk isn't loaded. */
    List<Placement> in(Chunk chunk);

    /**
     * Spawns a model in the world, as though a player had placed it.
     *
     * <p>With a placer in {@link PlaceOptions}, this fires
     * {@link ai.resourcepack.presence.ModelPlaceEvent} first and returns empty
     * if a listener cancels it - a rig is spawned with {@code world.spawn()},
     * which fires none of the events a placement normally does, so that event
     * is the only chance anything on the server gets to refuse.
     *
     * <p><b>With no placer, nothing is fired and nothing can refuse.</b> That
     * event is a {@code PlayerEvent}, and inventing a player to fill it would
     * hand every listener a lie about who built what. A placement your plugin
     * makes on its own behalf is your plugin's decision; a placement somebody
     * asked for should name them.
     *
     * <p>A rig occupies a block space, so the location names a block rather
     * than a point: it is centred in the block containing it, exactly as a
     * player's placement is. The yaw IS taken as given, though, and not
     * snapped to a cardinal the way a click is - so a statue can face any way
     * you like, which a player placing one by hand cannot do.
     *
     * @return the new placement, or empty if the model is unknown, the world
     *         isn't loaded, or a listener cancelled it.
     */
    Optional<Placement> place(Location at, String modelId, PlaceOptions options);

    /**
     * The item that places this model when right-clicked - the same stack the
     * studio panel hands out.
     *
     * <p>Useful for shops, kits and rewards. The encoding (a string
     * {@code custom_model_data}, plus the animation choice and scale) is an
     * implementation detail that this method and the placement listener are
     * the only two things that need to agree on.
     *
     * @return the stack, or empty if this server holds no such model.
     */
    Optional<ItemStack> itemFor(String modelId, ItemOptions options);
}
