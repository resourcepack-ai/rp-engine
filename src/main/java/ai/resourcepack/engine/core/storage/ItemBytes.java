package ai.resourcepack.engine.core.storage;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
 */
public final class ItemBytes {

    /** {@link BukkitObjectOutputStream}: a length, then that many stacks or nulls. */
    public static final byte BUKKIT_STREAM = 1;

    /** What {@link #write} writes. */
    public static final byte CURRENT = BUKKIT_STREAM;

    private ItemBytes() {
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
        try (BukkitObjectInputStream stream = new BukkitObjectInputStream(
                new ByteArrayInputStream(bytes, 1, bytes.length - 1))) {
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
