package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 3D armour: the sets this server holds, and putting them on people.
 *
 * <p>A set comes from a content folder ({@code armor3d/}, see FORMAT.md) or a
 * Studio push, and both are here alike. Its four pieces are items: put on by
 * hand like any armour, or all at once by {@link #wear}. The helmet is drawn
 * by the game on the head; the rest is drawn by the engine on the body, for
 * whoever has the piece on. Nothing in this interface has to be called for
 * that to happen, which is why a piece handed over by {@link #create} works
 * the moment it is equipped.
 *
 * <p>Needs Minecraft 1.21.4 or newer. Below that {@link #available()} is
 * false, {@link #ids()} still lists the sets, and everything that would put
 * one on refuses.
 *
 * <p><b>Main thread only</b>, except {@link #available}, {@link #ids} and
 * {@link #info}. Every method tolerates a null argument by answering empty,
 * false or {@link ArmorResult.Reason#UNKNOWN_SET}.
 */
public interface Armor {

    /** Whether this server can draw 3D armour at all. */
    boolean available();

    /**
     * Every set this server holds, content folders' first:
     * {@code namespace:path} for a content folder's, {@code studio:<id>} for
     * a push's.
     */
    List<ContentId> ids();

    /** What a set is: its name and its pieces. */
    Optional<ArmorInfo> info(ContentId id);

    /**
     * One piece of a set, as an item somebody can hold and put on.
     *
     * <p>Paper, wearing the piece's art and an {@code equippable} naming its
     * slot, so it goes on by hand like armour and sits in no other armour
     * slot.
     *
     * @return the piece, or empty if there is no such set, the set has no
     *         such piece, or the server cannot draw 3D armour
     */
    Optional<ItemStack> create(ContentId id, ArmorPiece piece);

    /**
     * Puts every piece of a set on, now.
     *
     * <p>What was in a slot is handed back to the player rather than lost,
     * and {@link ArmorResult#displaced()} lists it, so a plugin that wants to
     * keep it (a costume shop holding on to somebody's own armour) can find
     * it. A slot the set has no piece for is left as it was. This is exactly
     * what {@code /rp armor wear} does.
     */
    ArmorResult wear(Player player, ContentId id);

    /**
     * Takes every 3D armour piece off, leaving any other armour on.
     *
     * @return what was taken off. Nothing is put in their inventory: the
     *         pieces are gone unless your plugin gives them back
     */
    List<ItemStack> strip(Player player);

    /**
     * Which set each 3D armour piece they have on belongs to. A slot holding
     * no piece, or a piece in the wrong slot, is absent, so somebody in one
     * set's helmet and another's boots is two entries naming two sets.
     */
    Map<ArmorPiece, ContentId> wearing(Player player);
}
