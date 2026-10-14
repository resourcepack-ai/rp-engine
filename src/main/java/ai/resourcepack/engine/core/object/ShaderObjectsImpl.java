package ai.resourcepack.engine.core.object;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ShaderObject;
import ai.resourcepack.engine.api.ShaderObjectInfo;
import ai.resourcepack.engine.api.ShaderObjects;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The API's view of {@link ObjectRuntime}. */
public final class ShaderObjectsImpl implements ShaderObjects {

    private final ObjectRuntime runtime;

    public ShaderObjectsImpl(ObjectRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public boolean supported() {
        return runtime.supported();
    }

    @Override
    public List<ContentId> ids() {
        List<ContentId> ids = new ArrayList<>(runtime.catalogue().all().keySet());
        ids.sort(null);
        return ids;
    }

    @Override
    public Optional<ShaderObjectInfo> info(ContentId id) {
        return runtime.catalogue().info(id);
    }

    @Override
    public Optional<ShaderObject> spawn(Location where, ContentId id) {
        return spawn(where, id, 0);
    }

    @Override
    public Optional<ShaderObject> spawn(Location where, ContentId id, double size) {
        return runtime.catalogue().info(id).flatMap(info -> runtime.spawn(where, info, size)).map(d -> d);
    }

    @Override
    public Optional<ShaderObject> at(Entity entity) {
        return runtime.at(entity).map(d -> d);
    }

    @Override
    public List<ShaderObject> near(Location near, double radius) {
        return new ArrayList<>(runtime.near(near, radius));
    }

    @Override
    public List<ShaderObject> loaded() {
        return new ArrayList<>(runtime.loaded());
    }
}
