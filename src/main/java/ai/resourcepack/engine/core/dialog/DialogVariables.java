package ai.resourcepack.engine.core.dialog;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The values a player's dialogs show them: one small map per player, kept on
 * the player. Internal.
 *
 * <h2>What it is for</h2>
 *
 * A dialog is a picture, drawn once — so a switch on it is a picture of a
 * switch, on for everybody. A Studio dialog can instead BIND a control to a
 * name: every state of it is drawn, and the body carries a choice between them
 * that resolves per player ({@link DialogPlaceholders}). This is where the
 * value it resolves by lives. A click on the control runs {@code /rp var <name>
 * <value>}, which sets it here and opens the dialog again, now drawn in the new
 * state — so a settings screen made in Studio works with nothing else on the
 * server. Other plugins read the same values back as
 * {@code %rpengine_var_<name>%} and act on them.
 *
 * <h2>Why on the player</h2>
 *
 * The persistent data container travels with the player's own save: it
 * survives restarts, needs no file of ours, and is gone when the player is.
 * Encoded as {@code name=value;name=value} rather than as a key per variable,
 * because the set of names is somebody's content and a key per name would be a
 * namespace nobody could clean up.
 *
 * <h2>What a player can put in it</h2>
 *
 * Nothing the server did not offer. {@code /rp var} is a command every player
 * may run — it has to be, a click on a dialog runs it as them — so it accepts
 * only a name some loaded dialog declares and only one of the values that
 * dialog declares for it (see {@link DialogsImpl#declares}). The names and
 * values themselves are held to a small alphabet here as well, and the map to
 * {@link #MAX_VARIABLES}, so no path can grow it without limit.
 */
public final class DialogVariables {

    /** A variable's name: what a control binds and a placeholder names. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    /** A value: what a control's state is called. */
    public static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");

    /** The most names one player keeps. A settings screen is a dozen. */
    public static final int MAX_VARIABLES = 64;

    private final NamespacedKey key;

    public DialogVariables(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "dialog_vars");
    }

    /** The player's value for a name, if they have one. */
    public Optional<String> get(Player player, String name) {
        if (player == null || name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(all(player).get(name.toLowerCase(Locale.ROOT)));
    }

    /** Every value the player has, in the order they were first set. */
    public Map<String, String> all(Player player) {
        if (player == null) {
            return Map.of();
        }
        String stored = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return Collections.unmodifiableMap(decode(stored));
    }

    /**
     * Sets a value. False when the name or value is not one this store keeps,
     * or the player already has as many names as it keeps and this is a new
     * one — nothing is written in any of those cases.
     */
    public boolean set(Player player, String name, String value) {
        if (player == null || name == null || value == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(n).matches() || !VALUE.matcher(value).matches()) {
            return false;
        }
        Map<String, String> values = decode(player.getPersistentDataContainer().get(key, PersistentDataType.STRING));
        if (!values.containsKey(n) && values.size() >= MAX_VARIABLES) {
            return false;
        }
        values.put(n, value);
        player.getPersistentDataContainer().set(key, PersistentDataType.STRING, encode(values));
        return true;
    }

    /** Forgets a value. */
    public void clear(Player player, String name) {
        if (player == null || name == null) {
            return;
        }
        Map<String, String> values = decode(player.getPersistentDataContainer().get(key, PersistentDataType.STRING));
        if (values.remove(name.toLowerCase(Locale.ROOT)) != null) {
            if (values.isEmpty()) {
                player.getPersistentDataContainer().remove(key);
            } else {
                player.getPersistentDataContainer().set(key, PersistentDataType.STRING, encode(values));
            }
        }
    }

    /** {@code name=value;…}, anything malformed skipped rather than trusted. */
    static Map<String, String> decode(String stored) {
        Map<String, String> out = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty()) {
            return out;
        }
        for (String pair : stored.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if (NAME.matcher(name).matches() && VALUE.matcher(value).matches() && out.size() < MAX_VARIABLES) {
                out.put(name, value);
            }
        }
        return out;
    }

    static String encode(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }
}
