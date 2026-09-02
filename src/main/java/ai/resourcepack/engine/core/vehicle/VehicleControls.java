package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.core.version.Compatibility;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the driver is asking the vehicle to do.
 *
 * <p><strong>Steering differs between the arms, and that turned out to matter
 * more than the throttle did.</strong> Where the keys can be read, A and D
 * turn the vehicle. Where they cannot, the body turns toward wherever the
 * driver is LOOKING — which works, and costs them their head: a player's body
 * follows their head, so steering by look swings the driver round on every
 * corner and leaves them unable to look anywhere except where they are going.
 *
 * <p>Two arms, and {@link Feature#PLAYER_INPUT} is the fork. The keys a player
 * is holding have been on the wire since long before the engine's floor —
 * the client has always sent them while riding something — but there was no
 * API in front of that packet until 1.21.2, and no accessor until Paper
 * 1.21.4. So this is a fork about what a plugin may <em>ask</em>, not about
 * what the client sends.
 *
 * <p><strong>It is looked up reflectively, and that is deliberate.</strong>
 * {@code Player#getCurrentInput} is Paper's, this plugin compiles against
 * Spigot, and taking paper-api on as a second Bukkit for one method would put
 * two copies of the API in front of the oldest-API audit. The lookup happens
 * once, at startup, and the arm that uses it is only built when it succeeded —
 * which is the same class-boundary rule {@code version-arms.txt} exists for,
 * reached without needing an entry in it because nothing here names a type
 * that might not exist.
 */
public interface VehicleControls extends Listener {

    /** What the driver is asking for, this tick. */
    VehiclePhysics.Demand read(Player driver, VehicleInfo info);

    /** A line for the startup report and {@code /rp info}. */
    String describe();

    /**
     * Called when somebody takes the wheel, so an arm that needs per-driver
     * state can start it.
     *
     * <p>Paired with {@link #forget}, and both are on the interface rather
     * than on the one arm that uses them so the runtime never has to ask which
     * arm it is holding.
     */
    default void take(UUID driver) {
    }

    /**
     * Called when somebody stops driving, so an arm holding per-driver state
     * can drop it.
     *
     * <p>Every arm gets told, including the one with nothing to forget:
     * anything keyed by a player needs clearing wherever that player is let
     * go, and the 0.46.1 audit found two maps that grew for ever because the
     * clearing lived at only one of several such places.
     */
    default void forget(UUID driver) {
    }

    /**
     * The arm for this server.
     *
     * <p>Both checks matter and they are not the same question:
     * {@link Compatibility} says whether the game is new enough, and the
     * method lookup says whether this is Paper. A Spigot server on 1.21.8
     * passes the first and fails the second.
     */
    static VehicleControls forServer(Compatibility compatibility) {
        if (!compatibility.has(Feature.PLAYER_INPUT)) {
            return new Clicks();
        }
        Method currentInput;
        try {
            currentInput = Player.class.getMethod("getCurrentInput");
        } catch (NoSuchMethodException | RuntimeException e) {
            return new Clicks();
        }
        return new Keys(currentInput);
    }

    /**
     * Paper 1.21.4 and up: the keys the driver is actually holding.
     *
     * <p>W and S are the throttle, A and D turn the vehicle, space is the
     * handbrake on the ground and the climb in the air.
     *
     * <p><strong>A and D steer here and cannot on the other arm, and that is
     * the whole reason this arm is worth having beyond the throttle.</strong>
     * Steering by look means steering IS turning your head, and a player's
     * body follows their head — so the driver visibly swings round on every
     * corner. Reading the keys leaves their head out of it. This was written
     * off early on the grounds that A and D are strafe keys and a vehicle
     * answering them would slide sideways; that is true of moving the PLAYER
     * and irrelevant to turning the vehicle, which is what they are consumed
     * for here.
     *
     * <p>There is no "down" key, which is vanilla's own limitation on its own
     * flying multi-seat vehicle: sneak dismounts. Look down and go forward.
     */
    final class Keys implements VehicleControls {

        private final Method currentInput;

        /**
         * Resolved off the value {@code getCurrentInput()} returns rather than
         * from a named type, so this compiles and runs without ever mentioning
         * a Paper class. Filled on the first read and then constant.
         */
        private volatile Method forward;
        private volatile Method backward;
        private volatile Method jump;
        private volatile Method left;
        private volatile Method right;

        Keys(Method currentInput) {
            this.currentInput = currentInput;
        }

        @Override
        public VehiclePhysics.Demand read(Player driver, VehicleInfo info) {
            float yaw = driver.getLocation().getYaw();
            float pitch = driver.getLocation().getPitch();
            try {
                Object input = currentInput.invoke(driver);
                if (input == null) {
                    return VehiclePhysics.Demand.idle(yaw);
                }
                if (forward == null) {
                    Class<?> type = input.getClass();
                    forward = type.getMethod("isForward");
                    backward = type.getMethod("isBackward");
                    jump = type.getMethod("isJump");
                    left = type.getMethod("isLeft");
                    right = type.getMethod("isRight");
                    forward.setAccessible(true);
                    backward.setAccessible(true);
                    jump.setAccessible(true);
                    left.setAccessible(true);
                    right.setAccessible(true);
                }
                boolean ahead = (Boolean) forward.invoke(input);
                boolean astern = (Boolean) backward.invoke(input);
                boolean up = (Boolean) jump.invoke(input);
                boolean port = (Boolean) left.invoke(input);
                boolean starboard = (Boolean) right.invoke(input);
                double throttle = (ahead ? 1 : 0) + (astern ? -1 : 0);
                double steer = (starboard ? 1 : 0) + (port ? -1 : 0);
                boolean air = info.medium() == VehicleMedium.AIR;
                return VehiclePhysics.Demand.steering(
                        yaw, pitch, steer, throttle, air && up ? 1 : 0, !air && up);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Whatever went wrong, the vehicle still steers and coasts.
                // Failing to a stationary vehicle somebody can still point
                // where they want beats throwing inside a tick loop that is
                // running for every driver on the server.
                return VehiclePhysics.Demand.idle(yaw);
            }
        }

        @Override
        public String describe() {
            return "W and S to drive, A and D to steer, space to brake or climb";
        }
    }

    /**
     * Everything else: the throttle is a notch you click up and down.
     *
     * <p>Not "hold right-click to accelerate", which is the obvious design and
     * does not work: a held right mouse button is not something the server is
     * told about. Only discrete clicks arrive, so the control has to be
     * discrete — and a notch that stays where it was put is honest about that
     * rather than being a throttle that only responds while you keep clicking.
     *
     * <p>Sneak is not a brake here and must not become one: sneak is vanilla's
     * dismount, so a driver braking would step off at speed.
     */
    final class Clicks implements VehicleControls, Listener {

        /** How much one click moves the throttle. Four clicks from a stop to full. */
        static final double NOTCH = 0.25;

        private final Map<UUID, Double> throttles = new ConcurrentHashMap<>();

        @Override
        public VehiclePhysics.Demand read(Player driver, VehicleInfo info) {
            float yaw = driver.getLocation().getYaw();
            float pitch = driver.getLocation().getPitch();
            double throttle = throttles.getOrDefault(driver.getUniqueId(), 0d);
            // The notch doubles as the climb: an air vehicle goes where the
            // driver looks, so there is no second control to invent.
            return new VehiclePhysics.Demand(yaw, pitch, throttle, 0, false);
        }

        @Override
        public void forget(UUID driver) {
            throttles.remove(driver);
        }

        @Override
        public String describe() {
            return "right-click to speed up, left-click to slow down, look to steer";
        }

        /**
         * A click from somebody who is driving moves their throttle.
         *
         * <p>Cancelled, so the click does not also swing a sword, place a
         * block or eat: the driver has an item in their hand and every one of
         * those would fire while they were driving.
         */
        @EventHandler(ignoreCancelled = true)
        public void onClick(PlayerInteractEvent event) {
            Double current = throttles.get(event.getPlayer().getUniqueId());
            if (current == null) {
                return;
            }
            double step;
            switch (event.getAction()) {
                case RIGHT_CLICK_AIR:
                case RIGHT_CLICK_BLOCK:
                    step = NOTCH;
                    break;
                case LEFT_CLICK_AIR:
                case LEFT_CLICK_BLOCK:
                    step = -NOTCH;
                    break;
                default:
                    return;
            }
            event.setCancelled(true);
            throttles.put(event.getPlayer().getUniqueId(),
                    Math.max(-1, Math.min(1, current + step)));
        }

        /**
         * Starts somebody at a standstill, and is what makes their clicks
         * count at all.
         *
         * <p>A driver with no entry is not driving, which is how the listener
         * above stays out of the way of every other click on the server
         * without asking the vehicle registry anything.
         */
        @Override
        public void take(UUID driver) {
            throttles.put(driver, 0d);
        }
    }
}
