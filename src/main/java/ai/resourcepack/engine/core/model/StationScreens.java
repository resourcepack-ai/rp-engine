package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ModelInfo;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;

import java.lang.reflect.Method;

/**
 * Opens the game's own work screens for a piece that is a crafting table, an
 * anvil, a loom.
 *
 * <p>Nothing is simulated or kept here, which is what makes these so much less
 * than a furnace: each screen is the game's, does its own work, and hands
 * anything left in it back to the player when it closes. {@code force} is what
 * lets one open with no block of that kind standing there.
 *
 * <p><strong>Three ways in, because no server has all of them.</strong> A
 * crafting table and an enchanting table are plain Bukkit on every version.
 * The other six are {@code HumanEntity} methods only Paper has, and
 * {@code MenuType} on Spigot from 1.21. Both are looked up by name, since the
 * API this compiles against has only one of them and the oldest has neither.
 */
final class StationScreens {

    private StationScreens() {
    }

    /** Whether this server can open this screen at all. */
    static boolean available(ModelInfo.Station station) {
        return !needsLookup(station) || paperMethod(station) != null || menuType(station) != null;
    }

    /**
     * @param at    where the screen says it is. An enchanting table counts the
     *              bookshelves round here
     * @param title what to call it where the server lets a plugin choose,
     *              which is only the Spigot route
     * @return whether a screen opened
     */
    static boolean open(Player player, ModelInfo.Station station, Location at, String title) {
        switch (station) {
            case CRAFTING_TABLE:
                return player.openWorkbench(at, true) != null;
            case ENCHANTING_TABLE:
                return player.openEnchanting(at, true) != null;
            default:
                break;
        }
        try {
            Method paper = paperMethod(station);
            if (paper != null) {
                return paper.invoke(player, at, true) != null;
            }
            Object type = menuType(station);
            if (type == null) {
                return false;
            }
            Method create = Class.forName("org.bukkit.inventory.MenuType$Typed")
                    .getMethod("create", HumanEntity.class, String.class);
            Object view = create.invoke(type, player, title);
            HumanEntity.class.getMethod("openInventory", InventoryView.class).invoke(player, view);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean needsLookup(ModelInfo.Station station) {
        return station != ModelInfo.Station.CRAFTING_TABLE && station != ModelInfo.Station.ENCHANTING_TABLE;
    }

    private static Method paperMethod(ModelInfo.Station station) {
        String name;
        switch (station) {
            case ANVIL:
                name = "openAnvil";
                break;
            case STONECUTTER:
                name = "openStonecutter";
                break;
            case SMITHING_TABLE:
                name = "openSmithingTable";
                break;
            case GRINDSTONE:
                name = "openGrindstone";
                break;
            case LOOM:
                name = "openLoom";
                break;
            case CARTOGRAPHY_TABLE:
                name = "openCartographyTable";
                break;
            default:
                return null;
        }
        try {
            return HumanEntity.class.getMethod(name, Location.class, boolean.class);
        } catch (NoSuchMethodException | LinkageError e) {
            return null;
        }
    }

    private static Object menuType(ModelInfo.Station station) {
        String field;
        switch (station) {
            case ANVIL:
                field = "ANVIL";
                break;
            case STONECUTTER:
                field = "STONECUTTER";
                break;
            case SMITHING_TABLE:
                field = "SMITHING";
                break;
            case GRINDSTONE:
                field = "GRINDSTONE";
                break;
            case LOOM:
                field = "LOOM";
                break;
            case CARTOGRAPHY_TABLE:
                field = "CARTOGRAPHY_TABLE";
                break;
            default:
                return null;
        }
        try {
            return Class.forName("org.bukkit.inventory.MenuType").getField(field).get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
