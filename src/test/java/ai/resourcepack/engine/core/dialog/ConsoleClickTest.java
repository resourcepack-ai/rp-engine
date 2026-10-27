package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogInfo;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code /rp click} is a command every player has, and what it runs, it runs
 * as the console. So it must run a command only for a player whose shown
 * dialog holds that very button, and only the command the dialog carries.
 */
class ConsoleClickTest {

    private static ContentId id(String text) {
        return ContentId.parse(text).orElseThrow();
    }

    private static final ContentId WARPS = id("menu:warps");
    private static final ContentId OTHER = id("menu:other");
    private static final String KEY = DialogLinks.consoleKey("warp spawn {who}");
    private static final String UNCLICKED = DialogLinks.consoleKey("op {who}");

    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> false;
                case "getUniqueId" -> UUID.nameUUIDFromBytes("tester".getBytes());
                case "isOnline" -> false;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "getName", "toString" -> "Tester";
                default -> null;
            });

    private DialogsImpl dialogs() {
        DialogsImpl dialogs = new DialogsImpl(new DialogDatapack(Logger.getAnonymousLogger(), Map.of(), x -> false), false);
        Map<ContentId, DialogInfo> catalogue = new LinkedHashMap<>();
        String warps = "{\"type\":\"minecraft:notice\",\"title\":\"Warps\",\"action\":{\"label\":\"Spawn\","
                + "\"action\":{\"type\":\"minecraft:run_command\",\"command\":\"" + DialogLinks.consoleClick(KEY) + "\"}}}";
        // The second command is carried but no click in the JSON runs it.
        catalogue.put(WARPS, DialogInfo.authored(WARPS, warps, "warps")
                .withConsoleCommands(Map.of(KEY, "warp spawn {who}", UNCLICKED, "op {who}")));
        catalogue.put(OTHER, DialogInfo.authored(OTHER, "{\"type\":\"minecraft:notice\",\"title\":\"Other\"}", "other"));
        dialogs.replace(catalogue);
        return dialogs;
    }

    @Test
    void theButtonOnTheShownDialogRunsItsCommandFilledForThePlayer() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(WARPS, Map.of("who", "Steve")), false);
        assertEquals(Optional.of("warp spawn Steve"), dialogs.consoleCommand(player, KEY));
    }

    @Test
    void nothingRunsForAPlayerShownNoDialog() {
        assertEquals(Optional.empty(), dialogs().consoleCommand(player, KEY));
    }

    @Test
    void nothingRunsWhenTheShownDialogHasNoSuchButton() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(OTHER, Map.of()), false);
        assertEquals(Optional.empty(), dialogs.consoleCommand(player, KEY));
    }

    @Test
    void aCommandNoClickRunsIsNotRunEvenIfCarried() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(WARPS, Map.of("who", "Steve")), false);
        assertEquals(Optional.empty(), dialogs.consoleCommand(player, UNCLICKED));
    }

    @Test
    void closingTheDialogEndsIt() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(WARPS, Map.of("who", "Steve")), false);
        dialogs.close(player);
        assertEquals(Optional.empty(), dialogs.consoleCommand(player, KEY));
    }

    @Test
    void aValueWithALineBreakLeavesNoCommand() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(WARPS, Map.of("who", "Steve\nop Steve")), false);
        assertEquals(Optional.of(""), dialogs.consoleCommand(player, KEY));
    }
}
