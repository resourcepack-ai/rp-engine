package ai.resourcepack.engine.core.armor3d;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.core.model.RigTags;
import ai.resourcepack.engine.core.version.Compatibility;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stacks 3D armour is made of: the four pieces a player holds and wears,
 * and the art each display holds.
 *
 * <p>Everything is paper wearing a string {@code custom_model_data}, because
 * that is how a pack built by Studio names art — see {@link Armor3dSet}. The
 * string is also the fallback for telling a piece apart: a piece given by a
 * hand-written {@code /give} with no plugin behind it carries only that, and is
 * still recognised when it is put on.
 */
public final class Armor3dItems {

    /** The prefix of every model string a set's art is named by — Studio's {@code lib/armor3d/pack.ts}. */
    public static final String MODEL_PREFIX = "armor3d:";

    /** Studio's Bedrock marker for a piece; GeyserBridge registers the pool it names. */
    public static final String BEDROCK_MARKER = "rpai_armor_";

    private final NamespacedKey pieceKey;
    private final RigTags tags;
    /** Puts the slot, and the asset where there is one, on a piece. */
    private interface Equipper {
        void wear(ItemMeta meta, EquipmentSlot slot, String asset);
    }

    private final Equipper equipping;
    private final Map<String, ItemStack> art = new ConcurrentHashMap<>();

    public Armor3dItems(Plugin plugin, Compatibility compatibility) {
        this.pieceKey = new NamespacedKey(plugin, "armor3d");
        this.tags = RigTags.forServer(compatibility, plugin);
        // A method reference to the newer class, so this one never names the
        // API itself — see version-arms.txt.
        boolean withArt = compatibility.has(Feature.ARMOUR_ART);
        this.equipping = compatibility.has(Feature.EQUIPPABLE_ITEMS)
                ? (meta, slot, asset) -> PieceEquipping.wearable(meta, slot, asset, withArt)
                : (meta, slot, asset) -> { };
    }

    /** One piece of a set, as an item somebody can hold and put on. */
    public ItemStack piece(Armor3dSet set, Armor3dSet.Worn worn) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        // The art at index 0; the Bedrock marker, when studio numbered the
        // piece, at index 1, which is where Geyser's mapping looks.
        tags.write(meta, worn.bedrockSlot() == null ? List.of(worn.item())
                : List.of(worn.item(), BEDROCK_MARKER + worn.bedrockSlot()));
        meta.getPersistentDataContainer().set(pieceKey, PersistentDataType.STRING,
                set.id() + "/" + worn.piece().wire());
        meta.setDisplayName(ChatColor.RESET + set.name() + " " + worn.piece().label());
        meta.setLore(List.of(ChatColor.GRAY + "3D armour"));
        equipping.wear(meta, worn.piece().slot(), worn.asset());
        stack.setItemMeta(meta);
        return stack;
    }

    /** Which set and piece a stack is, if it is one. */
    public Optional<Worn> read(ItemStack stack) {
        if (stack == null || stack.getType() != Material.PAPER || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String marked = meta.getPersistentDataContainer().get(pieceKey, PersistentDataType.STRING);
        if (marked == null) {
            // A hand-written /give: the only thing on it is the art.
            List<String> strings = tags.read(stack);
            if (strings.isEmpty() || !strings.get(0).startsWith(MODEL_PREFIX)) {
                return Optional.empty();
            }
            marked = strings.get(0).substring(MODEL_PREFIX.length());
        }
        int slash = marked.lastIndexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        String setId = marked.substring(0, slash);
        return Armor3dSet.Piece.of(marked.substring(slash + 1)).map(piece -> new Worn(setId, piece));
    }

    /** Paper wearing {@code model}: what one display holds. Built once per string. */
    ItemStack art(String model) {
        return art.computeIfAbsent(model, m -> {
            ItemStack stack = new ItemStack(Material.PAPER);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                tags.write(meta, List.of(m));
                stack.setItemMeta(meta);
            }
            return stack;
        }).clone();
    }

    /** A piece somebody has on, by name. */
    public record Worn(String setId, Armor3dSet.Piece piece) {
    }
}
