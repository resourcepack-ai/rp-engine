package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.Icons;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * agree with the client beyond a codepoint the pack already defined.
 *
 * <p>Two decisions worth keeping:
 *
 * <ul>
 *   <li><strong>A name that is not an icon is left exactly as typed.</strong>
 *       People write {@code 10:30} and {@code :)} in chat, and a replacement
 *       that ate either would be a plugin quietly corrupting what somebody
 *       said. Only a name this server actually has is touched.
 *   <li><strong>It is gated by a permission</strong>, off by default for
 *       nobody in particular: a server that wants icons in chat to be a perk
 *       has that, and one that does not gives it to everybody in one line.
 * </ul>
 *
 * <p>Runs at {@link EventPriority#LOW} so a chat-formatting plugin sees the
 * finished text, rather than us rewriting whatever it produced.
 *
 * <p>{@link Icons#format} does the same job for {@code :namespace:id:} and is
 * what a config file uses. This is not that: chat also has to accept a BARE
 * name, because somebody typing has no reason to know which pack a smiley came
 * from — so it is one pass that handles both rather than that pass plus
 * another.
 */
public final class ChatIcons implements Listener {

    /**
     * {@code :name:}, or {@code :pack:name:} where two packs collide.
     *
     * <p>The namespaced form is the FIRST branch on purpose. A single
     * character class that allowed a colon inside would match {@code :pack:}
     * out of {@code :pack:name:}, find nothing called "pack", and leave the
     * rest stranded with no opening colon left to match against.
     */
    private static final Pattern SHORTCODE =
            Pattern.compile(":([a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}|[a-z0-9_./-]{1,64}):");

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
        // Cheap early-out: most lines have no colon in them at all, and this
        // runs on every message on the server.
        if (event.getMessage().indexOf(':') < 0) {
            return;
        }
        event.setMessage(replace(event.getMessage()));
    }

    /**
     * Every {@code :name:} that names an icon, replaced by its character.
     *
     * <p>Free of Bukkit and tested, because the interesting cases are all
     * about text: overlapping colons, a time of day, a name with a namespace
     * in it.
     */
    String replace(String message) {
        Matcher matcher = SHORTCODE.matcher(message);
        StringBuilder out = new StringBuilder(message.length());
        int at = 0;
        while (matcher.find()) {
            Optional<IconInfo> icon = lookUp(matcher.group(1));
            if (icon.isEmpty()) {
                continue;
            }
            out.append(message, at, matcher.start()).append(icon.get().character());
            at = matcher.end();
        }
        return at == 0 ? message : out.append(message.substring(at)).toString();
    }

    /**
     * The icon a shortcode names.
     *
     * <p>A bare name is looked up across every namespace, because somebody
     * typing in chat has no reason to know which pack a smiley came from.
     * Ambiguity resolves to the first in sorted order, which is at least
     * stable — a server with two packs that both call something "wave" can
     * write {@code :mypack:wave:} to be specific.
     */
    private Optional<IconInfo> lookUp(String name) {
        Optional<IconInfo> exact = icons.info(name);
        if (exact.isPresent()) {
            return exact;
        }
        if (name.indexOf(':') >= 0) {
            return Optional.empty();
        }
        for (ContentId id : icons.ids()) {
            if (id.path().equals(name)) {
                return icons.info(id);
            }
        }
        return Optional.empty();
    }
}
