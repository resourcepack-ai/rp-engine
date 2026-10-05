package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.ContentId;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Which vanilla block state each custom block is.
 *
 * <p><strong>This file is the one thing in the plugin that must never be lost
 * or reordered.</strong> A custom block standing in somebody's world is a note
 * block in a particular state; this says which id that state was. Lose it and
 * every custom block on the server becomes a different block — not broken, not
 * missing, but visibly the wrong thing, in a way no reload can repair.
 *
 * <p>So: <strong>append-only</strong>. An id gets a number the first time it is
 * seen and keeps it for ever. A block deleted from a pack keeps its number
 * reserved rather than freeing it, because the world may still be full of them
 * and a pack is often deleted by accident. Numbers are handed out in the order
 * ids are first seen, never sorted, because sorting is exactly the thing that
 * would renumber everybody when a pack is added.
 *
 * <p>This is the file the item scheme was designed to avoid — an id-to-number
 * mapping that has to survive for ever. It exists here because a custom block
 * genuinely cannot work without one: the number is IN the world, in every
 * chunk somebody has built with.
 */
public final class BlockStates {

    /**
     * The instruments a note block can be in, minus the ones the game picks
     * from a mob head above it.
     *
     * <p>Order is fixed for ever: it is what turns a number into a state.
     */
    private static final List<String> INSTRUMENTS = List.of(
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar",
            "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit",
            "banjo", "pling");

    /** Notes per instrument. */
    private static final int NOTES = 25;

    /**
     * How many identities a note block has: note and powered, and NOT the
     * instrument.
     *
     * <p>This is the whole design, and it was learned the hard way. A note
     * block recomputes its instrument from the block beneath it whenever that
     * block changes, and <strong>cancelling {@code BlockPhysicsEvent} does not
     * stop it</strong> on 1.21.8 — the recompute happens in the block's own
     * shape update, which no event can refuse. A custom block identified by
     * its instrument therefore turned into a different custom block the moment
     * somebody put hay under it, which the integration harness caught by doing
     * exactly that.
     *
     * <p>So the instrument is not part of the identity. Every instrument for a
     * given note and powered flag points at the same model, and the game may
     * change it as often as it likes without changing what the block is. The
     * cost is the pool: fifty rather than eight hundred per base, which is why
     * {@link BlockInfo.Base#MUSHROOM_STEM} is there and why a server with
     * hundreds of blocks should use it.
     */
    private static final int NOTE_IDENTITIES = NOTES * 2;

    /** Faces of a mushroom stem, in the order the state string writes them. */
    private static final List<String> FACES = List.of("north", "east", "south", "west", "up", "down");

    /**
     * The first note-block state, which is what a player placing an ordinary
     * note block gets. Never handed out, so a vanilla note block stays one.
     */
    private static final int FIRST = 1;

    /** What the file holds. A wrapper so a later field has somewhere to go. */
    private static final class Saved {
        Map<String, Integer> noteBlock;
        Map<String, Integer> mushroomStem;
    }

    private final Gson gson = new Gson();
    private final File file;

    /** id -> state number, per base. Insertion-ordered, and never re-sorted. */
    private final Map<String, Integer> noteBlock = new LinkedHashMap<>();
    private final Map<String, Integer> mushroomStem = new LinkedHashMap<>();

    public BlockStates(File dataFolder) {
        this.file = new File(dataFolder, "blocks.json");
    }

    /** How many blocks a base can hold, minus the one state left to vanilla. */
    public static int capacity(BlockInfo.Base base) {
        return (base == BlockInfo.Base.MUSHROOM_STEM ? 64 : NOTE_IDENTITIES) - FIRST;
    }

    /**
     * The state a block is, allocating one if this is the first time.
     *
     * @return empty only when the base has run out of states
     */
    public Optional<Integer> numberFor(BlockInfo block) {
        Map<String, Integer> allocated = mapFor(block.base());
        String id = block.id().toString();
        Integer already = allocated.get(id);
        if (already != null) {
            return Optional.of(already);
        }
        int next = FIRST + allocated.size();
        if (next >= capacity(block.base()) + FIRST) {
            return Optional.empty();
        }
        allocated.put(id, next);
        return Optional.of(next);
    }

    /** What a block was allocated, without allocating one. */
    public Optional<Integer> existing(BlockInfo block) {
        return Optional.ofNullable(mapFor(block.base()).get(block.id().toString()));
    }

    /** Which block a state belongs to, for a block being broken or clicked. */
    public Optional<ContentId> at(BlockInfo.Base base, int number) {
        for (Map.Entry<String, Integer> entry : mapFor(base).entrySet()) {
            if (entry.getValue() == number) {
                return ContentId.parse(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** Every id allocated on a base, in the order they were allocated. */
    public Map<String, Integer> allocated(BlockInfo.Base base) {
        return Map.copyOf(mapFor(base));
    }

    /** How many are left. */
    public int remaining(BlockInfo.Base base) {
        return capacity(base) - mapFor(base).size();
    }

    private Map<String, Integer> mapFor(BlockInfo.Base base) {
        return base == BlockInfo.Base.MUSHROOM_STEM ? mushroomStem : noteBlock;
    }

    // ---- turning a number into a state ----------------------------------

    /**
     * What identifies a block, as a blockstate fragment.
     *
     * <p>For a note block this is the note and the powered flag and nothing
     * else — see {@link #NOTE_IDENTITIES}. Notes are handed out before powered
     * ones, so the first twenty-four blocks a server defines are the half
     * redstone has no opinion about.
     */
    public static String identityOf(BlockInfo.Base base, int number) {
        if (base == BlockInfo.Base.MUSHROOM_STEM) {
            StringBuilder state = new StringBuilder();
            for (int i = 0; i < FACES.size(); i++) {
                state.append(i == 0 ? "" : ",").append(FACES.get(i)).append('=')
                        .append((number >> i & 1) == 1);
            }
            return state.toString();
        }
        return "note=" + number % NOTES + ",powered=" + (number / NOTES > 0);
    }

    /**
     * Every full blockstate that means this block, for the resource pack.
     *
     * <p>Sixteen for a note block, one per instrument, all pointing at the
     * same model — which is what makes the game's own instrument changes
     * invisible.
     */
    public static List<String> statesFor(BlockInfo.Base base, int number) {
        if (base == BlockInfo.Base.MUSHROOM_STEM) {
            return List.of(identityOf(base, number));
        }
        List<String> states = new ArrayList<>();
        for (String instrument : INSTRUMENTS) {
            states.add("instrument=" + instrument + "," + identityOf(base, number));
        }
        return states;
    }

    /** Every state of a base, for writing a variants map that covers them all. */
    public static List<String> everyState(BlockInfo.Base base) {
        List<String> all = new ArrayList<>();
        for (int number = 0; number < capacity(base) + FIRST; number++) {
            all.addAll(statesFor(base, number));
        }
        return all;
    }

    /**
     * The identity of a block standing in the world, from its own data.
     *
     * <p>Bukkit writes {@code minecraft:note_block[instrument=harp,note=1,
     * powered=false]}; this is the part of that which says what the block is.
     */
    public static String identityOfData(BlockInfo.Base base, String data) {
        int open = data.indexOf('[');
        int close = data.lastIndexOf(']');
        String inside = open < 0 || close < open ? "" : data.substring(open + 1, close);
        if (base == BlockInfo.Base.MUSHROOM_STEM) {
            return inside;
        }
        StringBuilder identity = new StringBuilder();
        for (String part : inside.split(",")) {
            if (part.startsWith("note=") || part.startsWith("powered=")) {
                identity.append(identity.length() == 0 ? "" : ",").append(part);
            }
        }
        return identity.toString();
    }

    // ---- the file --------------------------------------------------------

    /** Reads what was saved. A missing file is a server with no blocks yet. */
    public void load(Logger logger) {
        if (!file.isFile()) {
            return;
        }
        try {
            Saved saved = gson.fromJson(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), Saved.class);
            if (saved == null) {
                return;
            }
            copyInto(saved.noteBlock, noteBlock);
            copyInto(saved.mushroomStem, mushroomStem);
        } catch (IOException | JsonSyntaxException e) {
            // Deliberately loud, and deliberately not overwritten. Every custom
            // block in every world depends on this file, so a server owner has
            // to see this rather than find out by walking past a wall that used
            // to be one thing and is now another.
            logger.severe("blocks.json could not be read: " + e.getMessage());
            logger.severe("Custom blocks already placed in your worlds will be MISREAD until it is."
                    + " Stop the server and restore the file rather than letting it be rewritten.");
        }
    }

    private static void copyInto(Map<String, Integer> from, Map<String, Integer> to) {
        if (from == null) {
            return;
        }
        // Order matters and a JSON object preserves it, so this is a straight
        // copy rather than anything that sorts.
        to.putAll(from);
    }

    /** Writes them out. Called whenever an allocation happened. */
    public void save(Logger logger) {
        Saved saved = new Saved();
        saved.noteBlock = new LinkedHashMap<>(noteBlock);
        saved.mushroomStem = new LinkedHashMap<>(mushroomStem);
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), gson.toJson(saved).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.severe("Could not write blocks.json: " + e.getMessage()
                    + ". Custom blocks placed from now on may not survive a restart.");
        }
    }
}
