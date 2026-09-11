package ai.resourcepack.engine.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack said a vehicle is.
 *
 * <p>A vehicle is <strong>a model people ride</strong>: an invisible chassis
 * that exists in the world whether or not anybody is in it, wearing a model,
 * with an ordered list of seats and a handful of numbers saying how it moves.
 *
 * <p>Three decisions are worth knowing before changing anything here.
 *
 * <p><strong>The chassis is a real entity, not the driver.</strong> Making the
 * driver the vehicle — walking them around with a model attached — buys free
 * WASD, free gravity and zero input latency, and was seriously considered. It
 * loses on one thing that cannot be worked around: a player cannot be parked.
 * A vehicle has to exist with nobody in it, or there is no garage, no dock, no
 * shop that places one and nothing to survive a restart.
 *
 * <p><strong>The driver's look is the steering.</strong> A and D are the
 * driver's strafe keys and the server cannot see them below the floor of
 * {@link Feature#PLAYER_INPUT}. What it can always see is where they are
 * looking, which their own client renders the instant they move the mouse — so
 * a vehicle that follows the camera feels responsive even though the
 * translation is a server tick behind. {@link #turnSpeed()} is the clamp on
 * how fast the body catches up.
 *
 * <p><strong>Seat order is the contract.</strong> See {@link #seats()}.
 */
public final class VehicleInfo {

    private final ContentId id;
    private final String model;
    private final String carrier;
    private final String name;
    private final VehicleMedium medium;
    private final double weight;
    private final double speed;
    private final double acceleration;
    private final double turnSpeed;
    private final VehicleHitbox hitbox;
    private final VehicleFlight flight;
    private final List<VehicleSeat> seats;
    private final Map<VehicleState, String> animations;
    private final List<VehicleEmitter> emitters;
    private final double scale;
    private final boolean jumps;
    private final boolean turnInPlace;
    private final Map<VehicleState, String> sounds;
    private final boolean capes;
    private final boolean animationFollowsSpeed;
    private final boolean wallRide;
    private final boolean worn;
    private final boolean speedometer;
    private final String permission;
    private final double coast;
    private final VehicleBail bail;
    /** Configuration blocks owned by addons, keyed by addon id. */
    private final Map<String, DefinitionNode> addons;

    private VehicleInfo(ContentId id, String model, String carrier, String name, VehicleMedium medium,
                        double weight, double speed, double acceleration, double turnSpeed,
                        VehicleHitbox hitbox, VehicleFlight flight, List<VehicleSeat> seats,
                        Map<VehicleState, String> animations, List<VehicleEmitter> emitters,
                        double scale, boolean jumps, boolean turnInPlace,
                        Map<VehicleState, String> sounds, boolean capes,
                        boolean animationFollowsSpeed, boolean wallRide, boolean worn, boolean speedometer,
                        double coast, VehicleBail bail, String permission) {
        this.permission = permission == null || permission.isBlank() ? null : permission;
        this.coast = Double.isFinite(coast) && coast >= 0 ? coast : 0;
        this.bail = bail;
        this.animationFollowsSpeed = animationFollowsSpeed;
        this.wallRide = wallRide;
        this.worn = worn;
        this.speedometer = speedometer;
        this.id = id;
        this.model = model;
        this.carrier = carrier;
        this.name = name;
        this.medium = medium;
        this.weight = weight;
        this.speed = speed;
        this.acceleration = acceleration;
        this.turnSpeed = turnSpeed;
        this.hitbox = hitbox;
        this.flight = flight;
        this.seats = seats;
        this.animations = animations;
        this.emitters = emitters;
        this.scale = scale;
        this.jumps = jumps;
        this.turnInPlace = turnInPlace;
        this.sounds = sounds;
        this.capes = capes;
        this.addons = Collections.emptyMap();
    }

    /** Copy constructor used only when attaching addon-owned configuration. */
    private VehicleInfo(VehicleInfo source, Map<String, DefinitionNode> addons) {
        this.id = source.id;
        this.model = source.model;
        this.carrier = source.carrier;
        this.name = source.name;
        this.medium = source.medium;
        this.weight = source.weight;
        this.speed = source.speed;
        this.acceleration = source.acceleration;
        this.turnSpeed = source.turnSpeed;
        this.hitbox = source.hitbox;
        this.flight = source.flight;
        this.seats = source.seats;
        this.animations = source.animations;
        this.emitters = source.emitters;
        this.scale = source.scale;
        this.jumps = source.jumps;
        this.turnInPlace = source.turnInPlace;
        this.sounds = source.sounds;
        this.capes = source.capes;
        this.animationFollowsSpeed = source.animationFollowsSpeed;
        this.wallRide = source.wallRide;
        this.speedometer = source.speedometer;
        this.permission = source.permission;
        this.coast = source.coast;
        this.bail = source.bail;
        this.addons = copyAddons(addons);
    }

    private static Map<String, DefinitionNode> copyAddons(Map<String, DefinitionNode> addons) {
        if (addons == null || addons.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, DefinitionNode> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, DefinitionNode> entry : addons.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(java.util.Locale.ROOT);
            if (!key.isEmpty() && entry.getValue() != null) {
                copy.put(key, entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Configuration a content pack supplied for an addon.
     *
     * <p>The engine transports this block but does not interpret it. That keeps
     * an addon usable with authored, embedded and Studio content without making
     * the engine own the addon's game rules. Keys are lowercase addon ids.
     */
    public Optional<DefinitionNode> addon(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(addons.get(id.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    /** Every addon block this vehicle carries, in source order. */
    public Map<String, DefinitionNode> addons() {
        return addons;
    }

    /** The same vehicle with addon-owned configuration attached. */
    public VehicleInfo withAddons(Map<String, DefinitionNode> addons) {
        Map<String, DefinitionNode> copy = copyAddons(addons);
        return copy.equals(this.addons) ? this : new VehicleInfo(this, copy);
    }

    /**
     * Engine internal; built by the vehicle loader.
     *
     * <p>The arity without a {@link VehicleFlight} flies the way every air
     * vehicle did before that type existed — see {@link VehicleFlight#forSpeed}.
     * Kept rather than replaced so a caller that predates it still compiles and
     * still behaves.
     */
    public static VehicleInfo of(ContentId id, String model, String name, VehicleMedium medium,
                                 double weight, double speed, double acceleration, double turnSpeed,
                                 VehicleHitbox hitbox, List<VehicleSeat> seats,
                                 Map<VehicleState, String> animations, List<VehicleEmitter> emitters) {
        return of(id, model, name, medium, weight, speed, acceleration, turnSpeed,
                hitbox, VehicleFlight.forSpeed(speed), seats, animations, emitters);
    }

    /** The same, saying how it flies. */
    public static VehicleInfo of(ContentId id, String model, String name, VehicleMedium medium,
                                 double weight, double speed, double acceleration, double turnSpeed,
                                 VehicleHitbox hitbox, VehicleFlight flight, List<VehicleSeat> seats,
                                 Map<VehicleState, String> animations, List<VehicleEmitter> emitters) {
        return new VehicleInfo(
                Objects.requireNonNull(id, "id"),
                model == null ? "" : model,
                "",
                name == null ? "" : name,
                medium == null ? VehicleMedium.LAND : medium,
                weight, speed, acceleration, turnSpeed,
                hitbox == null ? VehicleHitbox.DEFAULT : hitbox,
                flight == null ? VehicleFlight.forSpeed(speed) : flight,
                seats == null ? List.of() : List.copyOf(seats),
                copyAnimations(animations), copyEmitters(emitters), 1, false, false,
                Collections.<VehicleState, String>emptyMap(), true,
                false, false, false, true, 0, null, null);
    }

    /**
     * The same, for a vehicle that arrived on a Studio push.
     *
     * <p>Its art is named differently and that is the whole difference — see
     * {@link #carrier()}. A second constructor rather than a nullable
     * argument, and the same shape {@code SoundInfo.pushed} already uses for
     * exactly the same reason: the two ways of naming a thing are separate
     * facts, and a caller has to say which one it is holding.
     */
    public static VehicleInfo pushed(ContentId id, String carrier, String name, VehicleMedium medium,
                                     double weight, double speed, double acceleration, double turnSpeed,
                                     VehicleHitbox hitbox, List<VehicleSeat> seats,
                                     Map<VehicleState, String> animations, List<VehicleEmitter> emitters) {
        return pushed(id, carrier, name, medium, weight, speed, acceleration, turnSpeed,
                hitbox, VehicleFlight.forSpeed(speed), seats, animations, emitters);
    }

    /** The same, saying how it flies. */
    public static VehicleInfo pushed(ContentId id, String carrier, String name, VehicleMedium medium,
                                     double weight, double speed, double acceleration, double turnSpeed,
                                     VehicleHitbox hitbox, VehicleFlight flight, List<VehicleSeat> seats,
                                     Map<VehicleState, String> animations, List<VehicleEmitter> emitters) {
        return new VehicleInfo(
                Objects.requireNonNull(id, "id"),
                "",
                carrier == null ? "" : carrier,
                name == null ? "" : name,
                medium == null ? VehicleMedium.LAND : medium,
                weight, speed, acceleration, turnSpeed,
                hitbox == null ? VehicleHitbox.DEFAULT : hitbox,
                flight == null ? VehicleFlight.forSpeed(speed) : flight,
                seats == null ? List.of() : List.copyOf(seats),
                copyAnimations(animations), copyEmitters(emitters), 1, false, false,
                Collections.<VehicleState, String>emptyMap(), true,
                false, false, false, true, 0, null, null);
    }

    /**
     * An {@link EnumMap}, so iterating it is in {@link VehicleState}'s
     * declaration order — which is the animation precedence. Nothing depends
     * on that (see {@link VehicleState#choose}, which walks the enum rather
     * than the map), but a map whose order contradicts the rule beside it is
     * a thing somebody will eventually read as the rule.
     */
    private static Map<VehicleState, String> copyAnimations(Map<VehicleState, String> animations) {
        if (animations == null || animations.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<VehicleState, String> copy = new EnumMap<>(VehicleState.class);
        for (Map.Entry<VehicleState, String> entry : animations.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<VehicleEmitter> copyEmitters(List<VehicleEmitter> emitters) {
        return emitters == null ? List.of() : List.copyOf(emitters);
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /**
     * The item id whose model it wears, or empty for an invisible vehicle.
     *
     * <p>An item rather than a model file, for the same reason a placed model
     * and a custom entity both name one: the art already exists as an item,
     * and a second way to point at the same model is a second thing to keep in
     * step.
     */
    public Optional<ContentId> model() {
        return model.isEmpty() ? Optional.empty() : ContentId.parse(model);
    }

    /**
     * The {@code custom_model_data} string its art is drawn with, or empty
     * when it names an item instead.
     *
     * <p><strong>Exactly one of this and {@link #model()} is set</strong>, and
     * which one says how the art was delivered rather than where it came from.
     * A pack built here ships the model beside an item, so the item id is
     * enough. A pack built by Studio ships a zip with no plugin behind it, so
     * its models borrow a vanilla item — paper wearing a string — and the
     * string is the only handle there is.
     *
     * <p>This is the same split {@code SoundInfo.event()} makes, for the same
     * reason: two ways of naming one thing, and pretending they are one field
     * means one of them is wrong.
     */
    public Optional<String> carrier() {
        return carrier.isEmpty() ? Optional.empty() : Optional.of(carrier);
    }

    /** What to call it in a message, or empty for its id. */
    public Optional<String> name() {
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /** What it moves through, which decides its vertical rule entirely. */
    public VehicleMedium medium() {
        return medium;
    }

    /**
     * How heavy it is, 1 to 100, with 10 as a car.
     *
     * <p>Abstract rather than kilograms: nothing in Minecraft weighs anything,
     * so a real unit would imply a physics this cannot honour. It scales how
     * long the vehicle takes to reach its speed and how long it takes to stop,
     * which is the whole of what mass does that a player can feel.
     */
    public double weight() {
        return weight;
    }

    /** Top speed, in blocks per second. A sprinting player is about 5.6. */
    public double speed() {
        return speed;
    }

    /** How fast it reaches that speed, in blocks per second squared. */
    public double acceleration() {
        return acceleration;
    }

    /**
     * How fast the body swings round to follow the driver's look, in degrees
     * per second.
     *
     * <p>Not A and D. See the class note.
     */
    public double turnSpeed() {
        return turnSpeed;
    }

    /**
     * Whether it can turn while it is standing still.
     *
     * <p><strong>False for everything that does not ask for it</strong>, and
     * that is the interesting half. A vehicle used to swing round on the spot
     * whenever its driver moved the mouse, because the yaw chases the camera
     * every tick and nothing ever asked how fast the vehicle was going — so a
     * parked car span like a turntable, and a driver reversing out of a space
     * pointed the bodywork wherever they happened to be looking. Nothing with
     * wheels does that; steering is a thing you do to a vehicle that is
     * moving.
     *
     * <p>What does turn on the spot is a real class of vehicle rather than an
     * exception — a tank, a hovercraft, an excavator, anything tracked — so
     * this is a switch and not a rule. An aircraft off the ground is exempt
     * whatever it says, because a helicopter hovering is not standing still,
     * and holding a hover to the same test would leave it unable to point
     * itself anywhere.
     */
    public boolean turnInPlace() {
        return turnInPlace;
    }

    /**
     * How big it is to click on and to stand in front of.
     *
     * <p>Never null; a pack that says nothing gets {@link VehicleHitbox#DEFAULT}.
     *
     * <p><strong>{@link #scale()} does not touch this.</strong> The art is
     * grown by the display transform; the box a player collides with is a
     * number the pack states in blocks, and multiplying it would change what a
     * vehicle bumps into for somebody who only asked for bigger bodywork. A
     * pack that scales a car up and wants the collision to follow says so.
     */
    public VehicleHitbox hitbox() {
        return hitbox;
    }

    /**
     * How much bigger than built the vehicle is drawn, and everything measured
     * against the art with it.
     *
     * <p><strong>It exists because a block model cannot be more than three
     * blocks on an axis.</strong> The format bounds an element to -16..32, so
     * 48 units is the whole ceiling, and no amount of editing geometry makes a
     * bus or a cargo ship. Growing the DISPLAY is the only way past it, and it
     * is the same mechanism a placed rig has had all along — see
     * {@code RigPlacementListener}'s scale marker.
     *
     * <p>What it multiplies is the model and the things whose positions are
     * quoted against the model: every seat's offset from the chassis, and every
     * emitter's. What it deliberately does NOT multiply is the hitbox, above,
     * or any of the numbers about how the vehicle MOVES — a scaled lorry is a
     * bigger lorry, not a faster or heavier one, and tying speed to size would
     * make one slider quietly two.
     *
     * <p>1 for every vehicle written before this and for every pack that says
     * nothing, which is what {@code of} and {@code pushed} hand back.
     */
    public double scale() {
        return scale;
    }

    /**
     * The same vehicle, drawn {@code scale} times as big.
     *
     * <p>A wither rather than four more factory arities. The two {@code of}
     * and two {@code pushed} overloads already carry thirteen positional
     * arguments between them because {@link VehicleFlight} was added that way,
     * and a fourteenth would mean four more signatures differing by one
     * {@code double} — which is the point at which a caller gets it wrong
     * silently. Every existing caller keeps compiling and keeps getting 1.
     *
     * <p>A scale that is not a positive finite number is ignored rather than
     * refused: this is loaded from a file somebody typed, and a vehicle drawn
     * at its authored size is a better answer to {@code scale: abc} than no
     * vehicle at all.
     */
    public VehicleInfo withScale(double scale) {
        if (!Double.isFinite(scale) || scale <= 0 || scale == this.scale) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /**
     * The same vehicle, able (or not) to turn on the spot.
     *
     * <p>A wither for the reason {@link #withScale} is one: the factories
     * already carry thirteen positional arguments and a fourteenth of a
     * different type is how a caller gets one of them wrong in silence. See
     * {@link #turnInPlace()} for what it means.
     */
    public VehicleInfo withTurnInPlace(boolean turnInPlace) {
        if (turnInPlace == this.turnInPlace) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /**
     * The same vehicle, playing these sounds.
     *
     * <p>A wither on the same argument as {@link #withScale}, and copied the
     * same way {@code animations} is — an {@link EnumMap} keyed in
     * declaration order, with blank entries dropped so that "no sound for this
     * state" has one spelling.
     */
    public VehicleInfo withSounds(Map<VehicleState, String> sounds) {
        Map<VehicleState, String> copy = copyAnimations(sounds);
        if (copy.equals(this.sounds)) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                copy, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /**
     * Whether this vehicle's animation clock runs on how far it has TRAVELLED
     * rather than on how long it has been playing.
     *
     * <p>A drive cycle is a wheel turning, and a wheel turns because the
     * vehicle is moving: at half speed it should turn at half the rate, and
     * standing still it should not turn at all. Playing the cycle at its
     * authored rate whatever the vehicle is doing gives the wheels of a
     * pushed skateboard, which spin at exactly one speed from a crawl to a
     * tuck, and the milk float whose wheels race while it creeps.
     *
     * <p>Off by default, because an animation on {@code moving} is not always
     * a wheel — a bobbing suspension or a flapping flag is authored at a rate
     * somebody chose — and changing what an existing pack looks like is not
     * something a version bump should do quietly.
     */
    public boolean animationFollowsSpeed() {
        return animationFollowsSpeed;
    }

    /**
     * Whether this vehicle can ride a wall: hit one at speed and a shallow
     * angle and it holds itself against it, rolled over onto its side, until
     * the speed or the wall runs out.
     *
     * <p>Off by default, and not because it is expensive - a wall is only
     * looked for on a vehicle that says this - but because it is a decision
     * about what a vehicle IS. A skateboard wall rides. A tractor does not,
     * and a tractor that did would be a bug in somebody's farm.
     */
    public boolean wallRide() {
        return wallRide;
    }

    /**
     * Whether this vehicle's driver is shown their speed above the hotbar.
     *
     * <p>On by default, and the server's {@code vehicles.speedometer} can turn
     * it off for everything at once — this is the narrower question of whether
     * a readout makes sense for THIS vehicle. A car has a speedometer on its
     * dashboard and a skateboard does not, and a number counting up in the
     * corner of the screen is the sort of thing that quietly turns a trick into
     * a stat. So it is a decision about what the vehicle is, exactly like
     * {@link #wallRide()}.
     *
     * <p>Nothing moves when this is off. The readout is not relocated to the
     * chat, a boss bar or a title: it is simply not written, and the action bar
     * is left to whatever else wants it.
     */
    public boolean speedometer() {
        return speedometer;
    }

    /**
     * How quickly this vehicle slows when nobody is driving it, blocks a
     * second squared, or 0 for the engine's own answer.
     *
     * <p><strong>A skateboard coasts and a tractor does not.</strong> The
     * engine's floor is a car's: a vehicle with nobody on the throttle rolls
     * to a stop in a few seconds, which is right for everything with an engine
     * in it and wrong for everything without one. Before this, the only way to
     * build something that rolls was a plugin handing the speed back twenty
     * times a second - a lot of machinery for a number.
     *
     * <p>Small numbers roll far: 0.15 is a skateboard on smooth concrete, 0.6
     * a bicycle, 2 a shopping trolley with a bad wheel. It replaces the
     * engine's floor rather than the whole of the drag, so a vehicle whose own
     * acceleration implies more than this still gets that.
     */
    public double coast() {
        return coast;
    }

    /**
     * What somebody needs to get into this, or empty for anybody.
     *
     * <p>The same shape as an item's, and checked at the same moment: on the
     * way IN, not on the way to owning one. A vehicle is a thing standing in
     * the world that anybody can walk up to and right-click, so without this
     * the only control a server has over who rides what is who was given the
     * item - which is no control at all once one is parked in a public square.
     *
     * <p>Absent is not "op": a vehicle nobody said anything about is one
     * anybody may ride, which is what every pack written before this expects.
     */
    public java.util.Optional<String> permission() {
        return java.util.Optional.ofNullable(permission);
    }

    /** The same vehicle, needing that. See {@link #permission()}. */
    public VehicleInfo withPermission(String permission) {
        if (java.util.Objects.equals(permission, this.permission)) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail,
                permission);
    }

    /** The same vehicle, coasting like that. See {@link #coast()}. */
    public VehicleInfo withCoast(double coast) {
        if (coast == this.coast) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /**
     * What happens to a rider who lands this badly, or null for nothing.
     *
     * <p>Off unless a pack asks for it. A vehicle that throws its rider is a
     * game rule rather than a physical fact, and a server owner who never
     * asked for one should not find it under somebody at speed. See
     * {@link VehicleBail}.
     */
    public VehicleBail bail() {
        return bail;
    }

    /** The same vehicle, bailing (or not) on a bad landing. See {@link #bail()}. */
    public VehicleInfo withBail(VehicleBail bail) {
        if (bail == this.bail) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /** The same vehicle, showing (or not) its driver's speed. See {@link #speedometer()}. */
    public VehicleInfo withSpeedometer(boolean speedometer) {
        if (speedometer == this.speedometer) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /** The same vehicle, able (or not) to ride a wall. See {@link #wallRide()}. */
    public VehicleInfo withWallRide(boolean wallRide) {
        if (wallRide == this.wallRide) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /**
     * Whether this vehicle is WORN rather than ridden: its model is not drawn
     * while anybody is in it.
     *
     * <p>A pair of rollerskates, a jetpack, a horse costume - a vehicle whose
     * art belongs on the occupant's body rather than under it. The seat's
     * emotes carry the art instead, as props attached to the rig's bones (see
     * FORMAT.md, "Emotes"), so it moves with the legs the way the legs do and
     * the vehicle's own model would only ever be a second copy standing on the
     * floor. Empty, it is drawn as normal: a pair of skates left in the road is
     * a thing you can see and step into.
     *
     * <p>Off by default, for the same reason {@link #wallRide()} is: it is a
     * decision about what the vehicle is.
     */
    public boolean worn() {
        return worn;
    }

    /** The same vehicle, worn (or not). See {@link #worn()}. */
    public VehicleInfo withWorn(boolean worn) {
        if (worn == this.worn) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /** The same vehicle, with its animation clock tied (or not) to its speed. */
    public VehicleInfo withAnimationFollowsSpeed(boolean animationFollowsSpeed) {
        if (animationFollowsSpeed == this.animationFollowsSpeed) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /** The same vehicle, drawing (or not) its riders' capes. See {@link #capes()}. */
    public VehicleInfo withCapes(boolean capes) {
        if (capes == this.capes) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /**
     * The same vehicle with a different top speed.
     *
     * <p>What a plugin's {@link Vehicle#setSpeedLimit} is applied through:
     * the physics reads {@link #speed()} for the top speed and derives
     * braking, reversing and coasting from it, so a copy with a lower one is
     * a vehicle that is slower in every way at once rather than one whose
     * throttle has been turned down. Nothing else changes — same seats, same
     * hitbox, same flight numbers, which is why an aircraft limited below its
     * takeoff speed cannot take off.
     *
     * <p>A speed that is not a positive finite number is ignored, on the same
     * argument as {@link #withScale}.
     */
    public VehicleInfo withSpeed(double speed) {
        if (!Double.isFinite(speed) || speed <= 0 || speed == this.speed) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /**
     * Whether the jump key JUMPS it rather than braking it.
     *
     * <p>Only a {@link VehicleMedium#LAND} vehicle reads this. Space is the
     * handbrake on the ground, and a dirt bike or a skateboard would rather
     * have a jump than a second brake — the back key already brakes before
     * it reverses, so nothing is lost. Off for every vehicle written before
     * this existed and for every pack that says nothing.
     */
    public boolean jumps() {
        return jumps;
    }

    /**
     * The same vehicle, with the jump key jumping it (or not).
     *
     * <p>A wither, on the same argument as {@link #withScale}.
     */
    public VehicleInfo withJump(boolean jumps) {
        if (jumps == this.jumps) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /**
     * How it gets off the ground and how it comes back down.
     *
     * <p>Never null, and read only when {@link #medium()} is
     * {@link VehicleMedium#AIR} — a car carries one and never looks at it. A
     * pack that says nothing gets {@link VehicleFlight#forSpeed}, which is the
     * behaviour every air vehicle had before these numbers existed.
     */
    public VehicleFlight flight() {
        return flight;
    }

    /**
     * The same vehicle flying differently. What {@link Vehicle#setDescent}
     * hands the physics: the pack's own numbers with a descent imposed on
     * them, and nothing else about the vehicle touched.
     */
    public VehicleInfo withFlight(VehicleFlight flight) {
        if (flight == null || flight == this.flight) {
            return this;
        }
        return new VehicleInfo(id, model, carrier, name, medium, weight, speed, acceleration,
                turnSpeed, hitbox, flight, seats, animations, emitters, scale, jumps, turnInPlace,
                sounds, capes, animationFollowsSpeed, wallRide, worn, speedometer, coast, bail, permission);
    }

    /**
     * The seats, in the order people are put in them.
     *
     * <p><strong>The order is the contract.</strong> The driver is index 0 and
     * the rest are passenger 1, 2, 3… in exactly this order — so somebody who
     * clicks the third seat gets the third seat, and a pack that reorders its
     * list has moved where people sit rather than only how the file reads.
     * {@code VehicleDefinitions} is what guarantees the driver is first, so
     * nothing downstream has to search for it.
     *
     * <p>Never empty: a vehicle with no driver seat is refused at load, because
     * one that loaded would be a model claiming to be a vehicle and doing
     * nothing at all.
     */
    public List<VehicleSeat> seats() {
        return seats;
    }

    /**
     * Which animation this vehicle plays in which {@link VehicleState}.
     *
     * <p>Empty for a vehicle that animates nothing, which is every vehicle
     * written before this existed and most of them since — a vehicle is a
     * model that moves, and whether its wheels also turn is a separate
     * ambition.
     *
     * <p><strong>A state with no entry falls through to the next one</strong>
     * rather than stopping what is playing; {@link VehicleState#choose} is the
     * rule and the reason. The names are the model's own animation names, so
     * they are only meaningful against a model that has a rig — one that
     * places as a single still display can carry this map and never use it,
     * which is a half-finished vehicle rather than an error.
     */
    public Map<VehicleState, String> animations() {
        return animations;
    }

    /**
     * Which sound this vehicle plays in which {@link VehicleState}.
     *
     * <p>A custom sound's id, as text, resolved against {@code Sounds} when it
     * is played — so a vehicle may name a sound that arrives in a later push
     * without the vehicle itself being reloaded.
     *
     * <p><strong>Read exactly like {@link #animations()}</strong>: one state
     * wins, by the enum's declaration order, and a blank state falls through
     * to the next. That is not the emitter rule (which fires on ANY of its
     * states) and the difference is the same one: an engine has one note the
     * way a rig has one clock, and two of them playing over each other is a
     * vehicle that sounds broken rather than busy.
     *
     * <p>Empty for every vehicle written before this existed, which is
     * silence.
     */
    public Map<VehicleState, String> sounds() {
        return sounds;
    }

    /**
     * Whether a rider's cape is drawn while they are in it.
     *
     * <p>True unless the pack says otherwise, because a cape is somebody's own
     * and taking it off them is the surprising direction. What makes it worth
     * a switch at all is that a cape hangs off the back of a rig and a rig in
     * a vehicle is usually inside something — the cabin of a car, the fuselage
     * of an aeroplane, a tank — where it clips straight through the bodywork
     * and there is nothing an author can do about it from the model.
     *
     * <p>It is the CAPE and not the rig: the rider is still dressed, still
     * posed and still visible. See {@code VehicleSeat.hidden} for the switch
     * that removes them entirely.
     */
    public boolean capes() {
        return capes;
    }

    /**
     * Where it throws particles, and when.
     *
     * <p>Independent of each other and of {@link #animations()} — an exhaust
     * plume is not an animation and a pack that wants one should not need a
     * rig for it.
     */
    public List<VehicleEmitter> emitters() {
        return emitters;
    }

    /** The seat that steers. Always present, always {@code seats().get(0)}. */
    public VehicleSeat driverSeat() {
        return seats.get(0);
    }

    /** How many people fit. */
    public int capacity() {
        return seats.size();
    }

    @Override
    public String toString() {
        return id + " (" + medium.key() + ", " + seats.size() + " seats)";
    }
}
