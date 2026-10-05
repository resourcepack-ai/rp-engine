package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.Icons;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Typing {@code :wave:} in chat and getting the picture.
 *
 * <p>The pack already ships the icons and the font already draws them; this is
 * the last inch, and it is the most-screenshotted feature of every plugin in
 * this market for a reason — it is the one people can see other people using.
 *
 * <p><strong>An icon is a character, so this is a text replacement and
 * nothing more.</strong> Everything hard about drawing a picture in chat was
 * done by the font; there is no image here, no packet, and nothing that has to
 * agree with the client beyond a codepoint the pack already defined. The text
 * rules themselves are {@link ChatShortcuts}, which is free of Bukkit and
 * tested; this is the listener around them.
 *
 * <p>Three decisions worth keeping:
 *
 * <ul>
 *   <li><strong>A name that is not an icon is left exactly as typed.</strong>
 *       People write {@code 10:30} and {@code :)} in chat, and a replacement
 *       that ate either would be a plugin quietly corrupting what somebody
 *       said. Only a name this server actually has is touched.
 *   <li><strong>It is gated by a permission</strong>, off by default for
 *       nobody in particular: a server that wants icons in chat to be a perk
 *       has that, and one that does not gives it to everybody in one line.
 *   <li><strong>An icon can ask for a permission of its own</strong>, on top
 *       of that one, and it gates the icon however it is typed — by an alias
 *       or by {@code :id:}. A rank's emote that anybody could type by its name
 *       would be a perk with a hole in it. Without one, the general permission
 *       is all it takes.
 * </ul>
 *
 * <p>Runs at {@link EventPriority#LOW} so a chat-formatting plugin sees the
 * finished text, rather than us rewriting whatever it produced.
 *
 * <p>{@link Icons#format} does the same job for {@code :namespace:id:} and is
 * what a config file uses. This is not that: chat also has to accept a BARE
 * name and an icon's aliases, because somebody typing has no reason to know
 * which pack a smiley came from — so it is one pass that handles all of them
 * rather than that pass plus another.
 *
 * <p>An animated icon is its FIRST frame here. A chat line is sent once and
 * never redrawn, so there is nothing to animate it with; see
 * {@link Icons#characterNow}.
 */
public final class ChatIcons implements Listener {

    /** What somebody needs to be allowed to use them. */
    public static final String PERMISSION = "rpengine.chat.icons";

    private final Icons icons;
    private final boolean enabled;

    public ChatIcons(Icons icons, boolean enabled) {
        this.icons = icons;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(PERMISSION)) {
            return;
        }
        ChatShortcuts shortcuts = shortcuts();
        // Cheap early-out: most lines have no colon in them at all, and this
        // runs on every message on the server. Unless a pack declared an alias
        // like <3, that is the end of it.
        if (!shortcuts.mightMatch(event.getMessage())) {
            return;
        }
        event.setMessage(shortcuts.replace(event.getMessage(),
                icon -> icon.permission().map(player::hasPermission).orElse(true)));
    }

    /**
     * Every {@code :name:} and alias that names an icon, replaced by its
     * character, as if the sender may use every icon.
     */
    String replace(String message) {
        return replace(message, icon -> true);
    }

    /** The same, with {@code allowed} deciding icon by icon, as a permission does. */
    String replace(String message, Predicate<IconInfo> allowed) {
        return shortcuts().replace(message, allowed);
    }

    /**
     * The engine's own icons keep theirs built, rebuilt on each reload; any
     * other {@link Icons} (a test's) is asked afresh.
     */
    private ChatShortcuts shortcuts() {
        if (icons instanceof IconsImpl impl) {
            return impl.shortcuts();
        }
        List<IconInfo> all = new ArrayList<>();
        for (ContentId id : icons.ids()) {
            icons.info(id).ifPresent(all::add);
        }
        return ChatShortcuts.of(all);
    }
}
