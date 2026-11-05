package ai.resourcepack.engine.core.serve;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code send.before-join}: the packs go out while the player is still
 * connecting, so they arrive in the world with them already loaded, rather
 * than being sent once they are standing in it.
 *
 * <p>That moment is Minecraft's configuration phase (1.20.2+), and the only
 * server that lets a plugin act in it is Paper, from 1.21.7, through
 * {@code AsyncPlayerConnectionConfigureEvent}: the connection stays in
 * configuration until every listener has returned, so this one sends the
 * packs and waits for the client's answer before letting it go on. The
 * client shows its own "loading" screen meanwhile, and a pack that is
 * required and declined disconnects it, as it would in game.
 *
 * <p>Everything here is reflective, for the reason {@code AdventureBossBars}
 * gives: the engine compiles against Spigot, and on a server without the
 * event (Spigot, or Paper before 1.21.7) {@link #register} says no and the
 * packs go at join as they always did.
 */
public final class PaperPreJoin implements Listener {

    /** The longest a connection is held waiting for a client's answer: a big pack on a slow line. */
    private static final long TIMEOUT_SECONDS = 120;

    /** What to send and where the answers go. Called off the main thread. */
    public interface Source {
        /** What to send this player now; empty sends nothing and the join does it. */
        List<PackDelivery.PreJoin> plan(UUID player);

        /** The client's last answer for each pack, by pack id. */
        void done(UUID player, List<PackDelivery.PreJoin> sent, Map<UUID, String> statuses);

        /** The accept screen's message as a legacy string, or null. */
        String prompt();

        boolean force();
    }

    private final Source source;
    private final Logger logger;
    private final Method getConnection;
    private final Method getAudience;
    private final Method getProfile;
    private final Method profileId;
    private final Method packInfo;
    private final Method requestBuilder;
    private final Method builderPacks;
    private final Method builderRequired;
    private final Method builderReplace;
    private final Method builderPrompt;
    private final Method builderCallback;
    private final Method builderBuild;
    private final Method sendPacks;
    private final Method legacy;
    private final Object legacySerializer;
    private final Class<?> callbackType;
    private final Method intermediate;

    private PaperPreJoin(Source source, Logger logger) throws ReflectiveOperationException {
        this.source = source;
        this.logger = logger;
        Class<?> event = Class.forName(
                "io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent");
        Class<?> connection = Class.forName("io.papermc.paper.connection.PlayerConfigurationConnection");
        Class<?> profile = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
        Class<?> audience = Class.forName("net.kyori.adventure.audience.Audience");
        Class<?> info = Class.forName("net.kyori.adventure.resource.ResourcePackInfo");
        Class<?> request = Class.forName("net.kyori.adventure.resource.ResourcePackRequest");
        Class<?> requestLike = Class.forName("net.kyori.adventure.resource.ResourcePackRequestLike");
        Class<?> builder = Class.forName("net.kyori.adventure.resource.ResourcePackRequest$Builder");
        Class<?> abstractBuilder = Class.forName("net.kyori.adventure.builder.AbstractBuilder");
        Class<?> component = Class.forName("net.kyori.adventure.text.Component");
        Class<?> status = Class.forName("net.kyori.adventure.resource.ResourcePackStatus");
        Class<?> serializer = Class.forName(
                "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
        callbackType = Class.forName("net.kyori.adventure.resource.ResourcePackCallback");

        getConnection = event.getMethod("getConnection");
        getAudience = connection.getMethod("getAudience");
        getProfile = connection.getMethod("getProfile");
        profileId = profile.getMethod("getId");
        packInfo = info.getMethod("resourcePackInfo", UUID.class, URI.class, String.class);
        requestBuilder = request.getMethod("resourcePackRequest");
        builderPacks = builder.getMethod("packs", Iterable.class);
        builderRequired = builder.getMethod("required", boolean.class);
        builderReplace = builder.getMethod("replace", boolean.class);
        builderPrompt = builder.getMethod("prompt", component);
        builderCallback = builder.getMethod("callback", callbackType);
        builderBuild = abstractBuilder.getMethod("build");
        sendPacks = audience.getMethod("sendResourcePacks", requestLike);
        legacySerializer = serializer.getMethod("legacySection").invoke(null);
        legacy = serializer.getMethod("deserialize", String.class);
        intermediate = status.getMethod("intermediate");
    }

    /**
     * Listens for connections being configured, where the server has the
     * event.
     *
     * @return whether it does; false means the packs go at join
     */
    @SuppressWarnings("unchecked")
    public static boolean register(Plugin plugin, Source source) {
        PaperPreJoin hook;
        Class<?> event;
        try {
            event = Class.forName(
                    "io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent");
            hook = new PaperPreJoin(source, plugin.getLogger());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
        if (!Event.class.isAssignableFrom(event)) {
            return false;
        }
        plugin.getServer().getPluginManager().registerEvent((Class<? extends Event>) event, hook,
                EventPriority.NORMAL, (listener, fired) -> {
                    if (event.isInstance(fired)) {
                        hook.configure(fired);
                    }
                }, plugin);
        return true;
    }

    /** Sends the packs and waits for the client's answer to each. On Paper's connection thread. */
    private void configure(Object event) {
        UUID player;
        Object audience;
        try {
            Object connection = getConnection.invoke(event);
            player = (UUID) profileId.invoke(getProfile.invoke(connection));
            audience = getAudience.invoke(connection);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.FINE, "Could not read a configuring connection", e);
            return;
        }
        if (player == null) {
            return;
        }
        List<PackDelivery.PreJoin> plan = source.plan(player);
        if (plan.isEmpty()) {
            return;
        }
        Map<UUID, String> statuses = new ConcurrentHashMap<>();
        java.util.Set<UUID> finished = ConcurrentHashMap.newKeySet();
        CountDownLatch answered = new CountDownLatch(plan.size());
        try {
            List<Object> infos = new ArrayList<>();
            for (PackDelivery.PreJoin item : plan) {
                infos.add(packInfo.invoke(null, item.pack().uuid(), URI.create(item.url()), item.pack().sha1()));
            }
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("packEventReceived") && args != null && args.length == 3) {
                            UUID id = (UUID) args[0];
                            boolean last = !(Boolean) intermediate.invoke(args[1]);
                            statuses.put(id, ((Enum<?>) args[1]).name());
                            if (last && finished.add(id)) {
                                answered.countDown();
                            }
                            return null;
                        }
                        switch (method.getName()) {
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            case "toString":
                                return "RPEngine pre-join callback";
                            default:
                                return null;
                        }
                    });
            Object builder = requestBuilder.invoke(null);
            builderPacks.invoke(builder, infos);
            builderRequired.invoke(builder, source.force());
            builderReplace.invoke(builder, false);
            String prompt = source.prompt();
            if (prompt != null) {
                builderPrompt.invoke(builder, legacy.invoke(legacySerializer, prompt));
            }
            builderCallback.invoke(builder, callback);
            sendPacks.invoke(audience, builderBuild.invoke(builder));
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Could not send the packs before join (send.before-join): " + e
                    + ". They are sent once the player is in, as with it off.");
            source.done(player, List.of(), Map.of());
            return;
        }
        try {
            answered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A pack still downloading is not recorded as held, and the join
        // sends it again; the client has it cached by then if it finished.
        source.done(player, plan, Map.copyOf(statuses));
    }
}
