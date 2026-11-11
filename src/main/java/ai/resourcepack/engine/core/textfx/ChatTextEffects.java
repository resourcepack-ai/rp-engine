package ai.resourcepack.engine.core.textfx;

import ai.resourcepack.engine.api.TextEffectInfo;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Locale;

/**
 * Typing {@code <fx:fire>hello</fx>} in chat and having it burn.
 *
 * <p>Gated per effect, so a rank can have one effect as a perk without the
 * rest: {@code rpengine.chat.effects.<name>} for one (the effect's name, a
 * {@code /} in it a {@code .}), {@code rpengine.chat.effects.all} for every
 * one there is or will be. A tag somebody may not use stays as they typed it.
 *
 * <p>Runs at {@link EventPriority#LOW}, like chat icons, so a chat-formatting
 * plugin sees the finished text rather than having it rewritten afterwards.
 */
public final class ChatTextEffects implements Listener {

    /** Every effect in chat. */
    public static final String ALL = "rpengine.chat.effects.all";

    /** One effect in chat: this plus its name. */
    public static final String PREFIX = "rpengine.chat.effects.";

    private final TextEffectCatalogue effects;
    private final boolean enabled;

    public ChatTextEffects(TextEffectCatalogue effects, boolean enabled) {
        this.effects = effects;
        this.enabled = enabled;
    }

    /** The node that lets somebody use {@code effect} in chat, besides {@link #ALL}. */
    public static String permission(TextEffectInfo effect) {
        return PREFIX + effect.id().path().replace('/', '.').toLowerCase(Locale.ROOT);
    }

    /** Whether {@code player} may use {@code effect} in chat. */
    public static boolean allowed(Player player, TextEffectInfo effect) {
        return player.hasPermission(ALL) || player.hasPermission(permission(effect));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!enabled || !TextEffectTags.mightMatch(event.getMessage())) {
            return;
        }
        Player player = event.getPlayer();
        event.setMessage(effects.format(event.getMessage(), effect -> allowed(player, effect)));
    }
}
