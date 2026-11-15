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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DialogClickEvent} is fired for a command only when it is a click in
 * the dialog the player was shown, and {@code Dialogs.showing} names that
 * dialog. A command the dialog does not hold is somebody typing, and no click.
 */
class DialogClickedTest {

    private static final ContentId SHOP = ContentId.parse("shop:main").orElseThrow();

    private static final String JSON = "{\"type\":\"minecraft:multi_action\",\"title\":\"Shop\","
            + "\"body\":[{\"type\":\"minecraft:plain_message\",\"contents\":{\"text\":\"Sword\","
            + "\"click_event\":{\"action\":\"run_command\",\"command\":\"/buy sword\"}}}],"
            + "\"actions\":[{\"label\":\"Sell\",\"action\":{\"type\":\"run_command\",\"command\":\"sell {item}\"}}]}";

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
        catalogue.put(SHOP, DialogInfo.authored(SHOP, JSON, "shop"));
        dialogs.replace(catalogue);
        return dialogs;
    }

    @Test
    void aCommandTheShownDialogHoldsIsAClickInIt() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(SHOP, Map.of("item", "apple")), false);

        Optional<DialogsImpl.Shown> picture = dialogs.clicked(player, "/buy sword");
        assertTrue(picture.isPresent());
        assertEquals(SHOP, picture.get().id());
        assertEquals("apple", picture.get().values().get("item"));
        // A footer button's, with its placeholder filled by anything.
        assertTrue(dialogs.clicked(player, "/sell apple").isPresent());
    }

    @Test
    void anyOtherCommandIsNot() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(SHOP, Map.of()), false);

        assertFalse(dialogs.clicked(player, "/buy shield").isPresent());
        assertFalse(dialogs.clicked(player, "/op Tester").isPresent());
        assertFalse(dialogs.clicked(player, null).isPresent());
    }

    @Test
    void nothingIsAClickWithNoDialogShown() {
        DialogsImpl dialogs = dialogs();

        assertFalse(dialogs.clicked(player, "/buy sword").isPresent());
        assertTrue(dialogs.showing(player).isEmpty());
    }

    @Test
    void showingNamesTheDialogUntilTheServerClosesIt() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(SHOP, Map.of()), false);
        assertEquals(Optional.of(SHOP), dialogs.showing(player));

        dialogs.close(player);
        assertTrue(dialogs.showing(player).isEmpty());
    }
}
