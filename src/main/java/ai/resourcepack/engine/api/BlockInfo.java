package ai.resourcepack.engine.api;

import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack said a custom block is.
 *
 * <h2>What a custom block actually is</h2>
 *
 * <p>Minecraft has no way to add a block, so a custom block is <strong>a real
 * vanilla block in a state nothing else uses, wearing your model</strong>. The
 * state is what identifies it: a note block has sixteen instruments,
 * twenty-five notes and a powered flag, which is eight hundred combinations
 * that a resource pack can point at eight hundred different models.
 *
 * <p>That is the same trick ItemsAdder, Oraxen and every other plugin doing
 * this uses, because it is the only one there is.
 *
 * <h2>The cost, stated plainly</h2>
 *
 * <ul>
 *   <li><strong>The pool is finite.</strong> Eight hundred blocks per server,
 *       shared with every other plugin doing the same thing. A
 *       {@link ModelInfo placed model} has no pool and no limit, which is why
 *       it is still the right answer for furniture.</li>
 *   <li><strong>The mapping has to be kept.</strong> A block in somebody's
 *       world is a note block in state 412, and if the file saying which id
 *       that was is lost, every one of them becomes a different block. It is
 *       written to {@code blocks.json}, append-only, and never reordered.</li>
 *   <li><strong>Vanilla note blocks are hijacked.</strong> A server with
 *       custom blocks has note blocks that do not play notes, because the pack
 *       has repainted them and the engine stops the game changing their
 *       state.</li>
 * </ul>
 *
 * <p>None of that is a reason not to have the feature — a server that wants
 * ores, machines and blocks you mine needs real blocks, and a display entity
 * cannot be mined. It is a reason to say so out loud.
 */
public final class BlockInfo {

    /** Which vanilla block a custom one is made of. */
    public enum Base {

        /**
         * The note block. Eight hundred states, and the industry standard.
         *
         * <p>Its instrument is normally recomputed from whatever is beneath
         * it, so the engine has to stop the game updating one of ours.
         */
        NOTE_BLOCK,

        /**
         * A mushroom stem. Sixty-four states from six face booleans, and
         * nothing in vanilla ever changes them.
         *
         * <p>Fewer states, but no behaviour to suppress at all — the right
         * choice for a block that should be as inert as possible.
         */
        MUSHROOM_STEM
    }

    private final ContentId id;
    private final Base base;
    private final String model;
    private final float hardness;
    private final String tool;
    private final ContentId drop;
    private final int light;
    private final String sound;

    private BlockInfo(ContentId id, Base base, String model, float hardness,
                      String tool, ContentId drop, int light, String sound) {
        this.id = id;
        this.base = base;
        this.model = model;
        this.hardness = hardness;
        this.tool = tool;
        this.drop = drop;
        this.light = light;
        this.sound = sound;
    }

    /** Engine internal; built by the block loader. */
    public static BlockInfo of(ContentId id, Base base, String model, float hardness,
                               String tool, ContentId drop, int light, String sound) {
        return new BlockInfo(
                Objects.requireNonNull(id, "id"),
                base == null ? Base.NOTE_BLOCK : base,
                model == null ? "" : model,
                hardness,
                tool == null ? "" : tool,
                drop,
                light,
                sound == null ? "" : sound);
    }

    /** Its id, which is also the id of the item that places it. */
    public ContentId id() {
        return id;
    }

    /** Which vanilla block it is made of. */
    public Base base() {
        return base;
    }

    /** The model under {@code assets/models/}, without the extension. */
    public String model() {
        return model;
    }

    /**
     * How long it takes to break, in the same units as vanilla hardness —
     * stone is 1.5, dirt 0.5.
     *
     * <p>Zero means instant, which is what a plant or a decoration wants.
     */
    public float hardness() {
        return hardness;
    }

    /**
     * What has to be held to get the drop: {@code pickaxe}, {@code axe},
     * {@code shovel}, {@code hoe}, or empty for anything.
     */
    public Optional<String> tool() {
        return tool.isEmpty() ? Optional.empty() : Optional.of(tool);
    }

    /** What breaking it gives back, or empty for the block itself. */
    public Optional<ContentId> drop() {
        return Optional.ofNullable(drop);
    }

    /** How much light it gives off, 0 to 15. */
    public int light() {
        return light;
    }

    /**
     * The sound it makes, as a vanilla sound group: {@code stone},
     * {@code wood}, {@code metal}, {@code glass}, {@code wool}.
     *
     * <p>Empty leaves the base block's own, which is a note block's wooden
     * one. Worth setting on anything that is meant to be stone.
     */
    public Optional<String> sound() {
        return sound.isEmpty() ? Optional.empty() : Optional.of(sound);
    }

    @Override
    public String toString() {
        return id + " (" + base + ")";
    }
}
