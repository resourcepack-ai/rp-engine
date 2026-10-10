package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogInfo;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player's own dialog settings are written by the player — {@code /rp var}
 * is a command everybody has — so they must never answer for a value a dialog
 * trusts, and a player may only set what the dialog in front of them offers.
 */
class PlayerSettingsTest {

    private static ContentId id(String text) {
        return ContentId.parse(text).orElseThrow();
    }

    private static final ContentId VAULT = id("shop:vault");
    private static final ContentId OTHER = id("shop:other");

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Map<NamespacedKey, Object> stored = new HashMap<>();

    private final PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(
            PersistentDataContainer.class.getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "get" -> stored.get((NamespacedKey) args[0]);
                case "set" -> stored.put((NamespacedKey) args[0], args[2]);
                case "remove" -> stored.remove((NamespacedKey) args[0]);
                case "has" -> stored.containsKey((NamespacedKey) args[0]);
                default -> null;
            });

    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getLevel" -> 12;
                case "getPersistentDataContainer" -> data;
                case "hasPermission" -> false;
                case "getUniqueId" -> UUID.nameUUIDFromBytes("tester".getBytes());
                case "isOnline" -> false;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "getName", "toString" -> "Tester";
                default -> null;
            });

    private static final Plugin PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getName", "toString" -> "RPEngine";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            });

    /** A piece only a player of level 30 sees, and one only a player with mode b sees. */
    private static final String VAULT_JSON = "{\"type\":\"minecraft:notice\",\"body\":{\"text\":\"\",\"extra\":["
            + "{\"text\":\"\",\"insertion\":\"rp:if:{level} >= 30\",\"extra\":[{\"text\":\"SECRET\"},{\"text\":\"\"}]},"
            + "{\"text\":\"\",\"insertion\":\"rp:if:{mode} == \\\"b\\\"\",\"extra\":[{\"text\":\"MODE_B\"},{\"text\":\"\"}]}"
            + "]}}";

    private DialogsImpl dialogs() {
        DialogsImpl dialogs = new DialogsImpl(new DialogDatapack(Logger.getAnonymousLogger(), Map.of(), x -> false),
                false, new DialogVariables(PLUGIN), null);
        Map<ContentId, DialogInfo> catalogue = new LinkedHashMap<>();
        catalogue.put(VAULT, DialogInfo.authored(VAULT, VAULT_JSON, "vault", Map.of("mode", List.of("a", "b"))));
        catalogue.put(OTHER, DialogInfo.authored(OTHER, "{\"type\":\"minecraft:notice\"}", "other",
                Map.of("level", List.of("99"))));
        dialogs.replace(catalogue);
        dialogs.clock(now::get);
        return dialogs;
    }

    @Test
    void aSettingCannotAnswerForABuiltIn() {
        DialogsImpl dialogs = dialogs();
        assertTrue(dialogs.setSetting(player, "level", "99"));

        String seen = dialogs.visibleJson(player, VAULT, Map.of()).orElseThrow();

        assertFalse(seen.contains("SECRET"), seen);
    }

    @Test
    void aSettingStillAnswersForItsOwnName() {
        DialogsImpl dialogs = dialogs();
        assertFalse(dialogs.visibleJson(player, VAULT, Map.of()).orElseThrow().contains("MODE_B"));

        dialogs.setSetting(player, "mode", "b");

        assertTrue(dialogs.visibleJson(player, VAULT, Map.of()).orElseThrow().contains("MODE_B"));
    }

    @Test
    void aPlayerMaySetOnlyWhatTheDialogInFrontOfThemOffers() {
        DialogsImpl dialogs = dialogs();
        // Nothing shown: nothing to click.
        assertFalse(dialogs.declaresShown(player, "mode", "b"));

        dialogs.remember(player, new DialogsImpl.Shown(VAULT, Map.of()), false);

        assertTrue(dialogs.declaresShown(player, "mode", "b"));
        assertFalse(dialogs.declaresShown(player, "mode", "c"), "a value the dialog does not list");
        // Declared by a dialog on the server, but not the one this player is looking at.
        assertFalse(dialogs.declaresShown(player, "level", "99"));
        assertTrue(dialogs.declares("level", "99"), "staff naming a player are held to any dialog's");

        now.addAndGet(DialogsImpl.SHOWN_FOR_MILLIS + 1);
        assertFalse(dialogs.declaresShown(player, "mode", "b"), "a dialog long since left");
    }
}
