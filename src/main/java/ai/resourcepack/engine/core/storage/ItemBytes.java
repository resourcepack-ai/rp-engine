package ai.resourcepack.engine.core.storage;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.Serializable;
import java.util.Set;

/**
 * Item stacks to bytes and back, for keeping a container's contents in
 * persistent data.
 *
 * <p><strong>The first byte says which codec wrote the rest</strong>, and that
 * byte is the whole reason this class exists rather than a stream opened inline.
 * Bukkit's object streams are the one codec that works on every server from
 * 1.19.4 up, Spigot and Paper alike, and Paper has deprecated them in favour of
 * its own {@code serializeAsBytes}. When that day comes the better codec goes in
 * as format 2 beside this one, and every chest already standing in a world —
 * written in format 1 — still opens. Without the byte, the only way to tell the
 * two apart would be to try one and catch the other, which is a guess about
 * somebody's items.
 *
 * <p><strong>A read that fails throws, and never answers empty.</strong> An
 * unreadable chest opened as an empty one would be saved back as an empty one
 * the moment it closed, and the items would be gone with nothing in any log.
 * The caller refuses to open it instead, and the bytes stay where they are for
 * a newer engine (or a person) to deal with.
 *
 * <p><strong>A read only builds the classes an item is made of.</strong> The
 * bytes live in persistent data, and a player in creative mode can put whatever
 * bytes they like on an item, so an unfiltered object stream here would build
 * any serialisable class on the server's classpath. {@link #FILTER} admits what
 * Bukkit's stream writes for a stack (its {@code Wrapper}, the maps, lists and
 * boxed values a {@code serialize()} returns, and Guava's immutable collections,
 * which is what the wrapper's map is), plus what those resolve to as they are
 * read (the stack and its meta), and refuses everything else. A refusal is
 * an {@link IOException} like any other unreadable container: the container
 * does not open, and the bytes are kept.
 */
public final class ItemBytes {

    /** {@link BukkitObjectOutputStream}: a length, then that many stacks or nulls. */
    public static final byte BUKKIT_STREAM = 1;

    /** What {@link #write} writes. */
    public static final byte CURRENT = BUKKIT_STREAM;

    /** Exact classes a stored stack may contain, besides those {@link #allowed} takes by rule. */
    private static final Set<String> ALLOWED = Set.of(
            "org.bukkit.util.io.Wrapper",
            "java.lang.Object", "java.lang.String", "java.lang.Number", "java.lang.Boolean",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
            "java.lang.Float", "java.lang.Double", "java.lang.Character", "java.lang.Enum",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.Arrays$ArrayList",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.EnumMap", "java.util.EnumSet$SerializationProxy",
            "java.util.RegularEnumSet", "java.util.JumboEnumSet",
            "java.util.CollSer", "java.util.UUID",
            // What HashMap and HashSet size their table as while they read.
            "java.util.Map$Entry");

    /** Nesting, object and array limits for a read: generous for a chest of books, small for a bomb. */
    private static final long MAX_DEPTH = 32;
    private static final long MAX_REFS = 1_000_000;
    private static final long MAX_ARRAY = 1_000_000;

    /** What every read is held to; see the class note. */
    static final ObjectInputFilter FILTER = info -> {
        if (info.depth() > MAX_DEPTH || info.references() > MAX_REFS || info.arrayLength() > MAX_ARRAY) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> type = info.serialClass();
        if (type == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }
        return allowed(type) ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
    };

    private ItemBytes() {
    }

    /** Whether a stored stack may contain an object of {@code type}. */
    static boolean allowed(Class<?> type) {
        while (type.isArray()) {
            type = type.getComponentType();
        }
        if (type.isPrimitive() || type.isEnum()) {
            // An enum constant is read by name, never constructed.
            return true;
        }
        String name = type.getName();
        if (ALLOWED.contains(name)) {
            return true;
        }
        // What a Wrapper resolves to: the stack itself, its meta, a colour, a
        // pattern. Bukkit builds these from the wrapper's map through their own
        // deserialize, and the result is checked as it comes back. One that is
        // not Serializable cannot be read from the stream directly, so this
        // admits only what the wrapper built.
        if (ConfigurationSerializable.class.isAssignableFrom(type) && !Serializable.class.isAssignableFrom(type)) {
            return true;
        }
        // The JDK's own unmodifiable and immutable collections (List.of,
        // Collections.unmodifiableMap and the like), whose classes are private.
        // A class a readResolve answers with is checked as well as the one that
        // was read, which is why the EnumSet and Guava results are here too.
        if (name.startsWith("java.util.Collections$Unmodifiable")
                || name.startsWith("java.util.Collections$Singleton")
                || name.startsWith("java.util.Collections$Empty")
                || name.startsWith("java.util.ImmutableCollections$")) {
            return true;
        }
        // Guava's immutable collections, which is what a stack's serialised map
        // is. Each writes itself as a SerializedForm and builds itself back
        // through its builder; the collection that comes back is checked too,
        // as what the form resolved to. Those classes refuse to be read any
        // other way, so admitting them admits only what the forms build.
        if (!name.startsWith("com.google.common.collect.")) {
            return false;
        }
        if (name.contains("SerializedForm")) {
            return true;
        }
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            String superName = at.getName();
            if (superName.equals("com.google.common.collect.ImmutableCollection")
                    || superName.equals("com.google.common.collect.ImmutableMap")) {
                return true;
            }
        }
        return false;
    }

    /** A Bukkit object stream over {@code in}, held to {@link #FILTER} before anything is read. */
    static BukkitObjectInputStream open(InputStream in) throws IOException {
        BukkitObjectInputStream stream = new BukkitObjectInputStream(in);
        stream.setObjectInputFilter(FILTER);
        return stream;
    }

    /**
     * The contents as bytes, empty slots and all, so a slot an item was put in
     * is the slot it comes back in.
     */
    public static byte[] write(ItemStack[] contents) throws IOException {
        ItemStack[] slots = contents == null ? new ItemStack[0] : contents;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(CURRENT);
        try (BukkitObjectOutputStream stream = new BukkitObjectOutputStream(out)) {
            stream.writeInt(slots.length);
            for (ItemStack slot : slots) {
                stream.writeObject(slot == null || slot.getType().isAir() ? null : slot);
            }
        }
        return out.toByteArray();
    }

    /**
     * The contents back.
     *
     * @return an empty array for null or no bytes, which is a container nothing
     *         has been put in yet
     * @throws IOException for a format this engine does not know, or bytes that
     *                     do not read as one. Never treat this as empty: see the
     *                     class note
     */
    public static ItemStack[] read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return new ItemStack[0];
        }
        byte format = bytes[0];
        if (format != BUKKIT_STREAM) {
            throw new IOException("stored with item format " + format
                    + ", which this version of RP Engine does not read (it knows " + BUKKIT_STREAM
                    + "). A newer RP Engine wrote it; the items are untouched.");
        }
        try (BukkitObjectInputStream stream = open(new ByteArrayInputStream(bytes, 1, bytes.length - 1))) {
            int length = stream.readInt();
            if (length < 0 || length > 54 * 4) {
                throw new IOException("stored contents claim " + length + " slots");
            }
            ItemStack[] slots = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                Object read = stream.readObject();
                if (read != null && !(read instanceof ItemStack)) {
                    throw new IOException("slot " + i + " holds a " + read.getClass().getSimpleName()
                            + ", not an item");
                }
                slots[i] = (ItemStack) read;
            }
            return slots;
        } catch (ClassNotFoundException e) {
            throw new IOException("stored contents name a class this server does not have", e);
        }
    }

    /** Whether there is anything at all in {@code contents}. */
    public static boolean anything(ItemStack[] contents) {
        if (contents == null) {
            return false;
        }
        for (ItemStack slot : contents) {
            if (slot != null && !slot.getType().isAir() && slot.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }
}
