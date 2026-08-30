package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * A number for every content id, on the versions that address models by
 * number instead of by name.
 *
 * <p>This is the thing the id scheme exists to not need. On 1.21.4 and up an
 * item's id <em>is</em> its model reference and nothing is allocated; below
 * that the game matches a model by an integer, so the integers have to come
 * from somewhere and — this is the whole difficulty — they have to stay put.
 *
 * <p><b>What goes wrong if they move.</b> The number is not only written into
 * the pack. It is on every stack of that item already in a player's chest,
 * and it is what a placed model's part item carries. Renumber between two
 * starts and the pack now says 7 is a ruby while a thousand stacks in the
 * world still say 4: every one of them silently becomes a different item, or
 * an invisible one. There is no error and nothing to migrate against, because
 * those stacks are in region files. So the file this writes is not a cache and
 * cannot be regenerated — deleting it is a destructive act on a live server,
 * which is why it says so in its own contents.
 *
 * <p>Three rules follow, and they are the whole design:
 *
 * <ul>
 *   <li><b>Assignment is permanent.</b> An id that has been given a number
 *       keeps it, for the life of the server, whether or not the content is
 *       still there.</li>
 *   <li><b>Numbers are never reused.</b> Content that is deleted keeps its
 *       entry rather than freeing it, because the item is still in somebody's
 *       inventory and handing its number to something else is how one item
 *       turns into another.</li>
 *   <li><b>Allocation is in sorted id order.</b> Two servers building the same
 *       content folder from empty land on the same numbers, so a pack built on
 *       a test server is the pack that works on the live one. It is not a
 *       guarantee — anything allocated before diverges — but it costs nothing
 *       and it makes the common case reproducible.</li>
 * </ul>
 *
 * <p>Not thread safe, and deliberately not: it is touched during a content
 * load and a build, both of which are the main thread, and a lock here would
 * suggest it is safe to call from somewhere it is not.
 */
public final class ModelNumbers {

    /**
     * The first number handed out.
     *
     * <p>1 rather than 0 because 0 is what an item with no custom model data
     * at all reads as through some of the APIs that touch this, and a real
     * assignment indistinguishable from an absent one is a bug waiting for the
     * first person to write the shorter check.
     */
    static final int FIRST = 1;

    /** What the file says it is, so somebody who finds it does not delete it. */
    private static final String WARNING =
            "Numbers assigned to content ids for Minecraft versions older than 1.21.4. "
                    + "DO NOT DELETE OR EDIT: these numbers are on items in your players "
                    + "inventories and on models placed in your world. Changing them turns "
                    + "those items into different items, and there is no way to undo it. "
                    + "Servers on 1.21.4 or newer do not use this file at all.";

    /** The JSON shape on disk, and the only thing gson ever sees. */
    static final class Saved {
        String readme;
        Map<String, Integer> numbers;
        int next;
    }

    private final File file;
    private final Gson gson = new Gson();
    private final Map<String, Integer> numbers = new HashMap<>();
    private int next = FIRST;
    private boolean dirty;

    public ModelNumbers(File dataFolder) {
        this.file = new File(dataFolder, "model-numbers.json");
    }

    /**
     * The number for an id, assigning one if it has never had one.
     *
     * <p>The only way in. There is deliberately no release, no renumber and no
     * way to set one by hand.
     */
    public int of(ContentId id) {
        String key = id.toString();
        Integer existing = numbers.get(key);
        if (existing != null) {
            return existing;
        }
        int assigned = next++;
        numbers.put(key, assigned);
        dirty = true;
        return assigned;
    }

    /** The number an id already has, or empty if it has never been asked for. */
    public Optional<Integer> existing(ContentId id) {
        return Optional.ofNullable(numbers.get(id.toString()));
    }

    /**
     * Assigns numbers to a whole set of ids at once, in sorted order.
     *
     * <p>The reproducibility rule lives here rather than in {@link #of},
     * because {@code of} is called in whatever order a build happens to reach
     * things and that order is not worth constraining. A load hands everything
     * it found to this first, and after that {@code of} only ever finds them.
     */
    public void assignAll(Iterable<ContentId> ids) {
        TreeMap<String, ContentId> sorted = new TreeMap<>();
        for (ContentId id : ids) {
            sorted.put(id.toString(), id);
        }
        for (ContentId id : sorted.values()) {
            of(id);
        }
    }

    /** How many ids have ever been given a number. */
    public int size() {
        return numbers.size();
    }

    /** Every assignment, id to number, for a status command or a test. */
    public Map<String, Integer> all() {
        return Collections.unmodifiableMap(new TreeMap<>(numbers));
    }

    /**
     * Reads the file, or throws if there is one and it cannot be read.
     *
     * <p>Throwing is the point. Continuing with an empty map over a file that
     * exists is precisely how every number moves at once, and it would look
     * like a clean start rather than a disaster.
     */
    public void load(Logger logger) {
        if (!file.exists()) {
            return;
        }
        Saved saved;
        try {
            saved = gson.fromJson(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
                    Saved.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Could not read " + file.getName() + ", which holds the numbers your "
                            + "existing custom items are identified by. Refusing to allocate "
                            + "new ones over it, because that would change what every item "
                            + "already in your world is. Restore the file or move it aside "
                            + "deliberately.", e);
        }
        if (saved == null || saved.numbers == null) {
            return;
        }
        numbers.putAll(saved.numbers);
        int highest = FIRST - 1;
        for (Integer value : saved.numbers.values()) {
            if (value != null && value > highest) {
                highest = value;
            }
        }
        // The counter is recovered from the entries rather than trusted from
        // the file. A next that has been edited down, or written by a build
        // that saved before it, would hand out a number something else is
        // already using — the one failure this class exists to make
        // impossible, so it is not left to a field.
        next = Math.max(saved.next, highest + 1);
        dirty = false;
    }

    /** Writes only when something was actually assigned. */
    public void save(Logger logger) {
        if (!dirty) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("Couldn't create " + parent + " to persist model numbers");
                return;
            }
            Saved saved = new Saved();
            saved.readme = WARNING;
            // Sorted, so a diff of this file between two starts shows what was
            // added rather than a reshuffle of the whole thing.
            saved.numbers = new LinkedHashMap<>(new TreeMap<>(numbers));
            saved.next = next;
            Files.write(file.toPath(), gson.toJson(saved).getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (IOException e) {
            logger.warning("Couldn't persist model-numbers.json: " + e.getMessage());
        }
    }
}
