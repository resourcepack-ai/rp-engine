package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.api.event.ModelBreakEvent;
import ai.resourcepack.engine.api.event.ModelInteractEvent;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import ai.resourcepack.engine.core.Host;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes placed model model, breakable, and able to survive a restart.
 *
 * <p>A placed piece is two entities. An {@link ItemDisplay} is what you see; an
 * {@link Interaction} is what you can hit, because a display entity cannot be
 * clicked or collided with at all. Both are tagged in persistent data, so a
 * piece is an ordinary chunk-saved entity and needs no file of its own and no
 * loading step — the world already remembers where everything is.
 *
 * <p>Solid placed model also gets a barrier block at its anchor, which is the only
 * way a display entity can stop anybody walking through it.
 *
 * <p>The key is namespaced by the plugin, which is the concrete reason this
 * plugin can never be renamed: every piece standing in somebody's world is
 * keyed to it.
 */
public final class ModelPlacementListener implements Listener {

    private final Plugin plugin;
    private final Items items;
    private final Seats seats;
    private final NamespacedKey idKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey solidKey;
    /**
     * Whether this placement put a light block down.
     *
     * <p>Recorded rather than inferred from the block being a light, for the
     * same reason the barrier is: a display entity does not collide, so a
     * player can put their OWN light block in the space a model occupies, and
     * breaking the model would then delete it.
     */
    private final NamespacedKey lightKey;

    /**
     * The rig half, which a piece uses only if its model has keyframes in it.
     *
     * <p>These are the animator's own keys rather than this listener's,
     * because they are read by the animator: a placed rig is found, posed and
     * triggered by exactly the same code whether it came out of a content
     * folder or off a studio push. That is the whole point of building it this
     * way, and it is why an authored piece is not a second animation system.
     */
    private final RigStore rigs;
    private final RigAnimator animator;
    private final RigSpawn spawns;
    private final NamespacedKey rigModelKey;
    private final NamespacedKey displaysKey;
    private final NamespacedKey partKey;

    /** The placement's heading, on the hitbox — see the write in {@code place}. */
    private final NamespacedKey placedYawKey;

    private volatile Map<ContentId, ModelInfo> model = Map.of();

    public ModelPlacementListener(Plugin plugin, Items items, Seats seats,
                                  Host host, RigStore rigs, RigAnimator animator) {
        this.plugin = plugin;
        this.items = items;
        this.seats = seats;
        this.idKey = new NamespacedKey(plugin, "model");
        this.displayKey = new NamespacedKey(plugin, "model-display");
        this.solidKey = new NamespacedKey(plugin, "model-solid");
        this.lightKey = new NamespacedKey(plugin, "model-light");
        this.stateKey = new NamespacedKey(plugin, "model-state");
        this.placedAtKey = new NamespacedKey(plugin, "model-placed");
        this.rigs = rigs;
        this.animator = animator;
        this.spawns = new RigSpawn(host, animator);
        this.rigModelKey = host.key("model-id");
        this.displaysKey = host.key("display-uuids");
        this.partKey = host.key("part-index");
        this.placedYawKey = host.key("model-yaw");
    }

    /** Who to tell when a piece comes or goes, for players the displays do not reach. */
    private ai.resourcepack.engine.core.distribution.BedrockSupport bedrock =
            ai.resourcepack.engine.core.distribution.BedrockSupport.NONE;

    /** What a piece's own actions do when it is put down, clicked or broken. */
    private ai.resourcepack.engine.core.item.ActionRunner actions;

    public void actions(ai.resourcepack.engine.core.item.ActionRunner actions) {
        this.actions = actions;
    }

    /**
     * Runs the piece's actions for {@code trigger}.
     *
     * <p>With no stack: the click is on the piece, not on whatever the player
     * is holding, so a {@code take} step must not eat their held item.
     *
     * @return whether a {@code cancel} step asked for the click's own effect to be stopped
     */
    private boolean act(Player player, ContentId id, ai.resourcepack.engine.api.ItemAction.Trigger trigger) {
        return actions != null && player != null && actions.run(player, modelItem(id), trigger, null);
    }

    /** Opens a piece that holds items. Null until wired, and then a piece is just a piece. */
    private ai.resourcepack.engine.core.storage.Storages storages;

    public void storages(ai.resourcepack.engine.core.storage.Storages storages) {
        this.storages = storages;
    }

    /**
     * Which of its {@code states:} a piece is in, on its hitbox, so a lamp left
     * on is still on after a restart. Absent is 0, the piece as defined.
     */
    private final NamespacedKey stateKey;

    /** A piece's pending {@code reset-after}, by hitbox. One per piece, replaced on every click. */
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> resets = new java.util.HashMap<>();

    /** How long a door takes to swing, in ticks. Short: it is a click, not a cutscene. */
    private static final int SWING_TICKS = 5;

    /** This server's own sounds, for a state's sound named by a content id. */
    private ai.resourcepack.engine.api.Sounds sounds;

    public void sounds(ai.resourcepack.engine.api.Sounds sounds) {
        this.sounds = sounds;
    }

    /** Plays discs in a piece that is a jukebox. Null until wired. */
    private PieceJukebox jukeboxes;

    /** How this server tells a music disc from anything else; see {@link Discs}. */
    public void discs(Discs discs) {
        this.jukeboxes = discs == null ? null : new PieceJukebox(plugin, discs);
    }

    public void bedrock(ai.resourcepack.engine.core.distribution.BedrockSupport bedrock) {
        this.bedrock = bedrock == null ? ai.resourcepack.engine.core.distribution.BedrockSupport.NONE : bedrock;
    }

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, ModelInfo> loaded) {
        this.model = loaded == null ? Map.of() : Map.copyOf(loaded);
    }

    /**
     * Whether a vehicle is stopped by a placement of {@code id}.
     *
     * <p>Takes the raw string a placed hitbox carries rather than a
     * {@link ContentId}, because the caller is holding persistent data and the
     * ids in it come from two sources — a content folder writes
     * {@code mypack:chair}, a Studio push writes a bare slug. An id this
     * catalogue has never heard of is somebody else's and answers yes, which is
     * what leaves the pushed half free to say no about its own.
     *
     * @see ai.resourcepack.engine.api.ModelInfo#vehicleCollision()
     */
    public boolean stopsVehicles(String id) {
        return byRawId(id).map(ModelInfo::vehicleCollision).orElse(Boolean.TRUE);
    }

    /**
     * What the piece with this id is shaped like, measured off its model.
     *
     * <p>Empty for anything this catalogue has never heard of, which is every
     * Studio push — those are read out of the pushed pack instead, by
     * {@link StudioModelShapes}.
     */
    public ai.resourcepack.engine.api.ModelShape shapeOf(String id) {
        return byRawId(id)
                .map(ModelInfo::shape)
                .orElse(ai.resourcepack.engine.api.ModelShape.NONE);
    }

    /** The size its own definition asks for, or 1 for anything not ours. */
    public float scaleOf(String id) {
        return byRawId(id).map(ModelInfo::scale).orElse(1f);
    }

    private Optional<ModelInfo> byRawId(String id) {
        ContentId parsed = id == null ? null : ContentId.parse(id).orElse(null);
        return parsed == null ? Optional.empty() : Optional.ofNullable(model.get(parsed));
    }

    private Optional<ModelInfo> byItem(ContentId item) {
        for (ModelInfo one : model.values()) {
            if (one.item().equals(item)) {
                return Optional.of(one);
            }
        }
        return Optional.empty();
    }

    // ---- placing -------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack held = event.getItem();
        Optional<ContentId> id = items.idOf(held);
        if (id.isEmpty()) {
            return;
        }
        Optional<ModelInfo> found = byItem(id.get());
        if (found.isEmpty()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        // A chest keeps its vanilla click unless the player sneaks, which is
        // the same rule as placing an ordinary block. Anything else and
        // placed model would make containers unopenable while it is in hand.
        if (clicked.getType().isInteractable() && !event.getPlayer().isSneaking()) {
            return;
        }

        event.setCancelled(true);

        // A torch on a wall, a chandelier under a ceiling, a chair on neither.
        // Refused here rather than placed sideways: a lamp stuck to the
        // underside of a floor is somebody's build ruined, not a preference.
        if (!found.get().surface().accepts(event.getBlockFace())) {
            return;
        }

        Block target = clicked.getRelative(event.getBlockFace());
        if (!target.getType().isAir() || findAt(target) != null) {
            return;
        }

        Player player = event.getPlayer();
        ModelInfo info = found.get();

        // Everything above is "can this physically go here". Whether it is
        // ALLOWED to is a rule about somebody's server, which we cannot see.
        ModelPlaceEvent ask = new ModelPlaceEvent(player, info.id(), target);
        Bukkit.getPluginManager().callEvent(ask);
        if (ask.isCancelled()) {
            return;
        }

        place(target, info, yawFor(player, info), held);

        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        player.swingMainHand();
        act(player, info.id(), ai.resourcepack.engine.api.ItemAction.Trigger.PLACE);
    }

    /** Snaps the player's yaw the way this piece asked to be faced. */
    private static float yawFor(Player player, ModelInfo info) {
        float yaw = player.getLocation().getYaw();
        switch (info.facing()) {
            case CARDINAL:
                return Math.round(yaw / 90f) * 90f;
            case DIAGONAL:
                return Math.round(yaw / 45f) * 45f;
            case FIXED:
                return 0f;
            default:
                return yaw;
        }
    }

    /** Puts a piece into a block space. Main thread only. */
    public Interaction place(Block target, ModelInfo info, float yaw, ItemStack source) {
        World world = target.getWorld();
        ItemStack shown = source != null && source.getType() != Material.AIR
                ? asOne(source)
                : items.create(info.item()).orElse(null);
        // A shulker-style piece put down from an item that was carrying its
        // contents: the bytes move onto the hitbox as they are, and the stack
        // the display shows is stripped of them. Left on the display, they
        // would come back out on the next break as a second copy of everything.
        byte[] carried = storages == null ? null : storages.carried(shown);
        if (carried != null) {
            storages.withoutContents(shown);
        }

        // An animated piece is several displays the server retimes rather than
        // one still one. Everything below — the hitbox, the barrier, the
        // persistent data — is the same either way, which is why this is a
        // branch over what to spawn rather than a second place().
        RigStore.Rig rig = rigs == null ? null : rigs.get(info.id().toString());
        List<String> partIds = rig != null && rig.parts != null && !rig.parts.isEmpty()
                ? spawns.parts(target, info.id().toString(), rig, yaw, null, info.scale(),
                                part -> partStack(info, part))
                        .stream().map(display -> display.getUniqueId().toString()).toList()
                : null;

        // Block centre, because an item display renders its model centred on
        // the entity position: a model built from y=0 upward then sits exactly
        // on the block floor.
        Location centre = target.getLocation().add(0.5, 0.5, 0.5);
        centre.setYaw(yaw);
        ItemDisplay display = partIds != null ? null : world.spawn(centre, ItemDisplay.class, d -> {
            d.setItemStack(shown);
            // NONE, and this is load-bearing. Every other transform applies the
            // model's own `display` block, and the vanilla block/block parent
            // that generated models inherit sets `fixed` to scale 0.5 with a
            // translation — so a two-block statue renders one block tall and
            // slightly off the ground. NONE applies nothing, leaving 16 model
            // units to the block, which is the only scale a placed model can
            // honestly be.
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setRotation(yaw, 0f);
            if (info.scale() != 1f) {
                Transformation transformation = d.getTransformation();
                transformation.getScale().set(info.scale());
                d.setTransformation(transformation);
            }
            // Culled otherwise: a large piece disappears when its anchor block
            // leaves the frustum, which reads as flickering model.
            d.setViewRange(1.5f);
            d.setDisplayWidth(Math.max(1f, info.width() * info.scale()));
            d.setDisplayHeight(Math.max(1f, info.height() * info.scale()));
            d.setBillboard(Display.Billboard.FIXED);
        });

        // An Interaction anchors at its feet rather than its centre, so this
        // one sits on the block floor and grows upward.
        Location base = target.getLocation().add(0.5, 0.0, 0.5);
        Interaction hitbox = world.spawn(base, Interaction.class, i -> {
            i.setInteractionWidth(info.width() * info.scale());
            i.setInteractionHeight(info.height() * info.scale());
            i.setResponsive(true);
            i.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, info.id().toString());
            // On the HITBOX as well as on the parts, for the reason the studio
            // placement writes it: vehicle collision turns the model's own
            // boxes by this, and the hitbox is the only entity it looks at.
            i.getPersistentDataContainer().set(placedYawKey, PersistentDataType.FLOAT, yaw);
            i.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING,
                    partIds != null ? String.join(",", partIds) : display.getUniqueId().toString());
            if (partIds != null) {
                // What the animator looks for. The id it wants is the model's,
                // and the list is the same list under its own name — written
                // twice rather than sharing a key, because this listener's
                // break path and the animator's tick both have to keep working
                // if the other one changes.
                i.getPersistentDataContainer().set(rigModelKey, PersistentDataType.STRING, info.id().toString());
                i.getPersistentDataContainer().set(displaysKey, PersistentDataType.STRING,
                        String.join(",", partIds));
            }
            if (carried != null && info.storage().map(one -> one.type().keepsContents()).orElse(false)) {
                i.getPersistentDataContainer().set(storages.contentsKey(), PersistentDataType.BYTE_ARRAY, carried);
            }
            // When it went down, on every piece and not only the ones that
            // grow: a pack that gives a piece `grow:` later then counts from
            // the real placement. The game's tick count rather than its clock,
            // because /time set and a frozen daylight cycle both move or stop
            // the clock, and a crop on a server with the sun turned off would
            // never grow.
            i.getPersistentDataContainer().set(placedAtKey, PersistentDataType.LONG, world.getGameTime());
        });
        if (growth != null && info.grow().isPresent()) {
            growth.track(hitbox);
        }

        // Before the place trigger, so its animation reaches the Bedrock copy.
        bedrock.rigPlaced(hitbox, info.id().toString());
        if (partIds != null) {
            animator.track(hitbox);
            animator.trigger(hitbox, RigAnimations.TRIGGER_PLACE, null);
        }

        anchor(hitbox, info.solid(), info.light());
        return hitbox;
    }

    /**
     * Makes the block a piece stands in what it should be: a barrier, a light
     * of some level, or nothing of ours.
     *
     * <p>One method for placing a piece and for every state it is clicked
     * into, because a lamp switched on after it was placed has to get its light
     * the same way one placed lit does, and lose it the same way when it is
     * switched off.
     *
     * <p><strong>Only ever a block WE put there, or air.</strong> A display
     * entity does not collide, so a player can put their own light or block in
     * the space a model occupies, and a state change that wrote over it would
     * delete it. So what is ours is recorded on the hitbox (the solid and light
     * keys), a recorded block that is not there any more is forgotten rather
     * than trusted, and anything else standing there is left alone — at the
     * cost of the piece not being solid or lit in that state.
     *
     * <p>A barrier and a light would have to be the same block, so a solid
     * state gives no light: the barrier wins.
     */
    private void anchor(Interaction hitbox, boolean solid, int light) {
        org.bukkit.persistence.PersistentDataContainer data = hitbox.getPersistentDataContainer();
        Block block = hitbox.getLocation().getBlock();
        boolean ourBarrier = data.has(solidKey, PersistentDataType.BYTE) && block.getType() == Material.BARRIER;
        boolean ourLight = data.has(lightKey, PersistentDataType.BYTE) && block.getType() == Material.LIGHT;
        if (!ourBarrier) {
            data.remove(solidKey);
        }
        if (!ourLight) {
            data.remove(lightKey);
        }
        boolean free = block.getType().isAir() || ourBarrier || ourLight;

        if (solid) {
            if (ourBarrier || !free) {
                return;
            }
            // A display entity has no collision whatsoever. This is the only
            // way to make a table something you cannot walk through.
            data.remove(lightKey);
            block.setType(Material.BARRIER, false);
            data.set(solidKey, PersistentDataType.BYTE, (byte) 1);
        } else if (light > 0) {
            if (!free) {
                return;
            }
            // A display entity emits nothing either, so a lamp needs a real
            // light block standing in its anchor.
            data.remove(solidKey);
            if (!ourLight) {
                block.setType(Material.LIGHT, false);
            }
            org.bukkit.block.data.BlockData blockData = block.getBlockData();
            if (blockData instanceof org.bukkit.block.data.Levelled) {
                ((org.bukkit.block.data.Levelled) blockData).setLevel(light);
                block.setBlockData(blockData, false);
            }
            data.set(lightKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            if (ourBarrier || ourLight) {
                block.setType(Material.AIR, false);
            }
            data.remove(solidKey);
            data.remove(lightKey);
        }
    }

    /**
     * What one part of an authored piece renders as.
     *
     * <p>The piece's own material wearing the part's item model, which the
     * pack builder wrote beside the whole one. Studio's parts are paper with a
     * {@code custom_model_data} string instead — it has no plugin to define an
     * item and has to borrow a vanilla one. We are the plugin, so we do not.
     */
    private ItemStack partStack(ModelInfo info, RigStore.Part part) {
        ItemStack stack = items.create(info.item()).orElse(null);
        if (stack == null) {
            return null;
        }
        stack.setAmount(1);
        // Through the service rather than setItemModel directly, because how
        // a model is addressed depends on the server's version — see
        // Items.wearModel. A part id that is not a content id is left alone,
        // which is the same tolerance the old code had for a missing colon.
        ContentId.parse(part.item).ifPresent(model -> items.wearModel(stack, model));
        return stack;
    }

    private static ItemStack asOne(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        return one;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Interaction)) {
            return;
        }
        Interaction hitbox = (Interaction) event.getRightClicked();
        Optional<ContentId> id = idOf(hitbox);
        if (id.isEmpty()) {
            // Somebody else's Interaction entity. Not ours to speak for.
            return;
        }
        ModelInteractEvent clicked = new ModelInteractEvent(event.getPlayer(), id.get(), hitbox);
        Bukkit.getPluginManager().callEvent(clicked);
        if (clicked.isCancelled()) {
            return;
        }

        // The pack's own click actions come first, and a cancel in them is
        // the author saying this piece is a button rather than a chair.
        if (act(event.getPlayer(), id.get(), ai.resourcepack.engine.api.ItemAction.Trigger.INTERACT)) {
            event.setCancelled(true);
            return;
        }

        ModelInfo piece = model.get(id.get());
        Player clicker = event.getPlayer();
        // Sneaking on a piece that is also a seat sits; everything a plain
        // click does below gives way to that. A cabinet you keep sitting in
        // and a chair you can never open are both broken, and the sneak is how
        // a player says which one they meant.
        boolean sitInstead = piece != null && piece.sittable() && clicker.isSneaking();

        // A container takes a plain click, and nothing after it runs: a
        // storage piece that also seated people on the same click would open
        // and sit at once.
        if (piece != null && piece.storage().isPresent() && storages != null && !sitInstead) {
            event.setCancelled(true);
            openStorage(clicker, hitbox, piece);
            return;
        }

        // A disc in hand goes in; a disc already in comes out. Anything else
        // is not a jukebox click and carries on down the chain.
        if (piece != null && piece.jukebox().isPresent() && jukeboxes != null && !sitInstead
                && jukeboxes.click(clicker, hitbox, piece.jukebox().get())) {
            event.setCancelled(true);
            refreshLook(hitbox, piece);
            return;
        }

        // The next state: the lamp goes on, the door swings. An animated
        // piece's own right-click animation plays as well, because that is
        // how a rig door shows itself opening — a rig cannot be turned or
        // re-modelled whole the way a still piece can.
        if (piece != null && !piece.states().isEmpty() && !sitInstead) {
            event.setCancelled(true);
            int next = piece.nextState(stateOf(hitbox));
            enterState(hitbox, piece, next);
            scheduleReset(hitbox, piece, next);
            if (animator != null && animator.hasTrigger(id.get().toString(), RigAnimations.TRIGGER_RIGHT_CLICK)) {
                animator.trigger(hitbox, RigAnimations.TRIGGER_RIGHT_CLICK, clicker);
            }
            return;
        }

        // A right-click animation gets the click before sitting does. An
        // author who gave a piece both asked for a chair that does something
        // when you use it, and a seat is what SHIFT-clicking a seat still is.
        if (animator != null && animator.hasTrigger(id.get().toString(), RigAnimations.TRIGGER_RIGHT_CLICK)
                && !event.getPlayer().isSneaking()) {
            event.setCancelled(true);
            animator.trigger(hitbox, RigAnimations.TRIGGER_RIGHT_CLICK, event.getPlayer());
            return;
        }

        // Sitting is the one behaviour the engine does provide, because the
        // model already said it is a seat and there is exactly one sensible
        // thing to do about that. Everything else a click might mean is still
        // a decision about somebody's server, which is what the event is for —
        // and a listener that cancels gets its way before this runs.
        if (piece != null && piece.sittable()) {
            seats.sit(clicker, seatOf(hitbox, piece));
        }
    }

    // ---- what it looks like --------------------------------------------

    /**
     * Whether anything can put a different model on this piece's display
     * after it is placed.
     */
    private static boolean changesLook(ModelInfo info) {
        if (info.jukebox().flatMap(ModelInfo.Jukebox::playingModel).isPresent()) {
            return true;
        }
        for (ModelInfo.State state : info.states()) {
            if (state.model().isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The item (or model) whose model this piece should be wearing right now.
     *
     * <p>Worked out from what the piece holds and nothing else, rather than
     * remembered: a playing gramophone is the playing model however it came to
     * be playing, and restoring after the disc comes out is the same question
     * asked again.
     */
    private ContentId lookOf(Interaction hitbox, ModelInfo info) {
        Optional<ContentId> playing = info.jukebox().flatMap(ModelInfo.Jukebox::playingModel);
        if (playing.isPresent() && jukeboxes != null && jukeboxes.holding(hitbox)) {
            return playing.get();
        }
        return info.state(stateOf(hitbox)).flatMap(ModelInfo.State::model).orElse(info.item());
    }

    /**
     * The model id an item renders through, or the id itself if it is not an
     * item — which is how a pack names a model with no item of its own.
     */
    private ContentId modelOf(ContentId itemOrModel) {
        return items.info(itemOrModel).map(ai.resourcepack.engine.api.ItemInfo::modelId).orElse(itemOrModel);
    }

    /**
     * Puts on the display whatever {@link #lookOf} says.
     *
     * <p>Only on a still piece. An animated one is several displays each
     * wearing a part, and a whole-piece model swapped onto every part would
     * be the whole model drawn once per bone.
     */
    private void refreshLook(Interaction hitbox, ModelInfo info) {
        ItemDisplay display = stillDisplay(hitbox);
        if (display == null || !changesLook(info)) {
            return;
        }
        ItemStack stack = display.getItemStack();
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        items.wearModel(stack, modelOf(lookOf(hitbox, info)));
        display.setItemStack(stack);
    }

    /** The one display of a still piece, or null for an animated one or a missing one. */
    private ItemDisplay stillDisplay(Interaction hitbox) {
        if (hitbox.getPersistentDataContainer().has(rigModelKey, PersistentDataType.STRING)) {
            return null;
        }
        List<String> ids = displayIdsOf(hitbox);
        if (ids.size() != 1) {
            return null;
        }
        try {
            Entity display = Bukkit.getEntity(UUID.fromString(ids.get(0)));
            return display instanceof ItemDisplay ? (ItemDisplay) display : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether this piece is an animated one, several displays the animator poses. */
    private boolean isRig(ModelInfo info) {
        RigStore.Rig rig = rigs == null ? null : rigs.get(info.id().toString());
        return rig != null && rig.parts != null && !rig.parts.isEmpty();
    }

    /**
     * What a pack asked of an animated piece that only a still one can do.
     *
     * <p>Run after a reload has registered the rigs, which is the first moment
     * anybody knows which pieces animate — the definition parser does not.
     */
    public List<ai.resourcepack.engine.api.Diagnostic> rigDiagnostics() {
        List<ai.resourcepack.engine.api.Diagnostic> found = new ArrayList<>();
        for (ModelInfo info : model.values()) {
            if (!isRig(info)) {
                continue;
            }
            if (info.jukebox().flatMap(ModelInfo.Jukebox::playingModel).isPresent()) {
                found.add(ai.resourcepack.engine.api.Diagnostic.warning(info.id().toString(),
                        "jukebox playing-model: does nothing on an animated piece, whose look is its "
                                + "parts. It plays without changing."));
            }
            for (int i = 0; i < info.states().size(); i++) {
                ModelInfo.State state = info.states().get(i);
                if (state.model().isPresent() || state.moves()) {
                    found.add(ai.resourcepack.engine.api.Diagnostic.warning(info.id().toString(),
                            "state " + (i + 1) + ": model, turn and offset do nothing on an animated piece, "
                                    + "whose parts are posed by its animation. Its light, solid and sound "
                                    + "still change; give it a right-click animation to show the change."));
                }
            }
        }
        return found;
    }

    // ---- states ----------------------------------------------------------

    /** Which state a piece is in. 0, the piece as defined, if it never said. */
    private int stateOf(Interaction hitbox) {
        Integer stored = hitbox.getPersistentDataContainer().get(stateKey, PersistentDataType.INTEGER);
        return stored == null ? 0 : stored;
    }

    /**
     * Puts a piece into state {@code index}: its block, its model, its angle
     * and position, and the sound of getting there.
     *
     * <p>Everything is set from the state rather than changed from the last
     * one, so going from any state to any other is the same call and a piece
     * whose pack changed under it lands somewhere coherent.
     */
    private void enterState(Interaction hitbox, ModelInfo info, int index) {
        if (index == 0) {
            hitbox.getPersistentDataContainer().remove(stateKey);
        } else {
            hitbox.getPersistentDataContainer().set(stateKey, PersistentDataType.INTEGER, index);
        }
        boolean solid = info.solidIn(index);
        anchor(hitbox, solid, solid ? 0 : info.lightIn(index));
        refreshLook(hitbox, info);
        pose(hitbox, info, info.state(index).orElse(null));
        String sound = index == 0
                ? info.baseSound().orElse(null)
                : info.state(index).flatMap(ModelInfo.State::sound).orElse(null);
        ai.resourcepack.engine.core.sound.SoundAt.play(sounds, hitbox.getLocation().add(0, 0.5, 0), sound,
                org.bukkit.SoundCategory.BLOCKS, 1f, 1f);
    }

    /**
     * Turns and moves a still piece's display for a state.
     *
     * <p>Through the display's transformation rather than its yaw, for two
     * reasons. The translation of a transformation is in the display's OWN
     * frame — right, up, forward of the piece as placed — which is exactly the
     * frame a state's offset is written in, so a sliding door slides along
     * itself whichever way it was put down; and a transformation change is
     * interpolated by the client, so a door swings rather than teleporting
     * open. The turn goes in the left rotation, after the translation, so a
     * piece turns about its own centre and THEN moves — which is how a hinge is
     * written: a quarter turn, and an offset to put the edge back on the hinge.
     *
     * <p>The yaw stored on the hitbox is left alone. It is what vehicles turn
     * the model's boxes by, and the hitbox does not swing with the door.
     */
    private void pose(Interaction hitbox, ModelInfo info, ModelInfo.State state) {
        boolean anyMoves = false;
        for (ModelInfo.State one : info.states()) {
            anyMoves |= one.moves();
        }
        if (!anyMoves) {
            // Never touched, so a plain lamp's display is exactly what place()
            // made of it.
            return;
        }
        ItemDisplay display = stillDisplay(hitbox);
        if (display == null) {
            return;
        }
        Transformation now = display.getTransformation();
        org.joml.Vector3f translation = state == null
                ? new org.joml.Vector3f()
                : new org.joml.Vector3f(state.offsetX(), state.offsetY(), state.offsetZ()).mul(info.scale());
        // Adding to a yaw is a NEGATIVE turn about y: the display is drawn
        // turned by -yaw, so this composes into -(yaw + turn).
        org.joml.Quaternionf turn = state == null
                ? new org.joml.Quaternionf()
                : new org.joml.Quaternionf().rotateY((float) Math.toRadians(-state.turn()));
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(SWING_TICKS);
        display.setTransformation(new Transformation(translation, turn, now.getScale(), now.getRightRotation()));
    }

    /**
     * Books the piece's return to the piece as defined, replacing any return
     * already booked. A door clicked twice shuts after the second click, not
     * the first.
     */
    private void scheduleReset(Interaction hitbox, ModelInfo info, int index) {
        UUID uuid = hitbox.getUniqueId();
        org.bukkit.scheduler.BukkitTask pending = resets.remove(uuid);
        if (pending != null) {
            pending.cancel();
        }
        if (index == 0 || info.stateResetTicks() <= 0) {
            return;
        }
        ContentId id = info.id();
        resets.put(uuid, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            resets.remove(uuid);
            Entity entity = Bukkit.getEntity(uuid);
            ModelInfo current = model.get(id);
            // Gone, unloaded, or already back: nothing to do. An unloaded one
            // is booked again when its chunk comes back; see onLoad.
            if (entity instanceof Interaction && entity.isValid() && current != null
                    && stateOf((Interaction) entity) != 0) {
                enterState((Interaction) entity, current, 0);
            }
        }, info.stateResetTicks()));
    }

    /**
     * A piece left in a state that resets itself, coming back with its chunk,
     * gets its reset booked again — from now, because how long it was away is
     * not something an unloaded chunk counted.
     */
    @EventHandler
    public void onLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            adopt(entity);
        }
    }

    /**
     * Everything already standing in a loaded chunk, as {@link #onLoad} would
     * have seen it. For startup and reload: the chunks round spawn are loaded
     * before this plugin is, so their load events have been and gone.
     */
    public void adoptLoaded() {
        for (World world : Bukkit.getWorlds()) {
            for (Interaction hitbox : world.getEntitiesByClass(Interaction.class)) {
                adopt(hitbox);
            }
        }
    }

    private void adopt(Entity entity) {
        if (!(entity instanceof Interaction)) {
            return;
        }
        Interaction hitbox = (Interaction) entity;
        ModelInfo info = idOf(hitbox).map(model::get).orElse(null);
        if (info == null) {
            return;
        }
        if (info.stateResetTicks() > 0) {
            int state = stateOf(hitbox);
            if (state != 0 && !resets.containsKey(hitbox.getUniqueId())) {
                scheduleReset(hitbox, info, state);
            }
        }
        if (growth != null && info.grow().isPresent()) {
            growth.track(hitbox);
        }
    }

    /** Every booked reset forgotten. The plugin is going, and its tasks with it. */
    public void stop() {
        for (org.bukkit.scheduler.BukkitTask task : resets.values()) {
            task.cancel();
        }
        resets.clear();
    }

    // ---- storage -------------------------------------------------------

    /**
     * Where a piece's own contents live: on its hitbox.
     *
     * <p>Keyed by the hitbox's uuid, which is what makes every player who
     * opens this piece share one live inventory, and what the unload handler
     * closes by.
     */
    private ai.resourcepack.engine.core.storage.StorageHolder storageOf(Interaction hitbox) {
        return new ai.resourcepack.engine.core.storage.PdcStorage(storageKey(hitbox.getUniqueId()),
                hitbox.getPersistentDataContainer(), storages.contentsKey(), plugin.getLogger());
    }

    private static String storageKey(UUID hitbox) {
        return "piece/" + hitbox;
    }

    private void openStorage(Player player, Interaction hitbox, ModelInfo info) {
        // The item's own name when the pack did not give the screen one, so a
        // cabinet called "Oak Cabinet" in your hand is called that when open.
        String title = items.info(info.item()).flatMap(ai.resourcepack.engine.api.ItemInfo::name)
                .orElse("Storage");
        storages.open(player, info.storage().get(), info.item(), storageOf(hitbox), title,
                hitbox.getLocation().add(0, 0.5, 0));
    }

    /**
     * Whatever a piece's chunk is taking away is saved and closed while it is
     * still there to save onto.
     *
     * <p>Reachable only by a viewer who has wandered off — opening needs a
     * click within reach — but a teleport with a screen open is exactly that,
     * and the save after this would land on an entity that no longer exists.
     */
    @EventHandler
    public void onUnload(org.bukkit.event.world.EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Interaction
                    && entity.getPersistentDataContainer().has(idKey, PersistentDataType.STRING)) {
                if (storages != null) {
                    storages.close(storageKey(entity.getUniqueId()));
                }
                // Not checked while its chunk is away. The time still counts,
                // so it may grow at the first check after it comes back — one
                // stage, because what it grows into starts its own clock then.
                if (growth != null) {
                    growth.forget(entity.getUniqueId());
                }
            }
        }
    }

    /**
     * Where somebody sits on this piece.
     *
     * <p>The seat's sideways and forward offsets are turned to match the piece
     * rather than the world, so a bench placed facing east seats people ALONG
     * itself. Turning them with the yaw is the whole reason they are stated as
     * side and forward rather than as x and z in the world.
     */
    private static Location seatOf(Interaction hitbox, ModelInfo info) {
        Location at = hitbox.getLocation();
        float yaw = at.getYaw();
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);

        // Minecraft's yaw: 0 faces south (+z), and +x is to the west of that.
        double side = info.seatSide() * info.scale();
        double forward = info.seatForward() * info.scale();

        Location seat = at.clone().add(
                side * cos - forward * sin,
                info.seat() * info.scale(),
                side * sin + forward * cos);
        seat.setYaw(yaw);
        return seat;
    }

    /** The model standing as {@code hitbox}, or empty if that is not one of ours. */
    public Optional<ContentId> idOf(Interaction hitbox) {
        if (hitbox == null) {
            return Optional.empty();
        }
        return ContentId.parse(hitbox.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    // ---- breaking ------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPunch(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Interaction)) {
            return;
        }
        Interaction hitbox = (Interaction) event.getEntity();
        Optional<ContentId> id = idOf(hitbox);
        if (id.isEmpty()) {
            // Somebody else's Interaction entity. Not ours to remove.
            return;
        }
        event.setCancelled(true);

        Player player = event.getDamager() instanceof Player ? (Player) event.getDamager() : null;
        boolean drop = player == null || player.getGameMode() != GameMode.CREATIVE;
        remove(hitbox, id.get(), player, drop);
    }

    /** Takes a piece apart, dropping its item unless told otherwise. */
    public void remove(Interaction hitbox, ContentId id, Player breaker, boolean dropItem) {
        ModelBreakEvent ask = new ModelBreakEvent(id, hitbox.getLocation(), breaker, dropItem);
        Bukkit.getPluginManager().callEvent(ask);
        if (ask.isCancelled()) {
            return;
        }

        Location where = hitbox.getLocation();
        World world = where.getWorld();
        Dismantled gone = dismantle(hitbox, model.get(id));
        // After it is gone, so an action that gives something back or runs a
        // command about the space finds it empty, as a broken piece is.
        act(breaker, id, ai.resourcepack.engine.api.ItemAction.Trigger.REMOVE);

        if (world == null) {
            return;
        }
        if (!ask.isDropItem()) {
            // The piece is not given back — a creative break, or a plugin that
            // said so — but what was IN it was never the piece's to lose. It
            // spills, as a chest's contents do whoever breaks the chest.
            spill(world, where, gone.contents);
            return;
        }
        // A configured drop beats what it was holding: a piece that gives
        // back something other than itself is a decision the pack made, and
        // the display's own stack is only the default answer.
        ItemStack configured = model.containsKey(id)
                ? model.get(id).drop().flatMap(items::create).orElse(null)
                : null;
        ItemStack fallback = configured != null
                ? configured
                : gone.shown != null ? gone.shown : items.create(modelItem(id)).orElse(null);
        List<ItemStack> contents = gone.contents;
        if (gone.keepInside && fallback != null) {
            // A shulker-style piece goes back into the item, contents and all.
            storages.keepInside(fallback, contents);
            contents = List.of();
        }
        if (fallback != null) {
            world.dropItemNaturally(where.clone().add(0, 0.5, 0), fallback);
        }
        spill(world, where, contents);
    }

    /**
     * Takes a piece out of the world and hands back what it leaves: every
     * display, the hitbox, the block it put down, anybody sitting on it, a
     * pending reset, a disc (dropped at once) and a container's contents
     * (returned, for the caller to spill or pack).
     *
     * <p>Shared by breaking and growing, which differ only in what is given
     * back afterwards and which events are fired — neither of which is here.
     */
    private Dismantled dismantle(Interaction hitbox, ModelInfo info) {
        Location where = hitbox.getLocation();

        // What it was holding, taken out FIRST: every view of it is closed and
        // emptied before anything else happens, so nobody can take something
        // out of a cabinet whose contents are about to be on the floor. After
        // the cancel check above, so a break somebody refused spills nothing.
        // A piece that has contents but whose definition no longer says it is
        // a container (the pack changed) spills them like a chest rather than
        // taking them away with it.
        ai.resourcepack.engine.api.StorageSpec storage = info == null ? null : info.storage().orElse(null);
        if (storage == null && storages != null
                && hitbox.getPersistentDataContainer().has(storages.contentsKey(), PersistentDataType.BYTE_ARRAY)) {
            storage = ai.resourcepack.engine.api.StorageSpec.chest();
        }
        List<ItemStack> contents = storage == null || storages == null
                ? List.of()
                : storages.removed(storage, storageOf(hitbox));
        boolean keepInside = storage != null
                && storage.type() == ai.resourcepack.engine.api.StorageSpec.Type.SHULKER;
        // A disc is never broken with its jukebox, and the music stops with
        // it. Whatever the pack now says, because the disc is somebody's.
        if (jukeboxes != null && jukeboxes.holding(hitbox)) {
            jukeboxes.eject(hitbox, info == null ? 1f
                    : info.jukebox().map(ModelInfo.Jukebox::volume).orElse(1f));
        }

        // A door due to shut itself has nothing to shut.
        org.bukkit.scheduler.BukkitTask reset = resets.remove(hitbox.getUniqueId());
        if (reset != null) {
            reset.cancel();
        }

        // Every display, because an animated piece is several and one left
        // behind is a limb standing in an empty block.
        ItemStack drop = null;
        for (String raw : displayIdsOf(hitbox)) {
            Entity display;
            try {
                display = Bukkit.getEntity(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                // A malformed id costs that one display and nothing else.
                continue;
            }
            if (!(display instanceof ItemDisplay)) {
                continue;
            }
            animator.untrack(display.getUniqueId());
            // Drops what it was placed holding, so a renamed or enchanted
            // piece comes back as itself rather than as a fresh one — but
            // never a PART, which is a sub-model with no inventory form. An
            // animated piece falls through to items.create below.
            if (drop == null && !display.getPersistentDataContainer().has(partKey, PersistentDataType.INTEGER)) {
                drop = ((ItemDisplay) display).getItemStack();
            }
            display.remove();
        }
        animator.untrackHitbox(hitbox.getUniqueId());
        if (drop != null && info != null && changesLook(info)) {
            // The display may be wearing a lamp's lit model or a gramophone's
            // playing one. What goes back in a hand is the piece as it is
            // sold, so it is put back in its own model first.
            items.wearModel(drop, modelOf(info.item()));
        }

        // Anybody sitting on it stands up first. A seat that outlives its
        // chair is an invisible thing a player can stand on for ever.
        for (Entity rider : hitbox.getWorld().getNearbyEntities(where, 1.5, 2.5, 1.5)) {
            if (rider instanceof Player && seats.isSeated((Player) rider)) {
                seats.stand((Player) rider);
            }
        }

        // The barrier and the light are both blocks WE put there, and a
        // block left behind is invisible and permanent — the single worst way
        // this feature can rot.
        Block anchor = where.getBlock();
        if (hitbox.getPersistentDataContainer().has(solidKey, PersistentDataType.BYTE)
                && anchor.getType() == Material.BARRIER) {
            anchor.setType(Material.AIR, false);
        } else if (hitbox.getPersistentDataContainer().has(lightKey, PersistentDataType.BYTE)
                && anchor.getType() == Material.LIGHT) {
            anchor.setType(Material.AIR, false);
        }
        bedrock.rigRemoved(hitbox.getUniqueId());
        if (growth != null) {
            growth.forget(hitbox.getUniqueId());
        }
        hitbox.remove();
        return new Dismantled(drop, contents, keepInside);
    }

    /** What taking a piece apart left over, for the caller to give back or spill. */
    private static final class Dismantled {

        /** What its display was holding, back in its own model; null for a rig. */
        final ItemStack shown;
        /** What was in it, for a container whose contents were its own. */
        final List<ItemStack> contents;
        /** Whether those go back inside the item rather than on the ground. */
        final boolean keepInside;

        Dismantled(ItemStack shown, List<ItemStack> contents, boolean keepInside) {
            this.shown = shown;
            this.contents = contents;
            this.keepInside = keepInside;
        }
    }

    // ---- growing --------------------------------------------------------

    /** When a piece was put down, in the game's own tick count, on its hitbox. */
    private final NamespacedKey placedAtKey;

    /** Keeps the set of pieces that can grow. Null until wired. */
    private ModelGrowth growth;

    public void growth(ModelGrowth growth) {
        this.growth = growth;
    }

    /** The piece standing as {@code hitbox}, as the loaded content defines it. */
    public Optional<ModelInfo> infoOf(Interaction hitbox) {
        return idOf(hitbox).map(model::get);
    }

    /** The piece {@code id} as the loaded content defines it. */
    public Optional<ModelInfo> info(ContentId id) {
        return Optional.ofNullable(id == null ? null : model.get(id));
    }

    /**
     * When {@code hitbox} was put down, in game ticks, recording now for a
     * piece placed before anybody wrote that down — so a piece that was given
     * {@code grow:} by a later version of its pack starts counting from the
     * first time it is asked rather than growing at once.
     */
    long placedAt(Interaction hitbox) {
        Long stored = hitbox.getPersistentDataContainer().get(placedAtKey, PersistentDataType.LONG);
        if (stored != null) {
            return stored;
        }
        long now = hitbox.getWorld().getGameTime();
        hitbox.getPersistentDataContainer().set(placedAtKey, PersistentDataType.LONG, now);
        return now;
    }

    /**
     * Replaces a piece with the one it grows into, in the same block and
     * facing the same way.
     *
     * <p><strong>Not a break.</strong> No {@code ModelBreakEvent}, no
     * {@code remove} actions, no item given back, and no {@code ModelPlaceEvent}
     * or {@code place} actions for what replaces it: a sapling becoming a tree
     * is not somebody breaking a sapling and planting a tree, and a protection
     * plugin that answered either event would be answering a question nobody
     * asked. What the piece was HOLDING is a different matter — a disc, a
     * container's contents — and those are given back on the ground, because
     * they were never the piece's to lose.
     *
     * @return the new piece's hitbox
     */
    Interaction grow(Interaction hitbox, ModelInfo from, ModelInfo into) {
        Float stored = hitbox.getPersistentDataContainer().get(placedYawKey, PersistentDataType.FLOAT);
        float yaw = stored != null ? stored : hitbox.getLocation().getYaw();
        Location where = hitbox.getLocation();
        Block block = where.getBlock();
        Dismantled gone = dismantle(hitbox, from);
        if (where.getWorld() != null) {
            spill(where.getWorld(), where, gone.contents);
        }
        return place(block, into, yaw, null);
    }

    /** Puts a container's contents on the ground where it stood. */
    private static void spill(World world, Location where, List<ItemStack> contents) {
        for (ItemStack stack : contents) {
            world.dropItemNaturally(where.clone().add(0, 0.5, 0), stack);
        }
    }

    /** The displays a placement owns: several for a rig, one for a still piece. */
    private List<String> displayIdsOf(Interaction hitbox) {
        String joined = hitbox.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
        if (joined == null || joined.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        java.util.Collections.addAll(ids, joined.split(","));
        return ids;
    }

    private ContentId modelItem(ContentId id) {
        ModelInfo info = model.get(id);
        return info == null ? id : info.item();
    }

    /**
     * Every model placed within {@code radius} blocks of {@code centre}.
     *
     * <p>There is no index of placed models and deliberately never will be:
     * they are chunk-saved entities, so the world IS the index and cannot
     * drift out of step with itself. The cost is that finding them means
     * asking the world, which is why this takes a radius rather than
     * pretending a whole-server list is cheap.
     */
    public java.util.List<Interaction> near(Location centre, double radius) {
        java.util.List<Interaction> found = new java.util.ArrayList<>();
        if (centre == null || centre.getWorld() == null) {
            return found;
        }
        for (Entity entity : centre.getWorld().getNearbyEntities(centre, radius, radius, radius)) {
            if (entity instanceof Interaction && idOf((Interaction) entity).isPresent()) {
                found.add((Interaction) entity);
            }
        }
        return found;
    }

    /**
     * Whether a placed model is one the loaded content still knows about.
     *
     * <p>Deleting a content pack does not delete what was placed from it, and
     * should not: reinstalling the pack brings somebody's build back rather
     * than leaving a hole in it. But it does leave models standing that
     * nothing can explain, and being able to ask is the difference between a
     * mystery and a decision.
     */
    public boolean isOrphan(Interaction hitbox) {
        return idOf(hitbox).filter(model::containsKey).isEmpty();
    }

    /** The hitbox of the piece standing in {@code block}, or null. */
    public Interaction findAt(Block block) {
        Location centre = block.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : block.getWorld().getNearbyEntities(centre, 0.5, 0.5, 0.5)) {
            if (entity instanceof Interaction
                    && entity.getPersistentDataContainer().has(idKey, PersistentDataType.STRING)) {
                return (Interaction) entity;
            }
        }
        return null;
    }
}
