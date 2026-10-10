package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogInfo;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "The dialog a player was last shown" is what every {@code /rp page},
 * {@code /rp slot} and list pick is checked against, so it must not outlive the
 * dialog: not by time, not past the server closing it, and not past the
 * permission it was opened on.
 */
class LastShownTest {

    private static ContentId id(String text) {
        return ContentId.parse(text).orElseThrow();
    }

    private static final ContentId VAULT = id("shop:vault");
    private static final ContentId VAULT_TWO = id("shop:vault.two");

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Set<String> permissions = new HashSet<>();

    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> args[0] instanceof String node && permissions.contains(node);
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
        String json = "{\"type\":\"minecraft:notice\",\"title\":\"Vault\"}";
        catalogue.put(VAULT, DialogInfo.authored(VAULT, json, "vault").withCommand("vault", "shop.vault"));
        catalogue.put(VAULT_TWO, DialogInfo.authored(VAULT_TWO, json, "vault two"));
        dialogs.replace(catalogue);
        dialogs.clock(now::get);
        return dialogs;
    }

    @Test
    void itExpiresAfterTenMinutesOfNothing() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(VAULT, Map.of()), false);

        now.addAndGet(DialogsImpl.SHOWN_FOR_MILLIS - 1);
        assertTrue(dialogs.lastShown(player).isPresent());

        now.addAndGet(2);
        assertFalse(dialogs.lastShown(player).isPresent());
    }

    @Test
    void showingItAgainStartsTheClockAgain() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(VAULT, Map.of()), false);
        now.addAndGet(DialogsImpl.SHOWN_FOR_MILLIS - 1);
        dialogs.remember(player, new DialogsImpl.Shown(VAULT, Map.of()), true);
        now.addAndGet(DialogsImpl.SHOWN_FOR_MILLIS - 1);

        assertTrue(dialogs.lastShown(player).isPresent());
    }

    @Test
    void theServerClosingItForgetsIt() {
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(VAULT, Map.of()), false);

        dialogs.close(player);

        assertFalse(dialogs.lastShown(player).isPresent());
    }

    @Test
    void losingThePermissionItOpenedOnLosesItAndItsPages() {
        DialogsImpl dialogs = dialogs();
        permissions.add("shop.vault");
        dialogs.remember(player, new DialogsImpl.Shown(VAULT, Map.of()), false);
        // Its second page has no permission of its own, and is only as open as the first.
        dialogs.remember(player, new DialogsImpl.Shown(VAULT_TWO, Map.of()), true);
        assertTrue(dialogs.lastShown(player).isPresent());

        permissions.remove("shop.vault");

        assertFalse(dialogs.lastShown(player).isPresent());
    }

    @Test
    void aDialogOpenedOnSomebodyWithoutThePermissionIsNotHeldToIt() {
        // Staff opening it on a player who lacks the node: the node was never what let it open.
        DialogsImpl dialogs = dialogs();
        dialogs.remember(player, new DialogsImpl.Shown(VAULT, Map.of()), false);

        assertTrue(dialogs.lastShown(player).isPresent());
    }
}
