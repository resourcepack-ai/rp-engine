package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ItemOptions;
import ai.resourcepack.engine.api.Models;
import ai.resourcepack.engine.api.PlaceOptions;
import ai.resourcepack.engine.api.Placement;
import ai.resourcepack.engine.api.RigInfo;
import ai.resourcepack.engine.api.event.ModelBreakEvent;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import ai.resourcepack.engine.core.Host;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The public {@link Models} surface, over {@link RigAnimator} and
 * {@link RigPlacementListener}.
 *
 * <p>Deliberately thin: it resolves entities to placements and checks the
 * thread, and every question about rigs, animations or persistent data goes to
 * the animator, which is the one class that knows how those are stored. A
 * second reader of those PDC keys is a second thing to keep in step.
 *
 * <p>Internal. Not part of the supported API.
 */
public final class ModelsImpl implements Models {

    /** Clamped to the same range the panel offers, since a caller can pass anything. */
    private static final float MIN_SCALE = 0.125f;
    private static final float MAX_SCALE = 8f;

    private final RigAnimator animator;
    private final RigStore rigs;
    /**
     * The spawner. Always present - a host that turned off placeable ITEMS
     * turned off a click handler, not the ability to put a model in the world.
     */
    private final RigPlacementListener placements;

    /**
     * Set after construction, because binding needs Items and Items is built
     * on top of this. A null one answers false to everything rather than
     * throwing: the plugin wires it at startup, and nothing else can.
     */
    private volatile BoundModels bound;

    /** Wires the bind half. Called once at startup. */
    public void bound(BoundModels bound) {
        this.bound = bound;
    }

    @Override
    public boolean bind(org.bukkit.entity.Entity host, ai.resourcepack.engine.api.ContentId model) {
        return bind(host, model, 1f);
    }

    @Override
    public boolean bind(org.bukkit.entity.Entity host, ai.resourcepack.engine.api.ContentId model, float scale) {
        BoundModels models = bound;
        return models != null && models.bind(host, model, scale);
    }

    @Override
    public boolean unbind(org.bukkit.entity.Entity host) {
        BoundModels models = bound;
        return models != null && models.unbind(host);
    }

    @Override
    public java.util.Optional<ai.resourcepack.engine.api.ContentId> modelOn(org.bukkit.entity.Entity host) {
        BoundModels models = bound;
        return models == null ? java.util.Optional.empty() : models.modelOn(host);
    }

    @Override
    public boolean animate(org.bukkit.entity.Entity host, String animation) {
        BoundModels models = bound;
        // restart: an API call asks for this animation NOW, unlike a trigger,
        // which may be one of several players clicking the same thing.
        return models != null && models.play(host, animation, true);
    }

    @Override
    public boolean stopAnimating(org.bukkit.entity.Entity host) {
        BoundModels models = bound;
        return models != null && models.stop(host);
    }

    public ModelsImpl(RigAnimator animator, RigStore rigs, RigPlacementListener placements) {
        this.animator = animator;
        this.rigs = rigs;
        this.placements = placements;
    }

    @Override
    public List<String> ids() {
        return List.copyOf(rigs.modelIds());
    }

    @Override
    public Optional<RigInfo> info(String modelId) {
        if (modelId == null || rigs.get(modelId) == null) return Optional.empty();
        return Optional.of(new RigInfo(
                modelId, animator.isAnimated(modelId), animator.animationNamesOf(modelId)));
    }

    @Override
    public List<String> animationsOf(String modelId) {
        return modelId == null ? List.of() : animator.animationNamesOf(modelId);
    }

    @Override
    public boolean isAnimated(String modelId) {
        return modelId != null && animator.isAnimated(modelId);
    }

    @Override
    public boolean isModel(Entity entity) {
        Host.requireMainThread();
        // Same resolution as at(), so the two can never disagree about what
        // counts as a model — it just stops before building the handle.
        return animator.hitboxOf(entity) != null;
    }

    @Override
    public Optional<Placement> at(Entity entity) {
        Host.requireMainThread();
        Interaction hitbox = animator.hitboxOf(entity);
        return hitbox == null ? Optional.empty() : Optional.of(new Handle(hitbox));
    }

    @Override
    public List<Placement> near(Location centre, double radius) {
        Host.requireMainThread();
        if (centre == null || centre.getWorld() == null || !(radius > 0)) return List.of();
        List<Interaction> found = new ArrayList<>();
        for (Entity entity : centre.getWorld().getNearbyEntities(centre, radius, radius, radius)) {
            if (animator.isRigHitbox(entity)) found.add((Interaction) entity);
        }
        found.sort(Comparator.comparingDouble(h -> h.getLocation().distanceSquared(centre)));
        List<Placement> nearby = new ArrayList<>(found.size());
        for (Interaction hitbox : found) nearby.add(new Handle(hitbox));
        return List.copyOf(nearby);
    }

    @Override
    public List<Placement> in(Chunk chunk) {
        Host.requireMainThread();
        if (chunk == null || !chunk.isLoaded()) return List.of();
        List<Placement> here = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            if (animator.isRigHitbox(entity)) here.add(new Handle((Interaction) entity));
        }
        return List.copyOf(here);
    }

    @Override
    public Optional<Placement> place(Location at, String modelId, PlaceOptions options) {
        Host.requireMainThread();
        if (at == null || at.getWorld() == null || modelId == null || modelId.isEmpty()) return Optional.empty();
        PlaceOptions opts = options == null ? PlaceOptions.defaults() : options;

        Block target = at.getBlock();
        // The same physical question a player's click has to pass. A rig
        // standing inside another one is two models in one block space, and
        // whichever is broken first takes the other's hitbox with it.
        if (placements.findRig(target) != null) return Optional.empty();

        ItemStack item = itemFor(modelId, ai.resourcepack.engine.api.ItemOptions.defaults()
                .animation(opts.animation())
                .scale(opts.scale())).orElse(null);
        if (item == null) return Optional.empty();

        // Announced only where there is somebody to announce - see
        // PlaceOptions.placer. A PlayerEvent with no player would be a lie
        // every listener has to guard against.
        if (opts.placer() != null) {
            ModelPlaceEvent ask = new ModelPlaceEvent(opts.placer(),
                    ai.resourcepack.engine.api.ContentId.of("studio",
                            modelId.toLowerCase(java.util.Locale.ROOT)).orElse(null),
                    target);
            Bukkit.getPluginManager().callEvent(ask);
            if (ask.isCancelled()) return Optional.empty();
        }

        return Optional.of(new Handle(placements.spawn(
                target, modelId, item, at.getYaw(), opts.animation(), clampScale(opts.scale()), opts.placer())));
    }

    @Override
    public Optional<ItemStack> itemFor(String modelId, ItemOptions options) {
        Host.requireMainThread();
        if (modelId == null || modelId.isEmpty()) return Optional.empty();
        // No check that this server "has" the model, deliberately. A still
        // model has no rig at all, and nothing here can see what art a pack
        // holds - only the client can. Refusing an id we hold no rig for would
        // therefore refuse every unanimated model in the pack.

        ItemOptions opts = options == null ? ItemOptions.defaults() : options;
        ItemStack item = RigPlacementListener.itemWithModelData(
                modelId, opts.animation(), clampScale(opts.scale()));
        item.setAmount(Math.max(1, opts.amount()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(opts.name() == null ? modelId : opts.name());
            item.setItemMeta(meta);
        }
        return Optional.of(item);
    }

    private static float clampScale(float scale) {
        if (!Float.isFinite(scale) || scale <= 0f) return 1f;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /** Builds the handle events carry. Wired in at startup by the library. */
    public Placement handleFor(Interaction hitbox) {
        return hitbox == null ? null : new Handle(hitbox);
    }

    private final class Handle implements Placement {

        private final Interaction hitbox;

        private Handle(Interaction hitbox) {
            this.hitbox = hitbox;
        }

        @Override
        public String modelId() {
            return animator.modelIdOf(hitbox);
        }

        @Override
        public Location location() {
            return hitbox.getLocation();
        }

        @Override
        public Interaction hitbox() {
            return hitbox;
        }

        @Override
        public List<String> animations() {
            return animator.animationNamesOf(animator.modelIdOf(hitbox));
        }

        @Override
        public Optional<String> playing() {
            Host.requireMainThread();
            return Optional.ofNullable(animator.playingOn(hitbox));
        }

        @Override
        public boolean play(String animation) {
            return play(animation, false);
        }

        @Override
        public boolean play(String animation, boolean restart) {
            Host.requireMainThread();
            return animator.play(hitbox, animation, restart);
        }

        @Override
        public boolean stop() {
            Host.requireMainThread();
            return animator.stop(hitbox);
        }

        @Override
        public boolean isValid() {
            return hitbox.isValid();
        }

        @Override
        public boolean remove(boolean dropItem) {
            Host.requireMainThread();
            if (!hitbox.isValid()) return false;
            String modelId = animator.modelIdOf(hitbox);
            if (modelId == null) return false;

            ModelBreakEvent ask = new ModelBreakEvent(
                ai.resourcepack.engine.api.ContentId.of("studio", modelId.toLowerCase(java.util.Locale.ROOT)).orElse(null),
                hitbox.getLocation(), null, true);
            Bukkit.getPluginManager().callEvent(ask);
            if (ask.isCancelled()) return false;

            placements.breakRig(hitbox, modelId, ask.isDropItem());
            return true;
        }

        @Override
        public PersistentDataContainer data() {
            Host.requireMainThread();
            return hitbox.getPersistentDataContainer();
        }

        /**
         * Two handles on the same rig are the same placement.
         *
         * <p>A handle is a view, not a value - every lookup mints a fresh one,
         * so without this {@code at(e).equals(at(e))} is false and a caller
         * keeping placements in a Set (which is how anyone tracks "the statues
         * I have already animated") silently collects duplicates of one rig.
         * Keyed on the hitbox's UUID rather than the entity, because an
         * Interaction fetched twice need not be the same object either.
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Handle)) return false;
            return hitbox.getUniqueId().equals(((Handle) other).hitbox.getUniqueId());
        }

        @Override
        public int hashCode() {
            return hitbox.getUniqueId().hashCode();
        }

        @Override
        public String toString() {
            return "Placement(" + modelId() + " at " + hitbox.getLocation().toVector() + ")";
        }
    }
}
