package ai.resourcepack.engine.core.storage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format byte, and the rule that an unreadable container is never an empty
 * one. A real stack needs a server to serialise, so these use empty slots, which
 * is enough to pin everything about the framing.
 */
class ItemBytesTest {

    @Test
    void theFirstByteSaysWhichCodecWroteTheRest() throws IOException {
        byte[] bytes = ItemBytes.write(new ItemStack[4]);

        assertEquals(ItemBytes.BUKKIT_STREAM, bytes[0]);
    }

    @Test
    void emptySlotsComeBackAsEmptySlotsInTheSamePlaces() throws IOException {
        ItemStack[] back = ItemBytes.read(ItemBytes.write(new ItemStack[27]));

        assertEquals(27, back.length, "a slot an item was put in is the slot it comes back in");
        assertArrayEquals(new ItemStack[27], back);
    }

    @Test
    void nothingStoredIsAnEmptyContainer() throws IOException {
        assertEquals(0, ItemBytes.read(null).length);
        assertEquals(0, ItemBytes.read(new byte[0]).length);
    }

    @Test
    void aFormatThisEngineDoesNotKnowIsRefusedRatherThanReadAsEmpty() throws IOException {
        byte[] written = ItemBytes.write(new ItemStack[3]);
        written[0] = 2;

        // Read as empty, this would be saved back empty the moment it closed.
        IOException refused = assertThrows(IOException.class, () -> ItemBytes.read(written));
        assertTrue(refused.getMessage().contains("format 2"), refused.getMessage());
    }

    @Test
    void bytesThatAreNotAStreamAreRefused() {
        assertThrows(IOException.class, () -> ItemBytes.read(new byte[]{ItemBytes.BUKKIT_STREAM, 1, 2, 3}));
    }

    @Test
    void anythingIsFalseForNothing() {
        assertFalse(ItemBytes.anything(null));
        assertFalse(ItemBytes.anything(new ItemStack[9]));
    }

    // A real stack needs a server to serialise (its data version comes from
    // one), so the filter is held to a stand-in with the same shape: a
    // ConfigurationSerializable whose map holds another, with the boxed values,
    // lists and maps an ItemMeta's serialize() returns. Bukkit's stream turns
    // each into its Wrapper around a Guava ImmutableMap, exactly as for a stack.

    @Test
    void whatAStackIsMadeOfGetsThrough() throws Exception {
        ConfigurationSerialization.registerClass(FakeStack.class);
        ConfigurationSerialization.registerClass(FakeMeta.class);
        Map<String, Object> pdc = new HashMap<>();
        pdc.put("rpengine:backpack", "abc");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("display-name", "{\"text\":\"Bag\"}");
        meta.put("lore", ImmutableList.of("one", "two"));
        meta.put("enchants", ImmutableMap.of("DURABILITY", 3));
        meta.put("flags", new ArrayList<>(List.of("HIDE_ENCHANTS")));
        meta.put("unbreakable", true);
        meta.put("amount", 1.5d);
        meta.put("PublicBukkitValues", pdc);
        meta.put("list-of", List.of(1L, (short) 2, (byte) 3));
        meta.put("set", java.util.EnumSet.of(java.util.concurrent.TimeUnit.SECONDS));
        meta.put("big", ImmutableMap.of("a", 1, "b", 2, "c", ImmutableList.of(new FakeMeta(Map.of("x", 'y')))));
        Map<String, Object> stack = new LinkedHashMap<>();
        stack.put("v", 4189);
        stack.put("type", "DIAMOND_SWORD");
        stack.put("meta", new FakeMeta(meta));
        FakeStack written = new FakeStack(stack);

        Object back = readThroughFilter(written);

        assertEquals(written, back);
    }

    @Test
    void anyOtherClassIsRefused() throws IOException {
        IOException refused = assertThrows(IOException.class, () -> readThroughFilter(new NotAnItem()));
        assertTrue(refused.getMessage().contains("REJECTED"), refused.getMessage());
    }

    @Test
    void aStoredContainerHoldingAnyOtherClassDoesNotOpen() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ItemBytes.BUKKIT_STREAM);
        try (BukkitObjectOutputStream stream = new BukkitObjectOutputStream(out)) {
            stream.writeInt(1);
            stream.writeObject(new NotAnItem());
        }

        assertThrows(IOException.class, () -> ItemBytes.read(out.toByteArray()));
    }

    @Test
    void nestingPastTheLimitIsRefused() {
        List<Object> deep = new ArrayList<>();
        List<Object> at = deep;
        for (int i = 0; i < 64; i++) {
            List<Object> next = new ArrayList<>();
            at.add(next);
            at = next;
        }

        assertThrows(IOException.class, () -> readThroughFilter((Serializable) deep));
    }

    @Test
    void theRuleUnwrapsArraysAndKnowsEnums() {
        assertTrue(ItemBytes.allowed(Object[].class));
        assertTrue(ItemBytes.allowed(byte[].class));
        assertTrue(ItemBytes.allowed(String[][].class));
        assertTrue(ItemBytes.allowed(java.util.concurrent.TimeUnit.class));
        assertTrue(ItemBytes.allowed(ItemStack.class), "what a Wrapper resolves to");
        assertTrue(ItemBytes.allowed(org.bukkit.Color.class));
        assertFalse(ItemBytes.allowed(java.io.File.class));
        assertFalse(ItemBytes.allowed(NotAnItem[].class));
        assertTrue(ItemBytes.allowed(ImmutableList.of("a", "b").getClass()), "what a Guava form resolves to");
        assertFalse(ItemBytes.allowed(com.google.common.collect.ArrayListMultimap.class));
    }

    private static Object readThroughFilter(Object written) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream stream = new BukkitObjectOutputStream(out)) {
            stream.writeObject(written);
        }
        try (ObjectInputStream in = ItemBytes.open(new ByteArrayInputStream(out.toByteArray()))) {
            return in.readObject();
        }
    }

    static final class NotAnItem implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public static final class FakeStack implements ConfigurationSerializable {
        private final Map<String, Object> map;

        public FakeStack(Map<String, Object> map) {
            this.map = new LinkedHashMap<>(map);
            this.map.remove(ConfigurationSerialization.SERIALIZED_TYPE_KEY);
        }

        @Override
        public Map<String, Object> serialize() {
            return map;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FakeStack && ((FakeStack) other).map.equals(map);
        }

        @Override
        public int hashCode() {
            return map.hashCode();
        }
    }

    public static final class FakeMeta implements ConfigurationSerializable {
        private final Map<String, Object> map;

        public FakeMeta(Map<String, Object> map) {
            this.map = new LinkedHashMap<>(map);
            this.map.remove(ConfigurationSerialization.SERIALIZED_TYPE_KEY);
        }

        @Override
        public Map<String, Object> serialize() {
            return map;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FakeMeta && ((FakeMeta) other).map.equals(map);
        }

        @Override
        public int hashCode() {
            return map.hashCode();
        }
    }
}
