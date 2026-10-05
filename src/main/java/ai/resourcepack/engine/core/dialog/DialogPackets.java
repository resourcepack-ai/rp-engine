package ai.resourcepack.engine.core.dialog;

import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Optional Mojang-mapped runtime bridge. The server's own codec reads the JSON
 * and openDialog sends its direct holder in the show-dialog packet. No registry
 * mutation, command escaping, datapack reload, or copy of the dialog schema.
 * Kept behind reflection so older servers and Spigot retain the command path.
 */
public final class DialogPackets {
    private static Bridge bridge;
    private static boolean probed;

    /**
     * What the server's codec said about the last dialog {@link #show} could
     * not decode, or null. The ONE useful line when a dialog is refused: the
     * routes after this one fail too, and the last of them (by name) can only
     * say the id is not in the registry, which reads like the problem and is
     * not. Main thread, like every show.
     */
    private static String lastRefusal;

    /** See {@link #lastRefusal}: the codec's own words about the last dialog it would not read. */
    public static String lastRefusal() {
        return lastRefusal;
    }

    private DialogPackets() {}

    private record Bridge(Object codec, Object ops, Method parse, Method result,
                          Method direct, Class<?> holder) {}

    private static synchronized Bridge bridge() {
        if (probed) return bridge;
        probed = true;
        try {
            Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps");
            Class<?> codecClass = Class.forName("com.mojang.serialization.Codec");
            Class<?> provider = Class.forName("net.minecraft.core.HolderLookup$Provider");
            Class<?> holder = Class.forName("net.minecraft.core.Holder");
            Object registries = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".CraftRegistry")
                    .getMethod("getMinecraftRegistry").invoke(null);
            Object jsonOps = Class.forName("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
            Object ops = Class.forName("net.minecraft.resources.RegistryOps")
                    .getMethod("create", dynamicOps, provider).invoke(null, jsonOps, registries);
            Object codec = Class.forName("net.minecraft.server.dialog.Dialog").getField("DIRECT_CODEC").get(null);
            bridge = new Bridge(codec, ops, codecClass.getMethod("parse", dynamicOps, Object.class),
                    Class.forName("com.mojang.serialization.DataResult").getMethod("getOrThrow"),
                    holder.getMethod("direct", Object.class), holder);
        } catch (ReflectiveOperationException | LinkageError e) {
            Bukkit.getLogger().info("[RPEngine] Direct dialog delivery unavailable; using inline commands ("
                    + e.getClass().getSimpleName() + ").");
        }
        return bridge;
    }

    /** Also used by the server integration test to validate the actual codec. */
    public static Object decode(String json) throws ReflectiveOperationException {
        Bridge b = bridge();
        if (b == null) throw new IllegalStateException("Direct dialog delivery unavailable");
        Object result = b.parse.invoke(b.codec, b.ops, JsonParser.parseString(json));
        return b.direct.invoke(null, b.result.invoke(result));
    }

    private record ItemBridge(Method asNmsCopy, Object codec, Method encodeStart, Method result, Object ops) {}

    private static ItemBridge itemBridge;
    private static boolean itemProbed;

    private static synchronized ItemBridge itemBridge() {
        if (itemProbed) return itemBridge;
        itemProbed = true;
        try {
            String craft = Bukkit.getServer().getClass().getPackageName();
            Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps");
            Class<?> provider = Class.forName("net.minecraft.core.HolderLookup$Provider");
            Object registries = Class.forName(craft + ".CraftRegistry").getMethod("getMinecraftRegistry").invoke(null);
            Object jsonOps = Class.forName("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
            Object ops = Class.forName("net.minecraft.resources.RegistryOps")
                    .getMethod("create", dynamicOps, provider).invoke(null, jsonOps, registries);
            Object codec = Class.forName("net.minecraft.world.item.ItemStack").getField("CODEC").get(null);
            itemBridge = new ItemBridge(
                    Class.forName(craft + ".inventory.CraftItemStack").getMethod("asNMSCopy", org.bukkit.inventory.ItemStack.class),
                    codec,
                    Class.forName("com.mojang.serialization.Encoder").getMethod("encodeStart", dynamicOps, Object.class),
                    Class.forName("com.mojang.serialization.DataResult").getMethod("getOrThrow"),
                    ops);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Bukkit.getLogger().fine("[RPEngine] Item tooltips in dialogs fall back to name and lore ("
                    + e.getClass().getSimpleName() + ").");
        }
        return itemBridge;
    }

    /**
     * An item stack as the game writes one — {@code id}, {@code count} and every
     * {@code components} entry — or null where the server's codec cannot be
     * reached. What a dialog's item tooltip is made of: the same JSON
     * {@code show_item} carries, so the tooltip is the inventory's own.
     */
    public static com.google.gson.JsonObject itemJson(org.bukkit.inventory.ItemStack stack) {
        ItemBridge b = stack == null ? null : itemBridge();
        if (b == null) return null;
        try {
            Object nms = b.asNmsCopy.invoke(null, stack);
            Object encoded = b.result.invoke(b.encodeStart.invoke(b.codec, b.ops, nms));
            return encoded instanceof com.google.gson.JsonObject json ? json : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static boolean show(Player viewer, String json) {
        lastRefusal = null;
        Bridge b = bridge();
        if (b == null) return false;
        Object dialog;
        try {
            dialog = decode(json);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The codec refused it: the dialog's fault, and the line that says
            // which field. Kept short — it can quote the dialog's own words.
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            lastRefusal = message.length() > 300 ? message.substring(0, 300) + "…" : message;
            return false;
        }
        try {
            Object player = viewer.getClass().getMethod("getHandle").invoke(viewer);
            player.getClass().getMethod("openDialog", b.holder).invoke(player, dialog);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The inline route remains usable if a server changes this optional
            // bridge. Do not print the JSON: it can contain private content.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Bukkit.getLogger().fine("[RPEngine] Direct dialog delivery failed: " + cause.getClass().getSimpleName());
            return false;
        }
    }
}
