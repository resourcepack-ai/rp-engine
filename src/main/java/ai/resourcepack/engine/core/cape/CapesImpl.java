package ai.resourcepack.engine.core.cape;

import ai.resourcepack.engine.api.Capes;
import ai.resourcepack.engine.api.ContentId;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The public face of {@code core/cape}. */
public final class CapesImpl implements Capes {

    private final CapeCatalogue capes;
    private final CapeWardrobe wardrobe;
    private final boolean available;

    public CapesImpl(CapeCatalogue capes, CapeWardrobe wardrobe, boolean available) {
        this.capes = capes;
        this.wardrobe = wardrobe;
        this.available = available;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public List<ContentId> ids() {
        List<ContentId> ids = new ArrayList<>();
        for (Cape cape : capes.all().values()) {
            ContentId.parse(cape.label()).ifPresent(ids::add);
        }
        return List.copyOf(ids);
    }

    @Override
    public Optional<String> name(ContentId id) {
        return Optional.ofNullable(id == null ? null : capes.find(id.toString())).map(Cape::name);
    }

    @Override
    public Optional<ContentId> worn(Player player) {
        Cape cape = player == null ? null : wardrobe.worn(player);
        return cape == null ? Optional.empty() : ContentId.parse(cape.label());
    }

    @Override
    public boolean wear(Player player, ContentId id) {
        Cape cape = id == null || player == null ? null : capes.find(id.toString());
        if (cape == null) {
            return false;
        }
        wardrobe.wear(player, cape);
        return true;
    }

    @Override
    public boolean remove(Player player) {
        return player != null && wardrobe.remove(player);
    }
}
