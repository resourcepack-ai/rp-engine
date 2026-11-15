package ai.resourcepack.engine.api;

import org.bukkit.block.Block;

import java.util.Collection;
import java.util.Optional;

/**
 * Custom blocks: the ones a content folder's {@code blocks/} declares. Ask
 * which one stands somewhere, put one down, change the state it is in.
 *
 * <p>A custom block is a real block in the world, one of the spare states of a
 * vanilla block the pack redraws, so everything Bukkit says about a block
 * holds for it: {@code BlockPlaceEvent} and {@code BlockBreakEvent} fire for it
 * like any other, and {@link #at} is how a listener to either learns that the
 * block is one of ours. There is no separate pair of events on purpose, so a
 * protection plugin that has never heard of RP Engine already protects them.
 *
 * <p>Every method that takes a block is main thread only, or on Folia the
 * thread that owns that block.
 */
public interface Blocks {

    /** Every custom block id, sorted. */
    Collection<ContentId> ids();

    /** What one is: its shape, its states, its hardness and tool. */
    Optional<BlockInfo> info(ContentId id);

    /** The custom block standing here, or empty for anything else. */
    Optional<BlockInfo> at(Block block);

    /** Its id, or empty for anything that is not one of ours. */
    default Optional<ContentId> idAt(Block block) {
        return at(block).map(BlockInfo::id);
    }

    /** Whether the block standing here is this one. */
    default boolean is(Block block, ContentId id) {
        return id != null && idAt(block).filter(id::equals).isPresent();
    }

    /**
     * The state the block standing here is in, as the pack writes one
     * ({@code facing=north,lit=true}); the empty string for a block with one
     * state, and for a shaped one (a stair, a slab, a door), whose state is the
     * game's own block data. Empty for anything that is not one of ours.
     */
    Optional<String> stateAt(Block block);

    /**
     * Puts a custom block here, in the state it is placed in, replacing
     * whatever stands there. No event is fired, no item is taken, no sound is
     * played and the neighbours are not updated: this is {@code setType}, for
     * a plugin that has already decided.
     *
     * @return false when there is no such block, or the pack has no state left for it
     */
    boolean place(Block where, ContentId id);

    /**
     * Puts the custom block standing here into another of its states: a lamp
     * switched on, a crop grown one stage. {@code state} is written as
     * {@link #stateAt} answers it, and {@link BlockInfo#with} changes one
     * property of one.
     *
     * @return false when nothing of ours stands there, it is a shaped block, or it has no such state
     */
    boolean setState(Block where, String state);

    /**
     * Sets one property of the custom block standing here and leaves the rest:
     * {@code setProperty(block, "lit", "true")}.
     *
     * @return false as {@link #setState} does
     */
    default boolean setProperty(Block where, String property, String value) {
        Optional<BlockInfo> block = at(where);
        Optional<String> state = stateAt(where);
        if (block.isEmpty() || state.isEmpty() || property == null || value == null) {
            return false;
        }
        return setState(where, block.get().with(state.get(), property, value));
    }
}
