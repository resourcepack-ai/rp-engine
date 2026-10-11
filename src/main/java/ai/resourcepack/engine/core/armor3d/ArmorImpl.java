package ai.resourcepack.engine.core.armor3d;

import ai.resourcepack.engine.api.Armor;
import ai.resourcepack.engine.api.ArmorInfo;
import ai.resourcepack.engine.api.ArmorPiece;
import ai.resourcepack.engine.api.ArmorResult;
import ai.resourcepack.engine.api.ContentId;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link Armor}, over the same catalogue and wardrobe {@code /rp armor} uses. */
public final class ArmorImpl implements Armor {

    private final Armor3dCatalogue sets;
    private final Armor3dItems items;
    private final Wardrobe wardrobe;
    private final boolean available;

    public ArmorImpl(Armor3dCatalogue sets, Armor3dItems items, Wardrobe wardrobe, boolean available) {
        this.sets = sets;
        this.items = items;
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
        for (Armor3dSet set : sets.all().values()) {
            idOf(set).ifPresent(ids::add);
        }
        return List.copyOf(ids);
    }

    @Override
    public Optional<ArmorInfo> info(ContentId id) {
        Armor3dSet set = find(id);
        if (set == null) {
            return Optional.empty();
        }
        Set<ArmorPiece> pieces = EnumSet.noneOf(ArmorPiece.class);
        for (Armor3dSet.Piece piece : set.pieces().keySet()) {
            pieces.add(toApi(piece));
        }
        return Optional.of(ArmorInfo.of(id, set.name(), pieces, !set.authored()));
    }

    @Override
    public Optional<ItemStack> create(ContentId id, ArmorPiece piece) {
        Armor3dSet set = find(id);
        if (!available || set == null || piece == null) {
            return Optional.empty();
        }
        return set.piece(fromApi(piece)).map(worn -> items.piece(set, worn));
    }

    @Override
    public ArmorResult wear(Player player, ContentId id) {
        Armor3dSet set = find(id);
        if (player == null || set == null) {
            return ArmorResult.of(ArmorResult.Reason.UNKNOWN_SET, List.of());
        }
        if (!available) {
            return ArmorResult.of(ArmorResult.Reason.UNSUPPORTED, List.of());
        }
        return ArmorResult.of(ArmorResult.Reason.WORN, wardrobe.wear(player, set));
    }

    @Override
    public List<ItemStack> strip(Player player) {
        return player == null ? List.of() : wardrobe.strip(player);
    }

    @Override
    public Map<ArmorPiece, ContentId> wearing(Player player) {
        Map<ArmorPiece, ContentId> out = new EnumMap<>(ArmorPiece.class);
        if (player == null) {
            return out;
        }
        for (Map.Entry<Armor3dSet.Piece, String> entry : wardrobe.wearing(player).entrySet()) {
            ContentId.parse(Armor3dCatalogue.label(entry.getValue()))
                    .ifPresent(id -> out.put(toApi(entry.getKey()), id));
        }
        return out;
    }

    private Armor3dSet find(ContentId id) {
        return id == null ? null : sets.find(id.toString());
    }

    private static Optional<ContentId> idOf(Armor3dSet set) {
        return ContentId.parse(Armor3dCatalogue.label(set));
    }

    static ArmorPiece toApi(Armor3dSet.Piece piece) {
        return ArmorPiece.valueOf(piece.name());
    }

    static Armor3dSet.Piece fromApi(ArmorPiece piece) {
        return Armor3dSet.Piece.valueOf(piece.name());
    }
}
