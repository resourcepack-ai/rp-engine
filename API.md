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

`face` exists because a carried body and a carried camera are different
questions. A worn rig normally turns with its wearer's look, which is right for
somebody walking; it is wrong for a passenger, whose body belongs to whatever
they are riding while their head is their own. Pass the direction the seat
points, **every tick** — a vehicle turns — and `null` to hand the rig back to
their look. It only moves a rig that arrived through your `wear`, so somebody
who was already mid-emote when they sat down keeps it.

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

Every handle is main thread only, like everything that touches an entity.
`ids`, `info` and `isRiding` are safe anywhere.

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

## Threading

Reads that ask what the server **holds** — `ids`, `info`, the whole of
`ContentRegistry` — are safe from any thread. Anything touching a player, an
entity or a world is main thread only, and says so on the method.

## What is not API

`core.*`, the content loader, the pack builder, and the sync client. If you
find yourself wanting one, that is the signal something belongs in `api` —
raise it rather than reaching in, because a patch release will move it.
