# Building against RP Engine

For plugin developers. If you are a server owner writing content, you want
[`FORMAT.md`](FORMAT.md) instead.

## Getting in

Everything hangs off the plugin instance.

```java
RPEnginePlugin engine = (RPEnginePlugin) Bukkit.getPluginManager().getPlugin("RPEngine");

engine.items();     // custom items
engine.models();    // studio models, and the rigs standing in your worlds
engine.emotes();    // emotes and stances
engine.vehicles();  // vehicles, and the ones standing in your worlds
engine.sounds();    // custom sounds
engine.icons();     // icons, and putting one into a piece of text
engine.overlays();  // HUD overlays: show one, hide one, feed it values
engine.registry();  // everything this server holds, by id
engine.registration(); // and how to put content of your own into it
```

Add `softdepend: [RPEngine]` to your `plugin.yml` so you load after it.

**Everything in `ai.resourcepack.engine.api` is supported. Nothing in
`ai.resourcepack.engine.core` is**, and anything there may change in any
release, including a patch.

## IDs

One `namespace:path` ID for everything, and it is a Minecraft resource
location. `ContentId.parse` answers empty rather than throwing, so an ID out of
somebody's config is a message rather than a stack trace.

```java
ContentId ruby = ContentId.parse("mypack:ruby").orElseThrow();
```

**Store IDs, never the things they resolve to.** An icon's codepoint moves when
content changes, and an item's model is derived from its ID — the ID is the
only stable reference in the system, and everything else is derived from it at
the moment it is needed.

## Items

```java
Optional<ItemStack> stack = engine.items().create(ruby);
Optional<ContentId> what = engine.items().idOf(player.getInventory().getItemInMainHand());
boolean isRuby = engine.items().is(stack, ruby);
```

Identity lives in the stack's persistent data, not its name or model — an item
renamed in an anvil is still itself, and a vanilla diamond somebody called
"Ruby" is still not one.

`create` is main thread only, like everything that touches an `ItemStack`.
`ids()` and `info()` are safe from any thread.

## Models

```java
engine.models().place(location, "golem", PlaceOptions.defaults());
engine.models().itemFor("golem", ItemOptions.defaults());
engine.models().near(location, 16);      // what is standing around here
engine.models().at(entity);              // is this entity part of a rig?
```

A `Placement` is a handle on a rig that is standing in a world: it can be
asked what it is, told to play an animation, and removed. `playhead()` is
how far into its animation it is, for keeping something else on its clock.

## Emotes

```java
engine.emotes().play(player, "wave", List.of());   // solo
engine.emotes().play(lead, "hug", List.of(other)); // with a cast
engine.emotes().stop(player);
engine.emotes().isEmoting(player.getUniqueId());
```

**An emote with a cast moves the people it names.** If your plugin plays one,
it is your job to have asked them — the engine's own `/emote` command does
that through an invitation, and `play` does not.

`EmoteResult` carries a typed reason rather than a sentence, so you write the
words in your own palette and your own language.

### Putting a rig on somebody yourself

```java
engine.emotes().wear(player, "rowing");  // and again to swap, null to take off
engine.emotes().face(player, 90f);       // point the body; null follows their look
```

`wear` is the movement-set machinery with the movement taken out: the rig
follows its wearer, moving does not end it, and **you** decide what it wears
rather than their legs deciding. Calling it again swaps without restarting the
session. `Emotes.BUILT_IN_SITTING` and `BUILT_IN_STANDING` are two stances
every pack has without authoring one, so a seat can dress somebody on a pack
that ships no emotes at all — it still needs a baked rig for that player,
because a rig is their skin.

`seek(player, seconds)` moves a worn rig's clock. Called every tick with a
`Placement.playhead()`, it keeps the rig posed at the same moment as that
model's animation, which is how a vehicle keeps its paddler's arms on the
paddle: the two are written as one animation and would drift apart on two
clocks.

An emote can come from a content folder now, not only from a push
(`FORMAT.md`, "Emotes"), and the engine bakes the rigs it plays on itself, so a
plugin that ships emotes in its own content folder gets them played on every
player's own skin with no Studio in the picture. The rig for somebody who has
never joined before is the default figure until the next build.

`face` exists because a carried body and a carried camera are different
questions. A worn rig normally turns with its wearer's look, which is right for
somebody walking; it is wrong for a passenger, whose body belongs to whatever
they are riding while their head is their own. Pass the direction the seat
points, **every tick** — a vehicle turns — and `null` to hand the rig back to
their look. It only moves a rig that arrived through your `wear`, so somebody
who was already mid-emote when they sat down keeps it.

### A prop that turns because its wearer moved

An emote can carry models (`props` in FORMAT.md), and a prop that names one
of the model's animations plays it on the emote's clock. A wheel should not:
it turns because the wearer moved, not because time passed. So a plugin may
drive one prop's clock itself, by distance:

```java
clock += board.groundSpeed() / topSpeed * TURNS_AT_TOP / 20.0;   // seconds of a one-turn-a-second `roll`
engine.emotes().seekProp(rider, "skate_right", clock);
engine.emotes().seekProp(rider, "skate_left", clock);
```

`seekProp` moves that prop's animation to exactly that time every tick, loop
wrap included, and holds it off the emote's clock from then on. It survives a
swap to another emote carrying the same prop id, so the wheel does not jump
because the wearer changed pose. It is `animation-follows-speed` for a thing
that is worn rather than ridden, and the rollerskates are what it was written
for.

## Sounds

```java
engine.sounds().play(player, id);       // only this player hears it
engine.sounds().playAt(location, id);   // everybody nearby hears it here
engine.sounds().playFrom(entity, id);   // everybody nearby hears it follow this entity
```

All three use the sound's declared category, volume and pitch; overloads let
you replace volume and pitch. `playFrom` is the moving-source form for engines,
creatures and anything else where a fixed coordinate would leave the sound
behind. Playing methods are main thread only; `ids()` and `info()` are safe
from any thread.

## Vehicles

```java
engine.vehicles().spawn(location, id);       // park one, ready to get into
engine.vehicles().of(player);                // the vehicle they are in
engine.vehicles().at(entity);                // is this thing part of a vehicle?
engine.vehicles().near(location, 16);        // nearest first
engine.vehicles().loaded();                  // every one in a loaded chunk
engine.vehicles().isRiding(uuid);            // safe from any thread
```

A `Vehicle` is a handle on one standing in a world, occupied or not: what it
is, where it is going, who is aboard, and the switches a plugin turns.

```java
Vehicle car = engine.vehicles().of(player).orElseThrow();
car.speed();                    // blocks per second, negative in reverse
car.states();                   // MOVING, TURNING, AIRBORNE, IDLE ...
car.driver();                   // Optional<Player>
car.occupants();                // driver first, then in seat order
car.seat(other, 1);             // put somebody in seat 1
car.eject(other);               // and take them out
```

**Fuel, breakdowns, ignition keys, pit lanes — anything that stops a vehicle
going — is `setEnabled`.** A disabled vehicle ignores its driver, coasts to a
halt where it is and stays there; it still falls if it was in the air and
still floats if it was on water, and people get in and out of it as normal.
The flag is written on the chassis, so a car that ran dry is still dry after
a restart.

```java
NamespacedKey fuel = car.key(this, "fuel");

@EventHandler
public void onState(VehicleStateEvent event) {
    // Burn only while it is actually going somewhere.
    if (event.entered(VehicleState.MOVING)) burners.add(event.vehicle());
    if (event.left(VehicleState.MOVING)) burners.remove(event.vehicle());
}

// once a second
for (Vehicle vehicle : burners) {
    int left = vehicle.data().getOrDefault(fuel, PersistentDataType.INTEGER, 0) - 1;
    vehicle.data().set(fuel, PersistentDataType.INTEGER, Math.max(0, left));
    vehicle.setEnabled(left > 0);
}
```

`data()` is the chassis's persistent data — the tank, the owner, the price
paid — and it survives everything the vehicle survives. `uniqueId()` is the
chassis's id and is the one stable key: seats and the model are rebuilt on
every chunk load with new ids each time.

`setSpeedLimit` is the softer version, for a damaged engine or a road with a
limit: a lower top speed, with braking and reversing scaled to match, and an
aircraft limited below its takeoff speed cannot take off. Deliberately not
remembered — keep it in `data()` yourself if it should be. `stop()` is a
wall: dead this tick, throttle reset, still answering its driver afterwards.

`submersion()` is how far the water's surface stands above the vehicle's base,
in blocks, and zero out of water. It is a different question from
`VehicleState.SUBMERGED`, which is only "standing in a water block" and is
therefore also what a puddle on a road answers: the edge of a river is drawn an
eighth of a block deep. **A vehicle whose `medium` is not `water` cannot drive
in water** — its engine floods and it wallows to a stop, which is the handling
model's half of that. Whether the machine survives it is yours: the engine has
no opinion about how much punishment a vehicle takes, on exactly the argument
that keeps health out of `setDamage`, and this is the number to decide on.

### Collisions, impulses and detachable model parts

`VehicleImpactEvent` fires once when the authoritative solver resolves a
meaningful vehicle/vehicle, vehicle/world or landing impact. A vehicle pair is
one event with `first()` and `second()` outcomes; world impacts have only the
first. Each outcome carries the pre-impact velocity, actual velocity change,
contact normal, closing speed, normal impulse, spin change and contacted body
area. Resting overlap and contacts already moving apart do not fire.

`contactOffset()` says **where on the vehicle** it landed, in that vehicle's own
frame and in blocks (`x` right, `y` up, `z` forward) — the same frame and units
as `partOffset`, so the two compare directly and `VehicleCorner.of(x, z)` names
the corner. `area()` is which of four sides took it; this is where along that
side, which is the difference between "the front" and "the front left".

How exact it is depends on the cause, and a listener that treats the three
alike will invent detail the engine does not have:

| Cause | What the offset is |
|---|---|
| `VEHICLE` | The real contact point the impulse solver clipped out of the two boxes — the same point the spin was computed about |
| `WORLD` | A **face**, not a point: block collision is resolved per world axis, so nothing knows where along the wing it touched. The height is the middle of the panel, because a wall is as tall as the thing that hit it |
| `LANDING` | The corner that reached the ground first, or zero when it came down flat |

A zero vector means "somewhere on it, and the engine cannot say where", not
"dead centre".

Use `applyImpulse(worldDeltaVelocity, spinDelta)` when an addon needs to alter
the physical reaction. It accepts world blocks/second and degrees/second and
passes through the engine's finite-value and magnitude bounds. `velocity()` is
the matching read side. `showStatus(text)` uses the vehicle's action-bar status
surface and yields the built-in speedometer briefly.

`scuff(area, contact, amount)` adds a persistent scrape to the bodywork, in
body-frame blocks, with amount from 0 to 1. It keeps at most ten marks, one
display each, drawn about the same pivot and at the same ride height as the
model itself and rebuilt after a chunk load. A mark's length and its colour —
bare metal through to a black gouge — come from `amount`, and **a second
contact in the same place deepens the mark that is there** rather than spending
another display on it, so a corner somebody keeps putting into a wall ends up
visibly ruined.

Two things to know. The `y` of the contact is measured **up from the vehicle's
base**, so a zero means "the engine had no height to report" rather than "on
the floor" — a mark with no height lands on the waistline. And these are shell
decals, not edits to the model's texture: each reaches a couple of pixels into
the collision shell and a hair outside it, which covers the ordinary gap between
a hitbox stated in blocks and art drawn in pixels, but a hitbox that is a long
way off the bodywork is a decal hanging in the air beside it. Marks are hidden
after part loss, so nothing is left floating across a missing panel.

An animated model is already split into named bone displays. `parts()` exposes
those model-derived names; `detachPart` removes one whole named bone, remembers
that fact on the chassis, gives its displays a temporary vanilla-physics host,
keeps their centred item-model origin above the host's feet so they rest on the
surface, and cleans both up after the supplied lifetime. `detachedParts()` is
the persistent read side. A single-display model has no supported detachable
parts.

A display is filed under **every** bone in its lineage, so a grouping bone — a
`hood` over a left and a right half — is a name `parts()` offers and
`detachPart` accepts, taking everything beneath it. `partOffset(name)` says
where a part sits on the body in blocks, in the vehicle's own frame (`x` right,
`y` up, `z` forward), which is what lets an addon ask which part is nearest the
corner that was hit rather than reading the answer out of the part's name. It
keeps answering after that part has been detached.

`setPosture(pitch, roll)` adds a standing lean to whatever attitude the physics
computed, for showing that something structural has gone; it is drawn, not
driven, so a leaning vehicle does not slide. `setHandling(speedFactor,
turnFactor)` scales what the vehicle can do as fractions of its definition,
applied through the same path as `setSpeedLimit`. Neither is persisted — an
addon re-asserts them from whatever it does persist.

### Driving a damaged machine

`setDamage(VehicleDamage)` tells the handling model what state the machine is
in, and is the asymmetric counterpart to `setHandling`. That one scales what a
vehicle may do and leaves it driving straight; this one describes a vehicle with
a corner missing, and what follows is the ordinary physics reading the ordinary
tyres rather than an effect drawn on top.

```java
vehicle.setDamage(VehicleDamage.builder()
        .wheel(VehicleCorner.FRONT_LEFT, 0)     // torn off
        .wheel(VehicleCorner.REAR_LEFT, 0.45)   // buckled
        .enginePower(0.7)
        .steeringPull(-3)                       // bent steering, pulling left
        .build());
```

A wheel's condition runs from 1 (sound) to 0 (gone), and it is a number rather
than a set of named states because where you draw the line between "damaged"
and "critical" is yours to decide. Most of the effect arrives in the last
quarter of a wheel's life: a soft tyre is not half a missing one.

It reaches the handling model in six places and nowhere else — the steering,
each axle's grip, the drive, the drag and the body's attitude. So a vehicle on
three wheels leans onto the corner it lost, drags round toward it, understeers
where the tyre is gone, and eventually has more drag than drive and stops. None
of that is special-cased; it falls out of the same model every vehicle runs on,
which is why a rider is thrown about by it, a slope still tilts it, and one that
can no longer turn genuinely cannot.

Two things it deliberately is not. It is **not health** — the engine has no
opinion about how much punishment a vehicle takes or whether any of this is
remembered; that is your model, and `data()` is where it goes. And it **never
touches the art**: `detachPart` is that, and they are separate so a vehicle can
limp with all four wheels bolted on (a bent axle) or throw one without the
handling being told (a cosmetic one).

Like `setSpeedLimit` and unlike `setEnabled` it is **not persisted**, so
re-assert it when you adopt a vehicle — a rebuilt rig comes back sound. Calling
it every tick with the same value is free.

`VehicleDamage.NONE` is a vehicle exactly as its pack defines it, and is not
merely "undamaged enough to ignore": the physics takes a different path for it,
so a server running nothing that sets damage drives identically to one built
before any of this existed.

Vehicle definitions can carry opaque `addons:` blocks. Read one with
`vehicle.info().addon("my-addon")`; the engine transports it unchanged through
authored YAML, Studio pushes and temporary edit round-trips but does not
interpret addon policy.

### Building a vehicle the engine does not have

A skateboard is pushed rather than throttled, tucks for speed and flicks round
in the air. None of that is in the format, and it does not need to be: the
handle exposes the pieces.

```java
Vehicle board = engine.vehicles().of(player).orElseThrow();
VehicleInput keys = board.input();       // what the driver is pressing, this tick
if (keys.forward() && board.groundSpeed() < 7) {
    board.nudge(2.2);                    // a kick: +2.2 blocks/s along the heading
    board.dress(player, "myplugin_push"); // wear this emote over the seat's states
}
if (keys.sprint()) board.setSpeedLimit(0); // no limit: tucked
if (board.is(VehicleState.AIRBORNE) && keys.left()) board.spin(-360);
board.undress(player);                   // back to the seat's own table
```

`turnOccupant(player, 270.0)` turns one rider to face a different way from
their seat — a goofy skater on a seat drawn for a regular one — with the rig,
the camera and the seat's hitbox following; `null` hands them back to the
seat. `input()` is the same demand the physics read, so your idea of "the driver
pressed forward" and the engine's are on one tick; where the keys cannot be
read (Spigot, or before 1.21.4) `keys()` is false and only `throttle()` means
anything. `nudge` adds to the speed along the heading and `spin` to the yaw
rate; both are then subject to everything the physics does. `dress` beats the
seat's `animations:` table until `undress` or they get out, and is worn over
the seat's stance the way a state's emote is. The sprint key reaches you and
nothing in the engine acts on it: it is yours to give a meaning.

#### The sneak key, and keeping a rider on

`keys.sneak()` reaches you too, but it is not free the way sprint is: **sneak
is Minecraft's dismount.** Read it on its own and you see it on the tick the
rider is also stepping off, which makes it useless for a trick. So ask to keep
them:

```java
board.holdOccupant(player, true);   // when they get on
...
if (keys.sneak() && board.groundSpeed() > 2) board.dress(player, "myplugin_coffin");
```

**Ask once, when they mount.** The dismount happens on the tick the key goes
down — earlier than your next tick task — so a hold applied after you first
read `sneak()` is applied to somebody already standing in the road.

**A held rider is never trapped.** The hold only bites while the vehicle is
moving; below half a block a second the sneak dismount goes through exactly as
it always did. "Stop, then step off" is the whole of what a rider has to
learn, and a plugin that sets the hold and then crashes, unloads or forgets
cannot strand anybody. It is dropped when they leave, so it never outlives the
ride.

Tell the rider what the key does now, somewhere they will read it. A vehicle
whose shift key silently stopped working is a bug report.

`Placement.seek(seconds)` is the write half of `playhead()`: it moves what is
already playing without restarting it or fading anything, so a clock you
advance yourself — by distance travelled, by fuel burnt, by anything
continuous — drives the animation. Grow it slower than real time and the cycle
runs slow; stop growing it and the cycle stops. A vehicle gets this off one
line of YAML (`animation-follows-speed`, see FORMAT.md); this is for the cases
that are not a vehicle. Advance it every tick from something smooth rather than
jumping it about: a rig has one clock, and every bone on it reads that clock.

#### Moving the vehicle's own model

`dress` is for the rider's body. `perform` is the same door for the vehicle's:
it plays one of the MODEL's animations instead of whatever its `animations:`
table says.

```java
bike.perform("wheelie");   // the whole bike tips back about the rear axle
...
bike.rest();               // back to the state table, wheels where they were
```

A bike's wheelie, a barspin, a digger's arm coming down: motion that belongs
to the model and is decided by a plugin rather than by which of six words
describes how fast the thing is going. A vehicle with no `animations:` at all
can be given one this way, so a pack need not have anticipated your trick.

**It loops for as long as it is set**, exactly as a state's animation does,
whatever the animation was authored as — so the length of a trick is yours to
time: set it, count your ticks, `rest()`. That is the same shape as wearing a
push emote for the length of a kick, and it is deliberately not a one-shot
with a callback: there is no tick you could be told about that you were not
already having.

**A performed animation runs on real time**, even on a vehicle with
`animation-follows-speed`. That link exists so a wheel turns because the
vehicle moved, and a trick is not a wheel — a barspin that ran at a quarter
speed because the rider was braking into it would be the mechanism showing
through. The speed-driven playhead is put aside while it holds and picked up
where it was on `rest()`, so the wheels do not jump when the trick ends.

One animation at a time, because a rig has one clock — the same rule the state
table lives under. An animation that has to keep the wheels turning while it
plays has to turn them itself.

#### Coming down

An aircraft's height is its own — held once it is fast enough, climbed and
dived on the keys, sunk at its `stall-sink` when it is too slow. `setDescent`
replaces all of that with one number for as long as it is set:

```java
Vehicle chute = engine.vehicles().spawn(player.getLocation(), PARACHUTE).orElseThrow();
chute.seat(player);
chute.setDescent(45);                      // freefall: down at 45 blocks/s, whatever the speed
...
chute.setDescent(keys.jump() ? 1.5 : 5.5); // under the canopy: a flare, or full flight
```

The vehicle comes down at that rate whatever it is doing, the climb and dive
keys do nothing to its height, and it lands where it reaches the ground and
sits there. Throttle and steering are untouched, so it still goes where it is
pointed — a glide, not a drop. It is the door for anything that comes down at
a rate the PLUGIN decides: a parachute, whose canopy sinks at one rate, brakes
at another and flares at a third; a glider; a helicopter you have just run out
of fuel. Call it every tick from something smooth when the rate changes, and
note that it is **not clamped** to the editor's flight bounds — a freefall is
allowed — and, like `setSpeedLimit`, not remembered across a chunk unload.
Zero holds the height; a negative number clears it. A vehicle under a descent
keeps its nose level rather than pitching into the dive, and still banks into
its turns.

`dressVariant(player, "goofy")` makes one rider wear a VARIANT of whatever
their seat's state table says - `moving` becomes `moving_goofy`, falling back
to the plain one where no such emote exists. For a per-person fact a seat
cannot know, a skater's stance being the case it was written for. Without it a
plugin has to take over the whole state table and re-implement its
fall-through rules to get back what the seat was already doing.

A vehicle that bails (`bail:` in its YAML, see FORMAT.md) fires
`VehicleBailEvent` before it throws anybody, and it is cancellable. That is
how a server-side switch for it is written: the pack states the rule, your
plugin's config decides whether it applies. Cancelling leaves the rider
aboard and takes nothing off them.

Every handle is main thread only, like everything that touches an entity.
`ids`, `info` and `isRiding` are safe anywhere.

## HUD overlays

An overlay is a picture the engine keeps on a player's screen — a stat block, a
health bar, a crouch indicator. Server owners draw them (in Studio, or by hand);
your job is deciding who sees one and what numbers are in it.

```java
engine.overlays().show(player, "studio:health");  // and it stays until hidden
engine.overlays().hide(player, "studio:health");
engine.overlays().hideAll(player);
engine.overlays().showing(player);                // what they are wearing
```

Held rather than sent, so there is a `hide` rather than a duration: a caller who
wants one for five seconds schedules the hide, and one who wants it until a
fight ends hides it when the fight ends.

### Feeding it values

An overlay's text and its progress bars can print `{name}` placeholders. **Any
name at all works, and yours wins.**

```java
engine.overlays().set(player, "speed", "83");
engine.overlays().set(player, "stamina", "40");
engine.overlays().set(player, "stamina", null);   // remove it
engine.overlays().value(player, "speed");         // what you last set
```

Three sources are asked, in this order:

1. **Whatever you set**, because a plugin that took the trouble to publish a
   number means that number and must not be overruled by a built-in that
   happens to share its name.
2. **The engine's built-ins** — `player`, `health`, `health_max`,
   `health_percent`, `food`, `level`, `xp`, `ping`, `world`, `x`, `y`, `z`,
   `direction`, `time`, `day`, `gamemode`, `online`, `max_online`, `air`,
   `uuid`, `displayname`, `name`. These are the questions the server can already answer
   about a player, so an overlay using them works with no code at all.
   (`name` is a second spelling of `player`.)
3. **PlaceholderAPI**, if it is installed.

Anything none of the three answers draws as nothing, rather than as a leftover
brace: a gap reads as "no value yet" where `{speed}` reads as a broken pack.

**Per player, not per overlay.** A number called `speed` means the same thing to
everything on that player's screen, which is what stops two overlays disagreeing
about it. Values are drawn on the next redraw rather than immediately, so
setting several in a row costs one draw rather than one each.

A **progress bar** reads the same names. Its `max` is either another placeholder
or a plain number the author typed, so a stamina bar out of 100 needs one call:

```java
engine.overlays().set(player, "stamina", String.valueOf(current));
```

**`set` and `value` are safe from any thread**, and are the one part of this
that is: they write into a concurrent map and draw nothing, precisely so a
caller updating six values in a row costs one redraw rather than six. The loop
picks them up within about a second and a half. `show`, `hide` and `hideAll`
draw, so those are main thread only.

## Icons in your own text

```java
String line = engine.icons().format(config.getString("welcome"));
```

Every `:namespace:id:` becomes its picture. An ID that names nothing is left
exactly as written, so text never silently loses a chunk of itself.

## Events

All cancellable unless the row says otherwise.

| Event | When |
|---|---|
| `ContentLoadEvent` | Content finished loading and the packs are built. **Listen to this before anything else** — a reload replaces every definition. Not cancellable |
| `PackSendEvent` | A pack went out to a player. Not cancellable |
| `ItemUseEvent` | A custom item was right- or left-clicked. Cancelling also cancels the vanilla use |
| `ModelPlaceEvent` | A model is about to be put down |
| `ModelBreakEvent` | A model is about to be broken. Carries a drop flag separate from cancelling |
| `ModelInteractEvent` | A placed model was right-clicked |
| `ModelAnimationEvent` | A rig is about to play an animation |
| `ModelAnimationEndEvent` | One ended — finished, replaced or stopped. Not cancellable |
| `ModelSeatEvent` | Somebody is about to sit on a chair, a seat bone, **or a vehicle seat**. Cancelling leaves them standing |
| `VehicleEnterEvent` | Somebody is about to get into a vehicle — fires after `ModelSeatEvent` for the same seat, with the vehicle and the seat attached. "Is this your car" lives here |
| `VehicleExitEvent` | Somebody got out, or was taken out. Carries why — dismounted, ejected, quit, reloaded, removed, unloaded, shutdown. Not cancellable: a player who cannot be let out is trapped. `RELOADED` is followed by them being put back a tick later, with no enter event |
| `VehicleMoveEvent` | A vehicle is about to move — once per tick, only while it is going somewhere. Cancelling stops it dead, like a wall. The event for a region it may not enter; **not** the event for fuel, which is `Vehicle.setEnabled` |
| `VehicleStateEvent` | What a vehicle is doing changed — set off, stopped, took off, went under. The same set that drives its animation, on the change rather than every tick. Not cancellable |
| `ModelBindEvent` | A model is going on an entity, or coming off one — a boss, an NPC, anything that is not ours |
| `EntityDeathEvent` | A custom entity died. Bukkit's own event carries the drops; this one says what it was. Not cancellable |
| `PlayerLiquidEvent` | Somebody went into one of your liquids, or came out. Fires on the crossing, not every second. Not cancellable |
| `EmoteStartEvent` | An emote is about to start |
| `EmoteEndEvent` | An emote ended. Carries why — finished, stopped, moved, damaged, quit, shutdown |

**The engine decides whether something can physically happen, never whether it
is allowed to.** Region protection, plot ownership, an event world where
nothing may be built: those are rules about your server, which the engine
cannot see. That is what these events are for.

```java
@EventHandler
public void onPlace(ModelPlaceEvent event) {
    if (!myRegions.mayBuild(event.getPlayer(), event.block())) {
        event.setCancelled(true);
    }
}
```

```java
@EventHandler
public void onLoad(ContentLoadEvent event) {
    // Everything the API can answer is answerable by now, on a reload as
    // much as at startup. Anything cached and derived from content is
    // rebuilt here.
    menus.rebuild();
}
```

**A plugin that loads after RP Engine misses the STARTUP one** — the event has
been and gone before its listener exists. That is not a case to work around
with a delayed task: ask the API directly in `onEnable`, and use the event for
the reloads after it.

### Priority

Every one of these is read **after all handlers have run** — the engine calls
the event, then asks whether anything cancelled it. So priority does not order
you against the engine, only against other plugins listening to the same event.
Listen at `NORMAL` unless you are deliberately arbitrating with another plugin,
and treat `MONITOR` as read-only: a cancel there still counts, which makes it a
cancel nobody downstream can see coming.

Where priority does matter is vanilla's own events. The engine listens to those
at `LOW` with `ignoreCancelled = true`, so a plugin that cancels a
`PlayerInteractEvent` at `LOWEST` stops a custom item's use before RP Engine
ever sees the click — which is usually exactly what a protection plugin wants.

## Shipping content in your own jar

An addon carries its models, items, vehicles and emotes as an ordinary content
folder inside its jar, under `resources/content/<namespace>/`, and installs it
into the engine on enable:

```java
if (AddonContent.install(this, engine, "skateboards")) {
    engine.reload();   // only when something actually changed
}
```

A `.version` stamp beside the files says which build last wrote them, so
restarting on the same jar copies nothing and reloads nothing - a reload
rebuilds every pack on the server, which is not something to do on every boot
for files that have not changed. A new build overwrites its own files and only
its own: whatever the server owner added to the folder stays, because the
folder is theirs to extend. Files are written whole or not at all.

Catch the `IOException` and disable your plugin over it. Content that did not
install is items that do not exist, and failing at boot beats a command that
says nothing an hour later.

## Content of your own

A plugin can be a content source rather than shipping a folder. Claim a
namespace, define into the handle, release it when you disable.

```java
ClaimResult claim = engine.registration().claim("myplugin", ContentSource.EMBEDDED);
claim.namespace().ifPresent(ns -> ns.define(ContentKind.ITEM, "ruby"));
```

The handle is what proves ownership: holding `myplugin` cannot define
`otherpack:thing`, so two sources loading at once cannot corrupt each other's
half of the ID space. `EMBEDDED` content is not second class — same registry,
same ID rules, and the pack builder cannot tell it from a hand-written folder.

### Shipping a content folder in your jar

The usual way to ship content WITH a plugin — a skateboard addon, a furniture
set — is a content folder inside the jar, exactly as a server owner would
write it (`FORMAT.md`), unpacked into `plugins/RPEngine/content/<namespace>/`
on enable and reloaded once:

```java
Path content = engine.getDataFolder().toPath().resolve("content").resolve("myplugin");
if (!Files.exists(content.resolve(".version")) || !Files.readString(content.resolve(".version")).equals(version)) {
    unpackResources("content/myplugin", content);   // your own copy loop over the jar
    Files.writeString(content.resolve(".version"), version);
    engine.reload();                                 // rebuilds every bundle, re-sends to everybody
}
```

`reload()` is `/rp reload`: main thread, every bundle rebuilt and re-sent, so
call it once after your files are in place and only when they changed. Use a
namespace nobody else would — your plugin's name — and put your plugin's name
in `depend` so you enable after the engine. The engine has already built its
packs by the time you enable; the reload is what folds yours in. Everything in
the folder is ordinary content: items, a vehicle, its emotes, its model, and
the server owner can read it, edit it, and see exactly what you installed.

## Threading

Reads that ask what the server **holds** — `ids`, `info`, the whole of
`ContentRegistry` — are safe from any thread. Anything touching a player, an
entity or a world is main thread only, and says so on the method.

## What is not API

`core.*`, the content loader, the pack builder, and the sync client. If you
find yourself wanting one, that is the signal something belongs in `api` —
raise it rather than reaching in, because a patch release will move it.
