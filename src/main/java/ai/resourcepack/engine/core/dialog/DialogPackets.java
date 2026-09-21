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

    public static boolean show(Player viewer, String json) {
        Bridge b = bridge();
        if (b == null) return false;
        try {
            Object dialog = decode(json);
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
