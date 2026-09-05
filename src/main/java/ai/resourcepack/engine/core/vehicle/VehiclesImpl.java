package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.Vehicles;
import ai.resourcepack.engine.core.Host;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The public {@link Vehicles} surface over {@link VehicleRuntime}.
 *
 * <p>Thin, like {@code ModelsImpl} and {@code EmotesImpl}: the thread check
 * and nothing else. Every answer is the runtime's, and the handle it hands
 * out is the runtime's own — there is one class that knows what a vehicle is
 * doing, and this is not it.
 *
 * <p>Internal. Not part of the supported API.
 */
public final class VehiclesImpl implements Vehicles {

    private final VehicleRuntime runtime;

    public VehiclesImpl(VehicleRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public List<ContentId> ids() {
        return List.copyOf(runtime.ids());
    }

    @Override
    public Optional<VehicleInfo> info(ContentId id) {
        return runtime.info(id);
    }

    @Override
    public Optional<Vehicle> spawn(Location where, ContentId id) {
        Host.requireMainThread();
        return runtime.spawnAndAdopt(where, id);
    }

    @Override
    public Optional<Vehicle> at(Entity entity) {
        Host.requireMainThread();
        return runtime.at(entity);
    }

    @Override
    public Optional<Vehicle> of(Player player) {
        Host.requireMainThread();
        return runtime.of(player);
    }

    @Override
    public boolean isRiding(UUID playerId) {
        return runtime.isRiding(playerId);
    }

    @Override
    public List<Vehicle> near(Location near, double radius) {
        Host.requireMainThread();
        return runtime.handlesNear(near, radius);
    }

    @Override
    public List<Vehicle> loaded() {
        Host.requireMainThread();
        return runtime.loaded();
    }
}
