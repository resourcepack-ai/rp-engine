package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/**
 * The public {@link ai.resourcepack.engine.api.Overlays} over the two internal
 * halves: {@link Overlays} knows what the pack holds, {@link OverlayRuntime}
 * knows who is wearing what.
 *
 * <p>A thin join rather than a third store, on the same pattern as
 * {@link IconsImpl}: the API is a face, and putting state behind it would give
 * the engine two answers to "is this player wearing that".
 */
public final class OverlaysImpl implements ai.resourcepack.engine.api.Overlays {

    private final Overlays overlays;
    private final OverlayRuntime runtime;

    public OverlaysImpl(Overlays overlays, OverlayRuntime runtime) {
        this.overlays = overlays;
        this.runtime = runtime;
    }

    @Override
    public Collection<ContentId> ids() {
        return overlays.hudIds();
    }

    @Override
    public Optional<OverlayInfo> info(ContentId id) {
        return overlays.hud(id);
    }

    @Override
    public Optional<OverlayInfo> info(String id) {
        return ContentId.parse(id).flatMap(overlays::hud);
    }

    @Override
    public boolean show(Player viewer, ContentId id) {
        return runtime.show(viewer, id);
    }

    @Override
    public boolean show(Player viewer, String id) {
        return ContentId.parse(id).map(parsed -> runtime.show(viewer, parsed)).orElse(false);
    }

    @Override
    public boolean hide(Player viewer, ContentId id) {
        return runtime.hide(viewer, id);
    }

    @Override
    public boolean hide(Player viewer, String id) {
        return ContentId.parse(id).map(parsed -> runtime.hide(viewer, parsed)).orElse(false);
    }

    @Override
    public void hideAll(Player viewer) {
        runtime.hideAll(viewer);
    }

    @Override
    public boolean isShowing(Player viewer, ContentId id) {
        return runtime.isShowing(viewer, id);
    }

    @Override
    public Collection<ContentId> showing(Player viewer) {
        return runtime.showing(viewer);
    }

    @Override
    public void set(Player viewer, String name, String value) {
        runtime.set(viewer, name, value);
    }

    @Override
    public Optional<String> value(Player viewer, String name) {
        return runtime.value(viewer, name);
    }
}
