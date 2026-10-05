package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemAction;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.Sounds;

import ai.resourcepack.engine.core.version.Vanilla;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs an item's actions.
 *
 * <p>The Bukkit half of {@link ItemActions}, kept apart from the parsing for
 * the usual reason: everything here needs a server and is therefore untestable,
 * so it is deliberately thin and holds no rules of its own. Whether a step is
 * well-formed was decided at load.
 *
 * <p><strong>A step that cannot run is skipped, and the rest still run.</strong>
 * A potion type somebody misspelled should cost that line, not the command
 * after it — an item that half works is traceable, and an item that stops dead
 * on its second step looks like the plugin failing.
 *
 * <p>Cooldowns live here rather than on the item because they are per player.
 * They are in memory and go with a restart, which is the honest scope for
 * something measured in seconds.
 */
public final class ActionRunner {

    /** player -> "id/trigger" -> when it may run again, in millis. */
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    private final Items items;
    private final Sounds sounds;

    public ActionRunner(Items items, Sounds sounds) {
        this.items = items;
        this.sounds = sounds;
    }

    /**
     * Runs what {@code id} does on {@code trigger}.
     *
     * @return whether the vanilla use of the stack should be cancelled
     */
    public boolean run(Player player, ContentId id, ItemAction.Trigger trigger, ItemStack stack) {
        List<ItemAction> steps = items.info(id)
                .map(info -> info.actions(trigger))
                .orElse(List.of());
        if (steps.isEmpty()) {
            return false;
        }

        boolean cancel = false;
        for (ItemAction step : steps) {
            switch (step.kind()) {
                case COOLDOWN:
                    if (cooling(player, id, trigger, step)) {
                        // Everything after it is skipped, which is what a
                        // cooldown means. A message BEFORE it still went out,
                        // which is how an author explains the refusal.
                        return cancel;
                    }
                    break;
                case PERMISSION:
                    if (!player.hasPermission(step.argument())) {
                        return cancel;
                    }
                    break;
                case CANCEL:
                    cancel = true;
                    break;
                default:
                    perform(player, step, stack);
                    break;
            }
        }
        return cancel;
    }

    private void perform(Player player, ItemAction step, ItemStack stack) {
        switch (step.kind()) {
            case MESSAGE:
                player.sendMessage(colour(text(player, step.argument())));
                break;
            case BROADCAST:
                Bukkit.broadcastMessage(colour(text(player, step.argument())));
                break;
            case ACTIONBAR:
                // fromLegacyText rather than fromLegacy: the latter arrived
                // in the bungee chat library that ships with 1.20, and the
                // one a server provides is whatever its Minecraft bundles.
                // The older name is still present on every version, and
                // sendMessage takes the component array it returns as varargs.
                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                colour(text(player, step.argument()))));
                break;
            case CONSOLE:
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text(player, step.argument()));
                break;
            case RUN:
                // As the player, with the player's own permissions — which is
                // the whole difference between the two and the reason both
                // exist. A console command is how an action reaches something
                // the user may not do themselves.
                player.performCommand(text(player, step.argument()));
                break;
            case SOUND:
                playSound(player, step);
                break;
            case EFFECT:
                effect(player, step);
                break;
            case TAKE:
                take(stack, step);
                break;
            case GIVE:
                give(player, step);
                break;
            default:
                break;
        }
    }

    /**
     * Whether this is still cooling, starting the clock if it is not.
     *
     * <p>Keyed by item AND trigger, so a wand with a slow right-click and a
     * fast left-click behaves as its author wrote it rather than sharing one
     * timer.
     */
    private boolean cooling(Player player, ContentId id, ItemAction.Trigger trigger, ItemAction step) {
        double seconds = step.number().orElse(0d);
        if (seconds <= 0) {
            return false;
        }
        Map<String, Long> theirs = cooldowns.computeIfAbsent(player.getUniqueId(),
                key -> new ConcurrentHashMap<>());
        String key = id + "/" + trigger.name();
        long now = System.currentTimeMillis();
        Long until = theirs.get(key);
        if (until != null && until > now) {
            return true;
        }
        theirs.put(key, now + (long) (seconds * 1000));
        return false;
    }

    /** One of this server's sounds, or a vanilla one named the vanilla way. */
    private void playSound(Player player, ItemAction step) {
        String[] words = step.words();
        if (words.length == 0) {
            return;
        }
        float volume = words.length > 1 ? number(words[1], 1f) : 1f;
        float pitch = words.length > 2 ? number(words[2], 1f) : 1f;

        Optional<ContentId> id = ContentId.parse(words[0]);
        if (id.isPresent() && sounds.info(id.get()).isPresent()) {
            sounds.play(player, id.get(), volume, pitch);
            return;
        }
        // Not ours, so it is meant to be vanilla's. Played by key rather than
        // by enum constant: the constants are renamed between versions and a
        // key from the wiki is what an author actually has in front of them.
        NamespacedKey key = NamespacedKey.fromString(words[0].toLowerCase(Locale.ROOT));
        if (key == null) {
            return;
        }
        Sound sound = org.bukkit.Registry.SOUNDS.get(key);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    private void effect(Player player, ItemAction step) {
        String[] words = step.words();
        if (words.length < 2) {
            return;
        }
        PotionEffectType type = Vanilla.effect(words[0]).orElse(null);
        if (type == null) {
            return;
        }
        int seconds = (int) number(words[1], 0f);
        int amplifier = words.length > 2 ? (int) number(words[2], 1f) : 1;
        if (seconds <= 0) {
            return;
        }
        // Amplifier is one-based to an author and zero-based to the game:
        // "SPEED 10 1" means Speed I, which the game calls amplifier 0.
        player.addPotionEffect(new PotionEffect(type, seconds * 20, Math.max(0, amplifier - 1)));
    }

    private void take(ItemStack stack, ItemAction step) {
        if (stack == null) {
            return;
        }
        int amount = (int) (double) step.number().orElse(1d);
        if (amount > 0) {
            stack.setAmount(Math.max(0, stack.getAmount() - amount));
        }
    }

    private void give(Player player, ItemAction step) {
        String[] words = step.words();
        if (words.length == 0) {
            return;
        }
        int amount = words.length > 1 ? (int) number(words[1], 1f) : 1;
        ContentId.parse(words[0])
                .flatMap(id -> items.create(id, Math.max(1, amount)))
                .ifPresent(stack -> {
                    // Whatever will not fit goes on the floor rather than
                    // vanishing, which is what every vanilla give does.
                    Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
                    Location where = player.getLocation();
                    left.values().forEach(over -> player.getWorld().dropItemNaturally(where, over));
                });
    }

    /**
     * The author's text, with the things they can put in it filled in.
     *
     * <p>PlaceholderAPI is asked too, if it is there, so an action can say
     * anything the rest of the server can — reached by name so this class does
     * not depend on it.
     */
    private static String text(Player player, String written) {
        Location at = player.getLocation();
        String out = written
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{world}", at.getWorld() == null ? "" : at.getWorld().getName())
                .replace("{x}", String.valueOf(at.getBlockX()))
                .replace("{y}", String.valueOf(at.getBlockY()))
                .replace("{z}", String.valueOf(at.getBlockZ()));
        return placeholders(player, out);
    }

    private static String placeholders(Player player, String text) {
        if (text.indexOf('%') < 0 || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return text;
        }
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object result = api.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class)
                    .invoke(null, player, text);
            return result instanceof String ? (String) result : text;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Its problem, not ours, and the text without it is still the
            // text the author wrote.
            return text;
        }
    }

    private static String colour(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static float number(String written, float fallback) {
        try {
            return Float.parseFloat(written);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Forgets a player's cooldowns. Called when they leave. */
    public void forget(UUID player) {
        cooldowns.remove(player);
    }
}
