package ai.resourcepack.engine.core.armor3d;

import org.bukkit.inventory.EquipmentSlot;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A set of 3D armour: four pieces, each worn in its own slot, each made of
 * parts glued to the bones of the body.
 *
 * <p><strong>A vanilla client cannot draw 3D body armour.</strong> What it
 * draws for worn armour is two flat layers wrapped round a fixed humanoid
 * model, and no resource pack can change that geometry. So a set is shown in
 * two different ways, and the split is the whole design:
 *
 * <ul>
 *   <li><b>The helmet is drawn by the game.</b> Any item in the head slot is
 *       rendered on the head with its model's {@code head} transform, exactly
 *       as a carved pumpkin is. The pack's helmet model is built to that
 *       transform, so it follows the head frame for frame on every client, is
 *       invisible to its wearer in first person, and needs nothing from this
 *       plugin once it is on.</li>
 *   <li><b>Everything below the neck is display entities.</b> The game has no
 *       equivalent slot for a torso or a limb, so each part is an
 *       {@code ItemDisplay} posed every other tick to where the game is
 *       drawing that limb. See {@link WornArmour} and {@link WornPose}.</li>
 * </ul>
 *
 * <p>Pushed by Studio today and by nothing else — see {@code StudioContent}.
 * Art is named the way a pushed pack names art, by a string
 * {@code custom_model_data} on paper, so a set only exists on a server that
 * can read one (1.21.4 and up).
 */
public final class Armor3dSet {

    /** The four pieces, and the slot each one is worn in. */
    public enum Piece {
        HELMET("helmet", EquipmentSlot.HEAD),
        CHESTPLATE("chestplate", EquipmentSlot.CHEST),
        LEGGINGS("leggings", EquipmentSlot.LEGS),
        BOOTS("boots", EquipmentSlot.FEET);

        private final String wire;
        private final EquipmentSlot slot;

        Piece(String wire, EquipmentSlot slot) {
            this.wire = wire;
            this.slot = slot;
        }

        /** The name Studio sends, and the one the commands print. */
        public String wire() {
            return wire;
        }

        public EquipmentSlot slot() {
            return slot;
        }

        public static Optional<Piece> of(String wire) {
            for (Piece piece : values()) {
                if (piece.wire.equalsIgnoreCase(wire)) {
                    return Optional.of(piece);
                }
            }
            return Optional.empty();
        }

        /** What a player calls it: "Helmet". */
        public String label() {
            return Character.toUpperCase(wire.charAt(0)) + wire.substring(1);
        }
    }

    /**
     * One display's worth of a piece.
     *
     * @param bone     which bone it is glued to, in MythicArmors' vocabulary
     *                 ({@code body}, {@code right_arm}, {@code waist},
     *                 {@code left_foot}…) — see {@link WornPose#limbOf}
     * @param model    the {@code custom_model_data} string that draws it
     * @param anchor   where, in the wearer's frame (pixels, feet at 0, +Z the
     *                 front, the right hand at -X), the model's centre sits
     *                 when the body stands still
     * @param rotation a fixed turn about the anchor, degrees, applied X then Y
     *                 then Z as a matrix (so Z first to a vector), or null.
     *                 This is how a cube tilted past what a block model can
     *                 hold is drawn: the model holds it square and this is the
     *                 turn, the same trick a free-rotation rig part plays
     * @param scale    how much the model was shrunk to fit a block model's
     *                 bounds; the display grows it back by the inverse
     */
    public record Part(String bone, String model, float[] anchor, float[] rotation, float scale) {
    }

    /**
     * One piece of the set.
     *
     * @param item  the {@code custom_model_data} string of the item itself —
     *              its inventory icon and, for a helmet with no {@code asset},
     *              what the game draws on the head
     * @param parts the displays it puts on the body, for anybody whose client
     *              does not draw the piece itself; empty for a helmet with no
     *              {@code asset}, which the game draws
     * @param asset the equipment asset the item wears, or null — see
     *              {@link Armor3dSet#drawnBy(int)}
     * @param bedrockSlot the Bedrock item Geyser maps this piece to, or null:
     *              the piece carries {@code rpai_armor_<n>} beside its art and
     *              the Bedrock pack draws item {@code rpai:armor3d_<n>} on the
     *              body. Studio numbers them; see GeyserBridge.
     */
    public record Worn(Piece piece, String item, List<Part> parts, String asset, Integer bedrockSlot) {

        public Worn(Piece piece, String item, List<Part> parts, String asset) {
            this(piece, item, parts, asset, null);
        }
    }

    private final String id;
    private final String name;
    private final Map<Piece, Worn> pieces;
    private final List<int[]> shaderProtocols;

    public Armor3dSet(String id, String name, Map<Piece, Worn> pieces) {
        this(id, name, pieces, List.of());
    }

    /**
     * @param shaderProtocols inclusive protocol ranges whose clients draw the
     *                        set THEMSELVES — see {@link #drawnBy(int)}
     */
    public Armor3dSet(String id, String name, Map<Piece, Worn> pieces, List<int[]> shaderProtocols) {
        this.id = id;
        this.shaderProtocols = List.copyOf(shaderProtocols);
        this.name = name == null || name.isEmpty() ? id : name;
        Map<Piece, Worn> copy = new EnumMap<>(Piece.class);
        copy.putAll(pieces);
        this.pieces = Collections.unmodifiableMap(copy);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Map<Piece, Worn> pieces() {
        return pieces;
    }

    public Optional<Worn> piece(Piece piece) {
        return Optional.ofNullable(pieces.get(piece));
    }

    /**
     * Whether a client speaking {@code protocol} draws this set by itself.
     *
     * <p><strong>The better of the two ways a set is shown, where it is
     * available.</strong> Studio's pack carries, in a version overlay, core
     * shaders that turn the pieces' equipment layers into the set's real
     * geometry on the player's own limbs — drawn by the client as part of the
     * body, so it never lags, not even for its wearer. Only clients whose
     * shaders have been ported get the overlay; everybody else is shown the
     * displays. So this is asked per VIEWER, not per wearer: two people looking
     * at the same player may be looking at two different renderings of the same
     * armour.
     */
    public boolean drawnBy(int protocol) {
        for (int[] range : shaderProtocols) {
            if (range.length == 2 && protocol >= range[0] && protocol <= range[1]) {
                return true;
            }
        }
        return false;
    }

    public List<int[]> shaderProtocols() {
        return shaderProtocols;
    }
}
