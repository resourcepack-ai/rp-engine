package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleInput;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleState;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * How a vehicle moves, as arithmetic.
 *
 * <p><strong>Pure, and deliberately so.</strong> Nothing here touches Bukkit,
 * reads a block or knows what a world is: it takes where the vehicle is going,
 * what the driver is asking for and what is around it, and returns the next
 * state and the displacement to try. Everything about the WORLD — is there
 * ground under it, is it in water, did it hit a wall, how high is the ground
 * under each wheel — is the runtime's job and arrives as {@link Surroundings}.
 *
 * <p>That split is what makes any of this testable. A vehicle that accelerates
 * wrongly, coasts for ever or sinks through the floor is a defect in a few
 * lines of arithmetic, and the alternative — finding it by driving a car around
 * a test server — is how these systems end up with numbers nobody dares touch.
 *
 * <h2>The model</h2>
 *
 * <p>This is a <em>handling</em> model, of the kind an arcade driving game
 * runs, and it replaced a kinematic one (2026-09-07) that moved the vehicle
 * along its heading at a speed and swung the heading at a fixed rate. That
 * older model had no sideways velocity, so a vehicle could not slide, no
 * grip, so it could not lose it, and no attitude, so it took a kerb as a
 * one-block hop with the body dead level. Everything that made it feel like a
 * marker being dragged across a table comes from those three absences.
 *
 * <p>Three things replace them:
 *
 * <ul>
 *   <li><strong>Velocity is a vector, held in the body's frame</strong> as
 *   {@link State#speed() speed} along the heading and {@link State#slip() slip}
 *   across it. Turning rotates the heading; the velocity stays where it was in
 *   the world, so a slip appears, and the tyres pull it back to zero at a
 *   finite rate — the {@link #GRIP}. Turn harder than the tyres can answer and
 *   the slip grows faster than they can kill it, which IS a slide. Nothing
 *   decides "now it drifts": it falls out of the arithmetic.</li>
 *   <li><strong>The heading comes from the front wheels, not from the
 *   driver.</strong> The steer input sets a wheel angle, the wheel angle and
 *   the speed set a yaw rate by the bicycle model ({@code v tan δ / L}), and
 *   the body follows that rate with some inertia. A car at walking pace turns
 *   slowly and a car at speed turns sharply, and the front tyres cap how
 *   sharply before they too give up — which is understeer, and the reason a
 *   fast car goes wide rather than spinning.</li>
 *   <li><strong>The body has an attitude</strong> — {@link State#pitch()},
 *   {@link State#roll()} and a {@link State#lift() height} — hung on a spring
 *   under it, aimed at the ground the four wheels are actually on (the
 *   runtime samples it) and nudged by what the vehicle is doing: the nose
 *   dips under braking, the body rolls out of a corner, a two-wheeler leans
 *   into one. The physics position never tilts; this is what the model and
 *   the seats are drawn at, so a car can be nose-up on a kerb while the
 *   collision box that decides whether it fits stays a plain box.</li>
 * </ul>
 *
 * <p>The one thing to hold in mind when changing a constant here: the driver
 * cannot feel any of it directly. Their throttle is a server tick behind
 * whatever they pressed, so what they judge the vehicle by is the CURVE, not
 * the moment. A vehicle that reaches its speed in two ticks feels broken even
 * though it is doing exactly what it was asked.
 */
public final class VehiclePhysics {

    /**
     * Downward acceleration, blocks per second squared.
     *
     * <p>Gentler than vanilla's (about 32 for a falling player) on purpose: a
     * car dropping off a kerb at vanilla gravity slams into the ground hard
     * enough to read as a bug, and nothing here is trying to simulate a fall.
     */
    public static final double GRAVITY = 28;

    /** As fast as anything falls, blocks per second. */
    public static final double TERMINAL_FALL = 30;

    /**
     * How fast a land vehicle that {@link VehicleInfo#jumps() jumps} leaves
     * the ground, blocks per second.
     *
     * <p>Against {@link #GRAVITY} that is a hop of about a block and a half —
     * a kerb, a fence, a one-block gap — which is what a bike or a board
     * does, and short of what turns every road into a trampoline. Only read
     * when the vehicle is on the ground: it cannot jump again mid-air.
     */
    public static final double JUMP_SPEED = 9;

    /**
     * How much of its top speed a vehicle does in reverse.
     *
     * <p>Named rather than folded into the maths because it is the kind of
     * number somebody will want to change, and a bare 0.4 in an expression is
     * the kind of number nobody can.
     */
    public static final double REVERSE_FRACTION = 0.4;

    /** Braking is this much harder than accelerating. */
    public static final double BRAKE_MULTIPLIER = 2.5;

    /**
     * Below this forward speed the back key stops braking and starts
     * reversing, in blocks per second.
     *
     * <p>Not zero, because {@code approach} lands exactly on zero only if a
     * tick's step happens to divide the remaining speed — so a threshold of
     * zero is a vehicle that brakes to a crawl and sits there refusing to
     * reverse. Half a block a second is slow enough that the changeover is
     * indistinguishable from stationary.
     */
    public static final double REVERSE_THRESHOLD = 0.5;

    /** With no throttle at all, a vehicle sheds speed at this fraction of its acceleration. */
    public static final double COAST_FRACTION = 0.35;

    /**
     * The least a vehicle sheds with no throttle, in blocks per second per
     * second, whatever its acceleration and weight.
     *
     * <p>A slow, heavy vehicle's coast rate is a small fraction of a small
     * number, and without a floor a tractor rolled the length of a field
     * after its driver let go. Four is a road vehicle rolling to a halt.
     */
    public static final double COAST_FLOOR = 4;

    /** The least braking does, on the same argument as {@link #COAST_FLOOR}. */
    public static final double BRAKE_FLOOR = 10;

    /**
     * The weight at which a vehicle accelerates at exactly its stated rate.
     *
     * <p>Weight is 1–100 in the format, and it scales the acceleration around
     * this figure: a 20 accelerates at half its number, a 5 at double.
     */
    public static final double NOMINAL_WEIGHT = 10;

    /** How hard water pushes a submerged hull up, blocks per second squared per block of submersion. */
    public static final double BUOYANCY = 26;

    /** How much of its vertical speed a hull keeps each tick — water is thick. */
    public static final double WATER_DAMPING = 0.75;

    /**
     * How fast an air vehicle climbs or dives at full lift, as a fraction of
     * its top speed. Kept for the flight-block defaults; see
     * {@link ai.resourcepack.engine.api.VehicleFlight#forSpeed}.
     */
    public static final double AIR_CLIMB_FRACTION =
            ai.resourcepack.engine.api.VehicleFlight.LEGACY_CLIMB_FRACTION;

    /**
     * How an aircraft off the ground sheds speed with no throttle, as a
     * fraction of its acceleration — and, unlike the ground coast, with no
     * floor under it.
     *
     * <p>The ground rate is floored at {@link #COAST_FLOOR} because a road
     * vehicle rolls to a halt. In mid-air that took a cruising plane to a dead
     * stop in a few seconds and left it hovering there; and since the back key
     * up there sets the throttle to nothing and becomes the descent, whatever
     * this rate is IS what diving feels like.
     */
    public static final double AIR_COAST_FRACTION = 0.15;

    /**
     * How much of its top speed a hull does out of the water.
     *
     * <p>A seventh — a shade slower than walking. Not zero, deliberately: a
     * boat that cannot move at all on land is stuck on the first shore it
     * touches, for ever. Slow enough to be unmistakably wrong, fast enough to
     * get off the sand.
     */
    public static final double BEACHED_FRACTION = 0.15;

    /**
     * Below this speed, in either direction, a vehicle counts as idle.
     *
     * <p>Half a block a second: slow enough that a vehicle at that speed is
     * visibly stopped, and not zero because nothing here lands on zero.
     */
    public static final double MOVING_THRESHOLD = 0.5;

    /**
     * How fast the heading has to change, degrees per second, before the
     * vehicle is turning rather than merely straightening up.
     */
    public static final double TURNING_THRESHOLD = 15;

    // --- handling -----------------------------------------------------

    /**
     * How far the front wheels turn at a crawl, degrees either side.
     *
     * <p>A real car's is 30–40. It only applies at a standstill: it fades with
     * speed (see {@link #STEERING_FADE}), which is what every driving game
     * does and every real car's driver does for it — at 100 km/h nobody turns
     * the wheel to the stop.
     */
    public static final double STEERING_LOCK = 38;

    /**
     * How much the lock has faded by top speed: the wheels then turn
     * {@code lock / (1 + fade)} either side.
     *
     * <p>Without it a car at speed answers a tap of the key with a swing its
     * tyres cannot hold, and everything above a jog becomes a slide.
     */
    public static final double STEERING_FADE = 1.8;

    /**
     * How quickly the wheels go where the key asks, per second — a fraction
     * of the remaining angle each second, so about a fifth of a second to
     * most of the way.
     */
    public static final double STEER_RATE = 8;

    /** How quickly they come back to centre when the key is let go. Faster than {@link #STEER_RATE}, as a real wheel does. */
    public static final double STEER_RETURN = 11;

    /**
     * The sideways acceleration the tyres can hold, blocks per second squared.
     *
     * <p>This is the number that decides whether a corner is a corner or a
     * slide, and 24 is chosen so that an ordinary car's full lock at about
     * half its top speed is right at the edge — brisk enough to have fun with,
     * held enough that nobody driving normally ever slides. It is also what
     * pulls a slide back in once the driver eases off.
     */
    public static final double GRIP = 24;

    /**
     * What is left of {@link #GRIP} once the tyres are already sliding.
     *
     * <p>Moving rubber holds less than rubber that has bitten, which is why a
     * slide, once started, is easier to keep going than it was to start. It
     * is also what gives a drift its shape: the tyres let go, the car swings,
     * and it comes back only as the slide slows.
     */
    public static final double SLIDING_GRIP = 0.9;

    /**
     * What the REAR tyres hold, as a fraction of {@link #GRIP}, while the
     * front are what the steering limit is measured against.
     *
     * <p>Very slightly less than the front, and that shade is the whole of
     * how a car held at the limit of a long corner gradually gets its tail
     * out: the front asks for exactly what tyres can give, the rear gives a
     * touch less, and the difference accrues as slip. Equal and no car here
     * could ever slide without the handbrake; much less and every corner is
     * a spin.
     */
    public static final double REAR_GRIP = 0.92;

    /**
     * How much further round the front can pull the car with the handbrake
     * on, as a multiple of the ordinary limit: the rear is no longer holding
     * it straight, so the same front force turns it harder.
     */
    public static final double HANDBRAKE_TURN = 1.3;

    /**
     * How much of the foot brake's rate the handbrake has. Two locked rear
     * wheels stop a car less than four braked ones, and a handbrake that
     * stopped the car dead would be a brake, not a way of turning it.
     */
    public static final double HANDBRAKE_BRAKING = 0.5;

    /**
     * What is left of {@link #GRIP} with the handbrake on.
     *
     * <p>The handbrake locks the rear wheels, and a locked wheel has no
     * sideways grip to speak of. This is the drift button: throw the car into
     * a corner, pull it, and the back comes round.
     */
    public static final double HANDBRAKE_GRIP = 0.22;

    /** Sideways speed, blocks per second, above which the tyres count as sliding. */
    public static final double SLIDE_THRESHOLD = 1.5;

    /**
     * How much a sliding rear rotates the car on its own, degrees per second
     * of extra yaw per block-per-second of slip.
     *
     * <p>A car whose rear has let go does not merely fail to turn — the back
     * steps out and the nose comes round further than the wheels asked. This
     * is that, and it is what makes a handbrake turn swing rather than skid
     * straight on. Too high and every slide is a spin; the sign is chosen so
     * that the slide feeds the rotation that caused it.
     */
    public static final double DRIFT_YAW = 10;

    /**
     * Sideways speed, blocks per second, above which the rear counts as
     * properly out and {@link #DRIFT_YAW} joins in — unless the handbrake is
     * on, in which case it joins in at once.
     *
     * <p>Higher than {@link #SLIDE_THRESHOLD} on purpose. The extra rotation
     * feeds on the slip that causes it, so applied from the first inch of
     * slide it turned every long corner into a spin. Above this the car IS
     * sliding, the driver knows it, and the swing is what they are steering
     * against; below it the tail creeps rather than steps.
     */
    public static final double DRIFT_THRESHOLD = 6;

    /**
     * How quickly the body's yaw rate follows what the wheels ask, per second,
     * while the tyres are gripping.
     */
    public static final double YAW_RESPONSE = 14;

    /**
     * The same while the rear is sliding and the wheels are asking for LESS
     * rotation than the car has. Much lower: a car that has let go carries
     * its rotation, which is what a driver is counter-steering against, and a
     * slide that answered the wheel instantly would not be one. Asking for
     * more still gets the gripping rate — the front tyres are what start the
     * swing and they have not let go.
     */
    public static final double YAW_RESPONSE_SLIDING = 3.5;

    /** How a vehicle's spin decays in mid-air, per second. Nearly not at all. */
    public static final double YAW_RESPONSE_AIRBORNE = 0.6;

    /** A hull's sideways grip. Water holds almost nothing, which is why a boat goes wide. */
    public static final double WATER_GRIP = 5;

    /** A hull's yaw response. It also swings slowly. */
    public static final double WATER_YAW_RESPONSE = 4;

    /**
     * How much forward speed a slide costs, per second, per block-per-second
     * of slip: tyres dragging sideways are tyres not rolling.
     */
    public static final double SCRUB = 0.4;

    /**
     * How much of gravity's along-slope component a land vehicle feels.
     *
     * <p>Whole gravity on a 45-degree stair is 20 blocks per second squared,
     * more than most vehicles' acceleration, and a slope nothing here could
     * climb is a bug rather than realism. A little over half is enough that a
     * hill is a hill — slower up, faster down — and a staircase still goes.
     */
    public static final double SLOPE_FACTOR = 0.6;

    /**
     * The fraction of a step's height that comes off the speed, per block.
     *
     * <p>A kerb at speed is a jolt; the vehicle should feel it. A one-block
     * step costs a fifth, a slab a tenth.
     */
    public static final double STEP_SCRUB = 0.2;

    /**
     * How much of the speed survives being deflected along a wall.
     *
     * <p>The component into the wall is gone entirely; this is what the
     * component along it keeps. Scraping a wall is not free.
     */
    public static final double WALL_SLIDE_KEEP = 0.85;

    /**
     * How far the wheel angle reaches when steering by look, degrees of
     * heading error for full lock. Where the keys cannot be read, the wheels
     * turn toward the driver's look in proportion to how far off it is.
     */
    public static final double LOOK_STEER_RANGE = 40;

    /**
     * Hitbox width at or below which a vehicle is a two-wheeler.
     *
     * <p>A bike leans INTO a corner and a car rolls OUT of one, and the format
     * has no field to say which a vehicle is. Nothing on four wheels is under
     * a block wide, and no bike is over one, so the box says.
     */
    public static final double NARROW = 0.9;

    // --- the body on its springs ---------------------------------------

    /** How stiff the attitude spring is: the square of its natural frequency, per second squared. */
    public static final double SUSPENSION_STIFFNESS = 110;

    /**
     * How the attitude spring is damped, per second. A little under critical
     * so a kerb is a visible bounce and not a slide into place.
     */
    public static final double SUSPENSION_DAMPING = 11.5;

    /** The height spring is stiffer than the attitude one: a body that floated up a kerb over a second would look like a boat. */
    public static final double RIDE_STIFFNESS = 170;

    /** And damped to match. */
    public static final double RIDE_DAMPING = 15;

    /** Nose-up degrees per block per second squared of forward acceleration: squat and dive. */
    public static final double SQUAT = 0.3;

    /**
     * How fast a vehicle has to be going, blocks a second, to hold itself on a
     * wall.
     *
     * <p>Deliberately LOW. It was four and a half, on the theory that a wall
     * ride should feel earned, and the result was a trick nobody could start:
     * a rider cannot tell by eye whether they are doing four or five blocks a
     * second, so every attempt failed for a reason they could not see. Speed
     * still matters - it is what a ride runs OUT of, and a slow one ends
     * almost at once - but running out of it is far better feedback than
     * being refused.
     */
    public static final double WALL_RIDE_MIN_SPEED = 2.0;

    /**
     * How far off parallel a vehicle may hit a wall and still ride it,
     * degrees.
     *
     * <p>Only a square-on hit is refused now. Forty-five was the first answer
     * and it was wrong for the same reason the speed was: a rider cannot see
     * the angle they are carrying, so a near miss is indistinguishable from
     * the mechanic being broken. The heading is snapped onto the wall the
     * moment a ride starts, so a steep approach simply becomes a ride that
     * begins with a hard turn - which is what a wall ride looks like anyway.
     */
    public static final double WALL_RIDE_MAX_ANGLE = 80;

    /** How far the body rolls over onto the wall, degrees. Nearly flat against it. */
    public static final double WALL_RIDE_ROLL = 80;

    /** How hard the wall scrubs speed off, blocks per second per second. */
    public static final double WALL_RIDE_DRAG = 0.8;

    /**
     * How fast a wall ride sinks, blocks a second.
     *
     * <p>Not zero: a ride that held its height would end only when the wall or
     * the speed did, and every one of them would look the same. Sliding gently
     * down means a long one finishes on the floor, where it started.
     */
    public static final double WALL_RIDE_SINK = 0.7;

    /** How quickly the heading is pulled onto the wall's line, per second. */
    public static final double WALL_RIDE_ALIGN = 6.0;

    /**
     * How much of the sink the steering can trim away, blocks a second.
     *
     * <p>The steering has nothing to do on a wall — the heading is the wall's
     * — so it becomes the one control a wall ride has: lean into the wall and
     * you hold your line up it, lean off and you come down it. Bigger than
     * {@link #WALL_RIDE_SINK}, so full lock into the wall climbs rather than
     * merely stops falling.
     */
    public static final double WALL_RIDE_CLIMB = 4.5;

    /** How much of its top speed a vehicle will drive to along a wall. */
    public static final double WALL_RIDE_DRIVE = 0.8;

    /**
     * How much harder than usual the throttle pulls on a wall.
     *
     * <p>A skateboard has almost no throttle by design - it is pushed, and you
     * cannot push while your board is against a wall - so without this a wall
     * ride is whatever momentum you arrived with, decaying. This is the
     * "assist" in wall ride assist: the wall holds you up, the throttle keeps
     * you going along it.
     */
    public static final double WALL_RIDE_PUSH = 6.0;

    /** How hard kicking off a wall throws you away from it, blocks a second. */
    public static final double WALL_RIDE_KICK = 5.0;

    /** Degrees of body roll per block per second squared of cornering, for a car. Outward. */
    public static final double BODY_ROLL = 0.42;

    /** Degrees of lean per block per second squared of cornering, for a two-wheeler. Inward, and much more of it. */
    public static final double LEAN = 1.6;

    /** As far as a two-wheeler leans. */
    public static final double MAX_LEAN = 48;

    /** As far as the ground can tilt the body. Past this the wheel samples are lying — a ledge, not a slope. */
    public static final double MAX_TILT = 45;

    /** How far a wheel with nothing under it hangs, blocks. */
    public static final double WHEEL_DROOP = 1.0;

    /** How much of a landing's speed goes into compressing the springs. */
    public static final double LANDING_COMPRESSION = 0.35;

    /**
     * How much of the hitbox's length the wheels span. The box is the
     * bodywork; the axles sit inside its ends.
     */
    public static final double WHEELBASE_FRACTION = 0.7;

    /** And of its width. */
    public static final double TRACK_FRACTION = 0.8;

    /** No axle is closer together than this, whatever the box says. A tiny box is a trolley, not a car that spins on a point. */
    public static final double MIN_WHEELBASE = 1.0;

    private VehiclePhysics() {
    }

    /**
     * One tick.
     *
     * @param info   the vehicle
     * @param state  where it is and what it is doing
     * @param demand what the driver is asking for
     * @param around what the world is doing to it
     * @param dt     seconds per tick
     */
    public static Step step(VehicleInfo info, State state, Demand demand, Surroundings around, double dt) {
        boolean air = info.medium() == VehicleMedium.AIR;
        boolean water = info.medium() == VehicleMedium.WATER;
        boolean flying = air && !around.supported();
        // A land vehicle in the air, or a hull that has left both ground and
        // water: nothing to steer against and nothing to grip.
        boolean airborne = !air && !around.supported() && !around.inWater();

        // The wall this vehicle is riding, if it is. Decided in the runtime,
        // where the block reads are — see Surroundings.wall — so everything
        // here is "am I on one", never "is there one".
        Wall wall = around.wall();

        double throttle = demand.throttle();
        double lift = demand.lift();

        // <strong>An aircraft in the air descends on the back key rather than
        // reversing.</strong> There is nothing to reverse against up there, and
        // an aeroplane that flew backwards on S would be the only vehicle here
        // that did something no real one does. On the GROUND it reverses like
        // anything else, which is what taxiing is.
        //
        // Space wins if both are held: asking to climb and to descend at once
        // is asking to climb, and the alternative is a cancellation nobody can
        // see the cause of.
        if (flying && throttle < 0) {
            if (lift == 0) {
                lift = throttle;
            }
            throttle = 0;
        }

        double heaviness = Math.max(0.1, info.weight() / NOMINAL_WEIGHT);
        double accel = info.acceleration() / heaviness;
        double top = beached(info, around) ? info.speed() * BEACHED_FRACTION : info.speed();

        // --- steering: the wheels -------------------------------------

        double steerInput = steerInput(state, demand);
        if (!steers(info, state, around)) {
            steerInput = 0;
        }
        double lock = STEERING_LOCK / (1 + STEERING_FADE * Math.min(1, Math.abs(state.speed()) / Math.max(top, 1e-6)));
        double wantedSteer = steerInput * lock;
        boolean returning = Math.abs(wantedSteer) < Math.abs(state.steer());
        double steer = state.steer()
                + (wantedSteer - state.steer()) * Math.min(1, (returning ? STEER_RETURN : STEER_RATE) * dt);

        // --- steering: the body ---------------------------------------

        double wheelbase = wheelbase(info.hitbox());
        boolean handbrake = demand.braking() && !air;
        boolean rearSliding = !air && (handbrake || Math.abs(state.slip()) > SLIDE_THRESHOLD);
        double grip = water ? WATER_GRIP : GRIP;

        double wantedYawRate;
        double response;
        if (flying) {
            // An aircraft points itself: no wheels, no grip, the pack's rate.
            wantedYawRate = steerInput * info.turnSpeed();
            response = YAW_RESPONSE;
        } else if (airborne) {
            // A jump keeps whatever spin it left the ground with.
            wantedYawRate = 0;
            response = YAW_RESPONSE_AIRBORNE;
        } else {
            // The bicycle model: the front wheels at angle δ drag a body of
            // length L round at v tan δ / L. In degrees, and capped at the
            // pack's turn-speed, which is what that number now means: the most
            // the body will swing however hard it is asked.
            double kinematic = Math.toDegrees(state.speed() * Math.tan(Math.toRadians(steer)) / wheelbase);
            kinematic = clampMagnitude(kinematic, info.turnSpeed());
            // The front tyres can only pull the nose round so hard. Beyond
            // this they slide and the car goes wide — understeer, which is
            // what keeps a fast car from spinning every time it turns.
            double frontLimit = Math.toDegrees(grip * (handbrake ? HANDBRAKE_TURN : 1)
                    / Math.max(Math.abs(state.speed()), 0.5));
            kinematic = clampMagnitude(kinematic, frontLimit);

            // Something that pivots on the spot turns at its full rate from
            // a standstill and blends into the wheel model as it gets going.
            if (info.turnInPlace()) {
                double still = 1 - Math.min(1, Math.abs(state.speed()) / 3);
                double pivot = steerInput * info.turnSpeed() * still;
                if (Math.abs(pivot) > Math.abs(kinematic)) {
                    kinematic = pivot;
                }
            }

            wantedYawRate = kinematic;
            if (handbrake || Math.abs(state.slip()) > DRIFT_THRESHOLD) {
                // The back has let go and steps out, so the nose comes round
                // further than the wheels asked. Slip is positive when the
                // velocity lies to the RIGHT of the nose, which is a car that
                // has turned LEFT harder than it is travelling — so the extra
                // rotation is to the left, which in this yaw (clockwise
                // positive) is negative.
                wantedYawRate -= DRIFT_YAW * state.slip();
                wantedYawRate = clampMagnitude(wantedYawRate, 1.5 * info.turnSpeed());
            }
            boolean easing = Math.abs(wantedYawRate) < Math.abs(state.yawRate())
                    || Math.signum(wantedYawRate) != Math.signum(state.yawRate());
            response = water ? WATER_YAW_RESPONSE
                    : rearSliding && easing ? YAW_RESPONSE_SLIDING
                    : YAW_RESPONSE;
            // A heavy vehicle swings more slowly. Around the nominal weight,
            // and softly: a bus is not a battleship.
            response /= Math.sqrt(Math.max(0.5, heaviness));
        }
        double yawRate = state.yawRate() + (wantedYawRate - state.yawRate()) * Math.min(1, response * dt);
        double wasYaw = state.yaw();
        double yaw = wrap360(wasYaw + yawRate * dt);

        // A wall straightens what is riding it. The steering above still ran,
        // because a rider leaning off the wall is what ends a ride early; this
        // is on top of it, and it is why an approach only has to be roughly
        // right (WALL_RIDE_MAX_ANGLE) to become exactly right.
        if (wall != null) {
            double off = wrap180(wall.yaw() - yaw);
            yaw = wrap360(yaw + off * Math.min(1, WALL_RIDE_ALIGN * dt));
            yawRate = 0;
        }

        // --- the velocity, now the body has turned under it -------------

        // The velocity is a world vector; the body turned and it did not. So
        // read it back in the new frame, and the difference is slip.
        double[] velocity = worldVelocity(wasYaw, state.speed(), state.slip());
        double speed = dot(velocity, forward(yaw));
        double slip = air ? 0 : dot(velocity, right(yaw));

        // --- along the heading ------------------------------------------

        double target = throttle >= 0
                ? throttle * top
                : throttle * top * REVERSE_FRACTION;

        // <strong>The back key brakes before it reverses.</strong> Holding it at
        // speed used to aim straight at the reverse target, so a vehicle doing
        // 20 forward crawled down through zero at ordinary acceleration and
        // then kept going — which reads as a car that will not stop rather
        // than one changing direction. Now it stops the way a brake does and
        // only engages reverse once it is actually stationary, which is also
        // what a real gearbox makes you do.
        boolean stopping = throttle < 0 && speed > REVERSE_THRESHOLD;
        if (stopping) {
            target = 0;
        }

        // Braking beats the throttle rather than being averaged with it: a
        // driver holding both is asking to stop, and half of each would be a
        // vehicle that neither accelerates nor stops.
        // Slowing down never falls below the floors — see COAST_FLOOR. The
        // acceleration-relative rates still apply to a vehicle that is quick
        // enough for them to exceed the floor, so a sports car brakes harder
        // than a cart; a tractor just no longer slides.
        //
        // The drive itself is strongest off the line and tails off toward top
        // speed — five quarters of the stated acceleration at a standstill,
        // half of it at the top — because a constant rate reads as an
        // escalator, and every engine anybody has driven pulls hardest low
        // down.
        double fraction = Math.min(1, Math.abs(speed) / Math.max(top, 1e-6));
        double drive = accel * (1.25 - 0.75 * fraction);
        double rate = demand.braking() || stopping
                ? Math.max(BRAKE_FLOOR, accel * BRAKE_MULTIPLIER) * (handbrake && !stopping ? HANDBRAKE_BRAKING : 1)
                : throttle == 0
                        // An aircraft off the ground coasts on drag alone — see
                        // AIR_COAST_FRACTION. This is what keeps a plane's
                        // momentum through a dive rather than braking it to a
                        // hover: pressing the back key up there sets the
                        // throttle to nothing and turns the key into a descent,
                        // so whatever this rate is IS what "let go" feels like.
                        ? flying ? accel * AIR_COAST_FRACTION : Math.max(COAST_FLOOR, accel * COAST_FRACTION)
                        : drive;
        if (demand.braking()) {
            target = 0;
        }
        if (airborne) {
            // Nothing to push against. The wheels spin; the car does not care.
            target = speed;
            rate = 0;
        }
        double wasSpeed = speed;
        speed = approach(speed, target, rate * dt);
        if (wall != null) {
            // Wood on brick, but barely: a wall ride is meant to be DRIVEN
            // along, not endured. It was three blocks a second of drag and it
            // turned every ride into a two-second slide with no say in it.
            speed = Math.max(0, Math.abs(speed) - WALL_RIDE_DRAG * dt) * Math.signum(speed);
            // And the throttle still works up there. The wall carries the
            // vehicle; the driver decides how fast along it - which is what
            // makes it a ride rather than a cutscene.
            if (demand.throttle() > 0) {
                speed = approach(speed, top * WALL_RIDE_DRIVE, accel * WALL_RIDE_PUSH * dt);
            }
        }

        // A hill. Only once the vehicle is going or the driver is asking it
        // to: a car left on a slope holds, because a parked car has a
        // handbrake, and an empty vehicle quietly rolling into the lake is
        // not the realism anybody wanted.
        double groundPitch = around.groundPitch(wheelbase);
        if (!air && around.supported() && groundPitch != 0
                && (Math.abs(speed) > MOVING_THRESHOLD || throttle != 0)) {
            speed -= GRAVITY * Math.sin(Math.toRadians(groundPitch)) * SLOPE_FACTOR * dt;
        }

        // Tyres dragging sideways are tyres not rolling.
        if (!air && !airborne && slip != 0) {
            speed = approach(speed, 0, SCRUB * Math.abs(slip) * dt);
        }

        // --- across the heading -----------------------------------------

        if (!air) {
            double hold = airborne ? 0
                    : grip * REAR_GRIP * (handbrake ? HANDBRAKE_GRIP : Math.abs(slip) > SLIDE_THRESHOLD ? SLIDING_GRIP : 1);
            slip = approach(slip, 0, hold * dt);
        }

        // --- up and down ------------------------------------------------

        double vertical = state.verticalSpeed();
        double climb = 0;
        switch (info.medium()) {
            case AIR:
                // <strong>Height is space and the back key, not the driver's
                // pitch — where the keys can be read.</strong> Vanilla's own
                // flying vehicle climbs by looking up, because it has no other
                // control to spare; a server that can read a key has one, and
                // steering by look was already costing the driver their head.
                // Tying the climb to it as well would mean glancing at the
                // scenery puts the aircraft into a dive.
                //
                // Where the keys CANNOT be read there is nothing else to use,
                // so that arm keeps vanilla's answer. `steersByKeys` stands in
                // for "this server can read the driver's keys at all", which
                // is the same question by the time it reaches here.
                double looking = demand.steersByKeys()
                        ? 0
                        : speed * Math.sin(Math.toRadians(-demand.pitch()));
                if (airborneEnough(info, speed)) {
                    // Up and down are separate rates because an aircraft does
                    // not descend as slowly as it climbs.
                    climb = looking + (lift >= 0
                            ? lift * info.flight().climbRate()
                            : lift * info.flight().diveRate());
                } else if (around.supported()) {
                    // Too slow to fly, and on the ground: it stays there. This
                    // IS the takeoff run — the aircraft accelerates down the
                    // runway with the climb key doing nothing until it is fast
                    // enough, and then it flies.
                    climb = 0;
                } else {
                    // Too slow to fly, and in the air: a stall. It keeps
                    // whatever speed it has and sinks, so an aircraft that ran
                    // out of throttle comes down rather than parking in
                    // mid-air. Opening the throttle again recovers it, which is
                    // why this is a glide rather than gravity.
                    climb = -info.flight().stallSink();
                }
                vertical = 0;
                break;
            case WATER:
                if (around.inWater()) {
                    // Toward the surface, damped. A hull pushed under rises,
                    // which is what makes going over a waterfall look right
                    // instead of leaving the boat at the height it entered.
                    vertical = (vertical + BUOYANCY * around.submersion() * dt) * WATER_DAMPING;
                } else if (around.supported()) {
                    vertical = 0;
                } else {
                    vertical = fall(vertical, dt);
                }
                break;
            case LAND:
            default:
                // A wall ride carries its own vertical: gravity is what it is
                // beating, and it sinks at its own pace instead. See
                // WALL_RIDE_SINK, and `wall` above for what puts one here.
                if (wall != null) {
                    // Steering leans up or down the wall instead of turning:
                    // toward the wall climbs, away from it drops. See
                    // WALL_RIDE_CLIMB.
                    vertical = -WALL_RIDE_SINK + demand.steer() * wall.side() * WALL_RIDE_CLIMB;
                    break;
                }
                // A land vehicle that jumps does so from the ground and only
                // there: the key is read on the tick it is on something, it
                // leaves with JUMP_SPEED, and from then on it is falling like
                // anything else — no double jump, no climbing on a held key.
                // The control layer has already turned the same key into
                // `lift` rather than `braking` for such a vehicle.
                vertical = around.supported()
                        ? (info.jumps() && lift > 0 ? JUMP_SPEED : 0)
                        : fall(vertical, dt);
                break;
        }

        // --- the body on its springs -------------------------------------

        double longitudinal = (speed - wasSpeed) / dt;
        double lateral = speed * Math.toRadians(yawRate);
        boolean twoWheeler = info.hitbox().width() <= NARROW;

        double pitchTarget;
        double rollTarget;
        double liftTarget;
        if (flying) {
            // The nose follows the climb, and the wings bank into the turn.
            pitchTarget = Math.toDegrees(Math.atan2(climb, Math.max(Math.abs(speed), 1))) * 0.6;
            rollTarget = -clampMagnitude(yawRate * 0.25, 30);
            liftTarget = 0;
        } else if (wall != null) {
            // Over onto the wall, and the nose level: a wall ride is the body
            // lying against something, not an arc through the air.
            //
            // AWAY from the wall, which is the opposite of the obvious sign
            // and the one that reads right. Roll is right-side-down, and a
            // board against a wall on its right has its right edge UP the wall
            // and its left edge down toward the ground - the deck faces out
            // into the air, the wheels face the bricks. Rolled the other way
            // it lies on a wall that is not there, which is precisely what it
            // looked like.
            pitchTarget = 0;
            rollTarget = -WALL_RIDE_ROLL * wall.side();
            liftTarget = state.lift();
        } else if (airborne) {
            // Off the ground: the nose follows the arc, and there is nothing
            // to lean on.
            pitchTarget = Math.toDegrees(Math.atan2(vertical, Math.max(Math.abs(speed), 2))) * 0.5;
            rollTarget = 0;
            liftTarget = state.lift();
        } else {
            pitchTarget = clampMagnitude(groundPitch, MAX_TILT) + clampMagnitude(longitudinal * SQUAT, 12);
            double cornering = twoWheeler
                    ? clampMagnitude(lateral * LEAN, MAX_LEAN)
                    : -clampMagnitude(lateral * BODY_ROLL, 14);
            rollTarget = clampMagnitude(around.groundRoll(track(info.hitbox())), MAX_TILT) + cornering;
            liftTarget = around.groundLift();
        }
        if (!Double.isFinite(pitchTarget)) {
            pitchTarget = 0;
        }
        if (!Double.isFinite(rollTarget)) {
            rollTarget = 0;
        }

        double[] pitchSpring = spring(state.pitch(), state.pitchRate(), pitchTarget,
                SUSPENSION_STIFFNESS, SUSPENSION_DAMPING, dt);
        double[] rollSpring = spring(state.roll(), state.rollRate(), rollTarget,
                SUSPENSION_STIFFNESS, SUSPENSION_DAMPING, dt);
        double[] liftSpring = spring(state.lift(), state.liftRate(), liftTarget,
                RIDE_STIFFNESS, RIDE_DAMPING, dt);

        // --- the move ---------------------------------------------------

        // The horizontal component shrinks as the nose comes up, so a climbing
        // aircraft covers less ground rather than the same ground plus a
        // vertical bonus — the alternative reads as the vehicle speeding up
        // whenever you look at the sky. Only on the arm where the pitch is
        // still flying the thing: where space and the back key do the climbing,
        // the driver's head has nothing to do with how far the aircraft gets.
        double planar = air && !demand.steersByKeys()
                ? speed * Math.cos(Math.toRadians(demand.pitch()))
                : speed;

        double[] move = worldVelocity(yaw, planar, slip);
        double dx = move[0] * dt;
        double dz = move[1] * dt;
        double dy = (air ? climb : vertical) * dt;

        State next = new State(yaw, speed, slip, vertical, steer, yawRate,
                pitchSpring[0], pitchSpring[1], rollSpring[0], rollSpring[1], liftSpring[0], liftSpring[1]);
        return new Step(next, dx, dy, dz, states(info, wasYaw, yaw, speed, around, dt));
    }

    /**
     * A wall a vehicle is riding along.
     *
     * <p>{@code side} is -1 for a wall on the vehicle's left and 1 for one on
     * its right - which is the way it leans - and {@code yaw} is the direction
     * ALONG the wall nearest the way the vehicle was already going, so a board
     * that hit it at fifteen degrees is straightened onto it rather than
     * bouncing off.
     */
    public static final class Wall {

        private final int side;
        private final double yaw;

        public Wall(int side, double yaw) {
            this.side = side < 0 ? -1 : 1;
            this.yaw = wrap360(yaw);
        }

        public int side() {
            return side;
        }

        public double yaw() {
            return yaw;
        }
    }

    /**
     * What the driver's steering amounts to, -1 for full left to 1 for full
     * right.
     *
     * <p>Keys are already that. A look is turned into one by how far off the
     * heading it is: dead ahead is no steer, {@link #LOOK_STEER_RANGE} or
     * more off is full lock. Steering by look therefore aims the vehicle at
     * where the driver is looking and straightens as it gets there, which is
     * what the old model did, at a wheel angle rather than a swing.
     */
    static double steerInput(State state, Demand demand) {
        if (demand.steersByKeys()) {
            return demand.steer();
        }
        double off = wrap180(demand.yaw() - state.yaw());
        return Math.max(-1, Math.min(1, off / LOOK_STEER_RANGE));
    }

    /**
     * Whether the vehicle is steering at all this tick — the whole of the rule
     * that a vehicle only turns while it is moving.
     *
     * <p>The pack asked for it, or the vehicle is doing more than
     * {@link #MOVING_THRESHOLD}, or it is an aircraft off the ground. That
     * last is not a special case being smuggled in: a hovering helicopter is
     * not standing still, pointing itself IS its steering, and holding it to
     * the moving test would leave one that came to a hover unable to turn
     * round and go home.
     *
     * <p>Measured on the speed the vehicle came into the tick with, so the
     * wheel is still there while it brakes to a halt.
     */
    static boolean steers(VehicleInfo info, State state, Surroundings around) {
        if (info.turnInPlace() || Math.abs(state.speed()) > MOVING_THRESHOLD) {
            return true;
        }
        return info.medium() == VehicleMedium.AIR && !around.supported();
    }

    /** Whether an aircraft is going fast enough to fly. Anything that is not an aircraft always is. */
    public static boolean airborneEnough(VehicleInfo info, double speed) {
        return info.medium() != VehicleMedium.AIR
                || Math.abs(speed) >= info.flight().takeoffSpeed();
    }

    /** A hull out of the water. */
    public static boolean beached(VehicleInfo info, Surroundings around) {
        return info.medium() == VehicleMedium.WATER && !around.inWater();
    }

    /** The distance between the axles, from the box. See {@link #WHEELBASE_FRACTION}. */
    public static double wheelbase(VehicleHitbox box) {
        return Math.max(MIN_WHEELBASE, box.length() * WHEELBASE_FRACTION);
    }

    /** The distance between the wheels on one axle, from the box. */
    public static double track(VehicleHitbox box) {
        return Math.max(0.3, box.width() * TRACK_FRACTION);
    }

    /**
     * The states a vehicle is in after a tick — several at once, since a
     * vehicle can be moving and turning and airborne together.
     */
    static Set<VehicleState> states(VehicleInfo info, double wasYaw, double yaw, double speed,
                                    Surroundings around, double dt) {
        Set<VehicleState> active = EnumSet.noneOf(VehicleState.class);

        if (speed > MOVING_THRESHOLD) {
            active.add(VehicleState.MOVING);
        } else if (speed < -MOVING_THRESHOLD) {
            active.add(VehicleState.REVERSING);
        } else {
            active.add(VehicleState.IDLE);
        }

        // The SHORT way round, or a vehicle crossing north from 359 to 1
        // reports a 358-degree turn and every wheel on it spins the wrong way
        // once a lap. Divided by dt so the threshold can be quoted per second
        // like every other constant here.
        if (dt > 0 && Math.abs(wrap180(yaw - wasYaw)) / dt >= TURNING_THRESHOLD) {
            active.add(VehicleState.TURNING);
        }

        if (around.inWater()) {
            active.add(VehicleState.SUBMERGED);
        }

        // An air vehicle is airborne whenever it is off the ground, which is
        // most of its life; a land or water one only when it has left both the
        // ground and the water, which is a jump or a fall. Water counts as
        // support here even though it is not solid: a boat riding the surface
        // is doing its job, not falling.
        if (!around.supported() && !around.inWater()) {
            active.add(VehicleState.AIRBORNE);
        }

        return active;
    }

    /**
     * Where a point {@code right} blocks to the vehicle's right and
     * {@code forward} blocks ahead of it lands in the world, as an x/z offset
     * from the vehicle, for a vehicle facing {@code yaw}.
     *
     * <p>Minecraft's yaw: 0 faces +z (south) and increases clockwise, which
     * puts forward at (-sin, cos) and the right hand at (-cos, -sin). Getting
     * this pair wrong is a vehicle that drives sideways, and it is the single
     * easiest thing in the file to get wrong, so it is written out here once
     * and everything — seats, emitters, wheels, the velocity — goes through
     * it.
     */
    public static double[] seatOffset(double yaw, double right, double forward) {
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return new double[] {
            right * -cos + forward * -sin,
            right * -sin + forward * cos,
        };
    }

    /**
     * {@link #seatOffset} with the body's attitude in it: where a point
     * {@code right}, {@code up} and {@code forward} of the vehicle lands once
     * the body is pitched and rolled about {@code pivotHeight}.
     *
     * <p>Pitch is nose-up positive and roll is right-side-down positive, both
     * in degrees, and the rotation is about the same point the model is drawn
     * around — so a seat stays on its bodywork when the bodywork tilts.
     *
     * @return x, y, z offsets from the vehicle's position
     */
    public static double[] bodyOffset(double yaw, double pitch, double roll, double pivotHeight,
                                      double right, double up, double forward) {
        // In the body frame first: x right, y up, z forward, about the pivot.
        double x = right;
        double y = up - pivotHeight;
        double z = forward;
        // Roll about the forward axis. Right-side-down positive lowers +x.
        double r = Math.toRadians(roll);
        double x1 = x * Math.cos(r) + y * Math.sin(r);
        double y1 = -x * Math.sin(r) + y * Math.cos(r);
        // Pitch about the right axis. Nose-up positive raises +z.
        double p = Math.toRadians(pitch);
        double z2 = z * Math.cos(p) - y1 * Math.sin(p);
        double y2 = z * Math.sin(p) + y1 * Math.cos(p);
        double[] flat = seatOffset(yaw, x1, z2);
        return new double[] {flat[0], y2 + pivotHeight, flat[1]};
    }

    /** The unit forward vector, x then z, for a heading. */
    static double[] forward(double yaw) {
        double radians = Math.toRadians(yaw);
        return new double[] {-Math.sin(radians), Math.cos(radians)};
    }

    /** The unit right-hand vector, x then z, for a heading. */
    static double[] right(double yaw) {
        double radians = Math.toRadians(yaw);
        return new double[] {-Math.cos(radians), -Math.sin(radians)};
    }

    /** A body-frame velocity as a world x/z vector. */
    static double[] worldVelocity(double yaw, double speed, double slip) {
        double[] f = forward(yaw);
        double[] r = right(yaw);
        return new double[] {
            f[0] * speed + r[0] * slip,
            f[1] * speed + r[1] * slip,
        };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1];
    }

    private static double fall(double vertical, double dt) {
        return Math.max(-TERMINAL_FALL, vertical - GRAVITY * dt);
    }

    /**
     * One tick of a damped spring toward {@code target}.
     *
     * <p>Semi-implicit: the rate is updated first and the position moves on
     * the new rate, which is what keeps it stable at a twentieth of a second
     * with the stiffnesses above.
     *
     * @return position, then rate
     */
    static double[] spring(double x, double rate, double target, double stiffness, double damping, double dt) {
        double acceleration = stiffness * (target - x) - damping * rate;
        rate += acceleration * dt;
        x += rate * dt;
        if (!Double.isFinite(x) || !Double.isFinite(rate)) {
            return new double[] {target, 0};
        }
        return new double[] {x, rate};
    }

    static double approach(double from, double to, double step) {
        if (from < to) {
            return Math.min(to, from + Math.abs(step));
        }
        return Math.max(to, from - Math.abs(step));
    }

    static double clampMagnitude(double value, double limit) {
        limit = Math.abs(limit);
        return Math.max(-limit, Math.min(limit, value));
    }

    static double turnToward(double from, double to, double step) {
        double delta = wrap180(to - from);
        if (Math.abs(delta) <= step) {
            return wrap360(to);
        }
        return wrap360(from + Math.signum(delta) * step);
    }

    static double wrap180(double degrees) {
        double wrapped = wrap360(degrees);
        return wrapped > 180 ? wrapped - 360 : wrapped;
    }

    static double wrap360(double degrees) {
        double wrapped = degrees % 360;
        return wrapped < 0 ? wrapped + 360 : wrapped;
    }

    /**
     * Where a vehicle is going and how its body is sitting, between ticks.
     *
     * <p>Immutable, so a step cannot half-apply.
     */
    public static final class State {

        private final double yaw;
        private final double speed;
        private final double slip;
        private final double verticalSpeed;
        private final double steer;
        private final double yawRate;
        private final double pitch;
        private final double pitchRate;
        private final double roll;
        private final double rollRate;
        private final double lift;
        private final double liftRate;

        /** A vehicle with this heading and velocity, sitting level with its wheels straight. */
        public State(double yaw, double speed, double verticalSpeed) {
            this(wrap360(yaw), speed, 0, verticalSpeed, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        State(double yaw, double speed, double slip, double verticalSpeed, double steer, double yawRate,
              double pitch, double pitchRate, double roll, double rollRate, double lift, double liftRate) {
            this.yaw = yaw;
            this.speed = speed;
            this.slip = slip;
            this.verticalSpeed = verticalSpeed;
            this.steer = steer;
            this.yawRate = yawRate;
            this.pitch = pitch;
            this.pitchRate = pitchRate;
            this.roll = roll;
            this.rollRate = rollRate;
            this.lift = lift;
            this.liftRate = liftRate;
        }

        public static State still(double yaw) {
            return new State(yaw, 0, 0);
        }

        /** Heading, degrees, Minecraft's way round. */
        public double yaw() {
            return yaw;
        }

        /** Speed along the heading, blocks per second; negative in reverse. */
        public double speed() {
            return speed;
        }

        /** Speed across the heading, blocks per second; positive to the vehicle's right. */
        public double slip() {
            return slip;
        }

        /** Speed over the ground in any direction. What a speedometer shows. */
        public double groundSpeed() {
            return Math.hypot(speed, slip);
        }

        public double verticalSpeed() {
            return verticalSpeed;
        }

        /** The front wheels' angle, degrees, positive to the right. */
        public double steer() {
            return steer;
        }

        /** How fast the heading is changing, degrees per second, clockwise positive. */
        public double yawRate() {
            return yawRate;
        }

        /** The body's pitch, degrees, nose-up positive. Where it is DRAWN; the position never tilts. */
        public double pitch() {
            return pitch;
        }

        double pitchRate() {
            return pitchRate;
        }

        /** The body's roll, degrees, right-side-down positive. */
        public double roll() {
            return roll;
        }

        double rollRate() {
            return rollRate;
        }

        /** How far above (or, mostly, below) the position the body is drawn, blocks. */
        public double lift() {
            return lift;
        }

        double liftRate() {
            return liftRate;
        }

        /** Whether the tyres are sliding: what a tyre-squeal or a skid mark would key off. */
        public boolean sliding() {
            return Math.abs(slip) > SLIDE_THRESHOLD;
        }

        /**
         * Dead in its tracks — a wall. The spin goes too: a car that has
         * stopped against a building is not still swinging round.
         */
        public State stopped() {
            return new State(yaw, 0, 0, verticalSpeed, steer, 0,
                    pitch, pitchRate, roll, rollRate, lift, liftRate);
        }

        /**
         * On the ground after a fall. The vertical speed is spent; some of it
         * goes into the springs, which is the bounce of a landing.
         */
        public State landed() {
            double compression = Math.min(0, verticalSpeed) * LANDING_COMPRESSION;
            return new State(yaw, speed, slip, 0, steer, yawRate,
                    pitch, pitchRate, roll, rollRate, lift, liftRate + compression);
        }

        /**
         * Up a step of {@code height} blocks. The position has already jumped;
         * the body has not, so it is left where it was to spring up after,
         * and the bump costs some speed.
         */
        public State stepped(double height) {
            if (!(height > 0)) {
                return this;
            }
            double keep = Math.max(0, 1 - STEP_SCRUB * height);
            return new State(yaw, speed * keep, slip * keep, verticalSpeed, steer, yawRate,
                    pitch, pitchRate + 30 * height, roll, rollRate, lift - height, liftRate);
        }

        /**
         * With {@code blocksPerSecond} added along the heading — a push, a
         * kick, a boost — for a plugin building a vehicle that gets its
         * speed some way other than a throttle. {@link Vehicle#nudge}.
         */
        public State nudged(double blocksPerSecond) {
            if (!Double.isFinite(blocksPerSecond) || blocksPerSecond == 0) {
                return this;
            }
            return new State(yaw, speed + blocksPerSecond, slip, verticalSpeed, steer, yawRate,
                    pitch, pitchRate, roll, rollRate, lift, liftRate);
        }

        /**
         * Thrown off something: {@code up} straight up and {@code sideways}
         * across the heading, both blocks a second, replacing whatever
         * vertical and sideways motion there was.
         *
         * <p>Written for kicking off a wall, which is a push in a direction
         * nothing else here can push: {@link #nudged} is along the heading and
         * a wall ride's heading is along the wall, so a nudge would send a
         * rider further along it rather than out into the air.
         */
        public State kicked(double up, double sideways) {
            return new State(yaw, speed, sideways, up, steer, yawRate,
                    pitch, pitchRate, roll, rollRate, lift, liftRate);
        }

        /**
         * With {@code degreesPerSecond} added to the spin — a flick of the
         * board in mid-air. On the ground the tyres take it back within a
         * tick or two, which is what makes it a trick rather than a steer.
         * {@link Vehicle#spin}.
         */
        public State spun(double degreesPerSecond) {
            if (!Double.isFinite(degreesPerSecond) || degreesPerSecond == 0) {
                return this;
            }
            return new State(yaw, speed, slip, verticalSpeed, steer, yawRate + degreesPerSecond,
                    pitch, pitchRate, roll, rollRate, lift, liftRate);
        }

        /**
         * Deflected along a wall. Whichever world axis is blocked loses its
         * velocity entirely; the other keeps {@link #WALL_SLIDE_KEEP} of
         * its. Neither blocked is not a deflection and returns this.
         */
        public State deflected(boolean blockedX, boolean blockedZ) {
            if (!blockedX && !blockedZ) {
                return this;
            }
            double[] v = worldVelocity(yaw, speed, slip);
            double vx = blockedX ? 0 : v[0] * WALL_SLIDE_KEEP;
            double vz = blockedZ ? 0 : v[1] * WALL_SLIDE_KEEP;
            double[] world = {vx, vz};
            return new State(yaw, dot(world, forward(yaw)), dot(world, right(yaw)), verticalSpeed,
                    steer, yawRate * 0.5, pitch, pitchRate, roll, rollRate, lift, liftRate);
        }
    }

    /** What the driver is asking for. */
    public static final class Demand {

        private final double yaw;
        private final double pitch;
        private final double throttle;
        private final double lift;
        private final boolean braking;
        private final double steer;
        private final boolean steersByKeys;
        private final boolean sprint;
        private final boolean sneak;

        /** A demand that steers by look: the body turns toward {@code yaw}. */
        public Demand(double yaw, double pitch, double throttle, double lift, boolean braking) {
            this(yaw, pitch, throttle, lift, braking, 0, false, false, false);
        }

        /** A demand that steers by keys: {@code steer} is -1 for left, 1 for right. */
        public static Demand steering(double yaw, double pitch, double steer,
                                      double throttle, double lift, boolean braking) {
            return new Demand(yaw, pitch, throttle, lift, braking, steer, true, false, false);
        }

        /**
         * The same, carrying the two keys the physics ignores and a plugin may
         * not: sprint and sneak.
         */
        public static Demand steering(double yaw, double pitch, double steer, double throttle,
                                      double lift, boolean braking, boolean sprint, boolean sneak) {
            return new Demand(yaw, pitch, throttle, lift, braking, steer, true, sprint, sneak);
        }

        private Demand(double yaw, double pitch, double throttle, double lift, boolean braking,
                       double steer, boolean steersByKeys, boolean sprint, boolean sneak) {
            this.sneak = sneak;
            this.yaw = yaw;
            this.pitch = pitch;
            // Clamped here rather than trusted, because both arms of the
            // control layer build these and one of them is reading a packet.
            this.throttle = clamp(throttle);
            this.lift = clamp(lift);
            this.braking = braking;
            this.steer = clamp(steer);
            this.steersByKeys = steersByKeys;
            this.sprint = sprint;
        }

        /** The sprint key, where keys can be read. Nothing here acts on it; see {@link VehicleInput#sprint}. */
        public boolean sprint() {
            return sprint;
        }

        /** The sneak key, the same way. See {@link VehicleInput#sneak}. */
        public boolean sneak() {
            return sneak;
        }

        public double steer() {
            return steer;
        }

        public boolean steersByKeys() {
            return steersByKeys;
        }

        /** Nobody at the wheel: no throttle, and a look straight ahead. */
        public static Demand idle(double yaw) {
            return new Demand(yaw, 0, 0, 0, false);
        }

        private static double clamp(double value) {
            if (!Double.isFinite(value)) {
                return 0;
            }
            return Math.max(-1, Math.min(1, value));
        }

        public double yaw() {
            return yaw;
        }

        public double pitch() {
            return pitch;
        }

        public double throttle() {
            return throttle;
        }

        public double lift() {
            return lift;
        }

        public boolean braking() {
            return braking;
        }
    }

    /**
     * What the world is doing to the vehicle this tick.
     *
     * <p>The three yes/no answers every vehicle needs, and — where the runtime
     * has sampled them — the height of the ground under each wheel relative
     * to the vehicle's position, front-left, front-right, rear-left,
     * rear-right, {@link Double#NaN} for a wheel over nothing. A vehicle
     * whose wheels were not sampled sits level, which is what every vehicle
     * did before there were wheels to sample.
     */
    public static final class Surroundings {

        private final boolean supported;
        private final boolean inWater;
        private final double submersion;
        private final double[] wheels;
        private final Wall wall;

        public Surroundings(boolean supported, boolean inWater, double submersion) {
            this(supported, inWater, submersion, null);
        }

        public Surroundings(boolean supported, boolean inWater, double submersion, double[] wheels) {
            this(supported, inWater, submersion, wheels, null);
        }

        public Surroundings(boolean supported, boolean inWater, double submersion, double[] wheels,
                            Wall wall) {
            this.supported = supported;
            this.inWater = inWater;
            this.submersion = submersion;
            this.wheels = wheels == null || wheels.length != 4 ? null : wheels.clone();
            this.wall = wall;
        }

        /**
         * The wall this vehicle is riding, or null - which is almost always.
         *
         * <p>Decided by whoever built these surroundings rather than in here,
         * because it is a question about the world: which side the wall is on,
         * and which way along it the vehicle is pointing. The physics only has
         * to know that it IS on one. See {@link Wall}.
         */
        public Wall wall() {
            return wall;
        }

        public static Surroundings falling() {
            return new Surroundings(false, false, 0);
        }

        public boolean supported() {
            return supported;
        }

        public boolean inWater() {
            return inWater;
        }

        public double submersion() {
            return submersion;
        }

        /** Whether the wheels were sampled at all. */
        public boolean sampled() {
            return wheels != null;
        }

        private double wheel(int index) {
            double height = wheels[index];
            return Double.isNaN(height) ? -WHEEL_DROOP : height;
        }

        /** The ground's pitch under the wheels, degrees nose-up positive; zero unsampled. */
        public double groundPitch(double wheelbase) {
            if (wheels == null) {
                return 0;
            }
            double front = (wheel(0) + wheel(1)) / 2;
            double rear = (wheel(2) + wheel(3)) / 2;
            return Math.toDegrees(Math.atan2(front - rear, wheelbase));
        }

        /** The ground's roll under the wheels, degrees right-side-down positive; zero unsampled. */
        public double groundRoll(double track) {
            if (wheels == null) {
                return 0;
            }
            double left = (wheel(0) + wheel(2)) / 2;
            double right = (wheel(1) + wheel(3)) / 2;
            return Math.toDegrees(Math.atan2(left - right, track));
        }

        /** Where the body sits relative to the position: the mean wheel height. Zero unsampled. */
        public double groundLift() {
            if (wheels == null) {
                return 0;
            }
            return (wheel(0) + wheel(1) + wheel(2) + wheel(3)) / 4;
        }
    }

    /** What to do about a destination that is blocked. */
    public enum Collision {
        /** Nothing in the way, or already inside something and refusing would only trap it. */
        MOVE,
        /** Something in the way no taller than a step: climb it. */
        STEP_UP,
        /** A wall. */
        STOP
    }

    /**
     * The collision rule, in one place so it can be tested without a world.
     *
     * <p>A vehicle already inside something is allowed to keep moving, which
     * is what lets one that ended up in a wall drive back out of it.
     */
    public static Collision resolve(boolean destinationBlocked, boolean canStepUp, boolean alreadyBlocked) {
        if (!destinationBlocked) {
            return Collision.MOVE;
        }
        if (canStepUp) {
            return Collision.STEP_UP;
        }
        return alreadyBlocked ? Collision.MOVE : Collision.STOP;
    }

    /** The result of a tick: the next state, the displacement to try, and what the vehicle is doing. */
    public static final class Step {

        private final State state;
        private final double dx;
        private final double dy;
        private final double dz;
        private final Set<VehicleState> states;

        Step(State state, double dx, double dy, double dz, Set<VehicleState> states) {
            this.state = state;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.states = states == null
                    ? Collections.<VehicleState>emptySet()
                    : Collections.unmodifiableSet(states);
        }

        public State state() {
            return state;
        }

        public Set<VehicleState> states() {
            return states;
        }

        public double dx() {
            return dx;
        }

        public double dy() {
            return dy;
        }

        public double dz() {
            return dz;
        }

        public boolean moves() {
            return dx != 0 || dy != 0 || dz != 0;
        }
    }
}
