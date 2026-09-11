# The content folder

This is the format a server owner writes by hand. It is the **primary** way
content gets into RP Engine, not a fallback: everything the engine can do has
to be expressible here, because a Studio push compiles down to the same
registry entries and gets no capability this format lacks.

It is specified here before the loader is written, and the loader is tested
against this document rather than the other way round.

## Layout

```
plugins/RPEngine/content/
  mypack/                    <- the folder name IS the namespace
    pack.yml                 <- required
    items/       *.yml
    sounds/      *.yml
    fonts/       *.yml
    screens/     *.yml
    huds/        *.yml
    recipes/     *.yml
    entities/    *.yml
    liquids/     *.yml
    vehicles/    *.yml
    emotes/      *.json      also *.yml - see Emotes below
    assets/                  -> assets/mypack/ in the built pack
      textures/  **.png
      models/    **.json     Blockbench exports (see below)
      sounds/    **.ogg
      fonts/     **.png
    overrides/               -> assets/minecraft/ in the built pack
      textures/block/stone.png
    pack.png                 optional, offered to the bundle
```

**A category folder exists only if something reads it.** There is no
`models/` — a placed model is a `place:` block on the item, below. Any other
folder is warned about by name, which is what catches `item/` and `Sounds/`.

**The folder name is the namespace**, and it has to satisfy
`ContentId.isValidNamespace`: lowercase `a-z`, digits, and `_ . -`. A folder
named `MyPack` is refused rather than lowercased, because `MyPack` and
`mypack` would be one ID to us and two to the client.

**Definitions and assets never mix.** Every raw file lives under `assets/`,
every YAML definition lives in a category folder. That is why `models/` can be
a folder of YAML while `.bbmodel` files sit in `assets/geometry/` without the
two ever fighting over a name.

**`assets/` is namespaced and `overrides/` is not.** Everything under
`assets/` lands under `assets/<your namespace>/` in the built pack, where it
cannot collide with anybody. Everything under `overrides/` replaces a vanilla
file, landing under `assets/minecraft/`, and is therefore the **only** way two
packs in one bundle can fight. Writing `assets/minecraft/` yourself does not
work and is not meant to: the folder is named for what it does so the risk is
visible while you are writing it, rather than on the day somebody installs your
pack next to another one.

If two packs in a bundle override the same file, the later namespace
alphabetically wins and both are named in a warning. Arbitrary, but the same on
every machine, which is the property that matters.

**Models live in `assets/models/`.** An item names one with `model: <name>`,
and either form works:

- `assets/models/<name>.bbmodel` — the Blockbench **project**, saved straight
  out of the editor. Preferred, and the one to use: its textures are inside it,
  so a model is one file you can hand to somebody.
- `assets/models/<name>.json` — a Java model **exported** from Blockbench
  (File > Export > Java Block/Item model), with its PNGs shipped beside it.

With no `model:` an item is a flat sprite, which is what a vanilla item is.

What a `.bbmodel` loses on the way in, because the format cannot express it:
meshes (convert them to cubes first), and rotations beyond one axis at one of
-45, -22.5, 0, 22.5, 45 degrees. Animations are carried through untouched;
nothing plays them yet.

Bare texture paths inside it (`item/sword`) are rewritten into your namespace;
one that already carries a namespace (`minecraft:item/stick`) is left exactly
as written, because somebody who typed that meant it.

A model **an item actually uses** does not ship: it is read, rewritten, and
written out as `assets/<namespace>/models/item/<id>.json`, and the original
goes rather than having every player download both. A model **nothing
references** stays exactly where you put it — that is how a shared parent
model works, and dropping those would break every model that inherits one.

An item can also wear another item's model with `copy-model: mypack:other`.
Nothing is generated for it; it points at what is already there, which is how
a pack ships five items that look the same without five copies of one file.

**`pack.png` is offered, not claimed.** A bundle has one icon and takes it from
the first namespace alphabetically that ships one. The rest get a warning
saying theirs is unused.

**The category folder decides the kind.** `items/` yields `ITEM`, `blocks/`
yields `BLOCK`, and so on. A folder that is not one of them is ignored with a
warning rather than an error, so a `README/` or a `.git/` in somebody's pack
does not stop it loading.

There is deliberately **no `furniture/` folder**: putting a model down is
something an item does, not a kind of content. See below.

## Making an item do something

```yaml
wand:
  material: STICK
  actions:
    right_click:
      - cooldown: 5
      - message: "&bWhoosh."
      - sound: mypack:chime
      - effect: SPEED 10 2
      - console: "effect give {player} minecraft:levitation 3"
```

A trigger holds a **list of steps**, run in order. Each step is one key, so it
needs its own `-`; two keys in one entry is a missing dash, and that is a load
error rather than a step that quietly never runs.

Triggers: `right_click`, `left_click`, `attack`, `drop`, `consume`,
`block_break`, `shoot`, `break` (durability ran out), `pickup`.

`break` fires after the item is already gone and cannot be cancelled — that is
vanilla's shape, not ours. It is still worth having for the sound and the
message.

There is deliberately no `wear`/`unwear`: Spigot has no equip event, and the
alternatives are a Paper dependency or polling everybody's armour every tick.
Neither is worth it for a trigger.

| Step | What it does |
|---|---|
| `message` | A line of chat to whoever used it. `&` colour codes. |
| `broadcast` | The same, to everybody. |
| `actionbar` | A line above their hotbar. |
| `console` | Runs a command as the console — how an action reaches something the user may not do themselves. |
| `run` | Runs a command as the user, with the user's own permissions. |
| `sound` | `mypack:chime`, or a vanilla key like `minecraft:block.anvil.land`. Optional volume and pitch. |
| `effect` | `SPEED 10 2` — type, seconds, level. Level is 1-based, as it reads. |
| `give` | `mypack:ruby 3`. What will not fit drops on the floor. |
| `take` | Takes this many off the stack. |
| `cancel` | Cancels the vanilla use, so a wand built on a bucket does not fill with water. |
| `cooldown` | Seconds. **Stops the run** if it has not been that long — so put a `message` before it and the refusal says something. |
| `permission` | Stops the run unless they have it. |

Text can carry `{player}`, `{uuid}`, `{world}`, `{x}`, `{y}`, `{z}`, and any
PlaceholderAPI placeholder if that plugin is installed.

**A step that cannot run is skipped and the rest still run.** A misspelled
potion costs that line, not the command after it.

**This is not scripting and is not going to become it.** There is no branching,
no state and no expression here, because the moment there is an `if` it is a
language and a bad one. Anything past these verbs is a plugin's job, and
`ItemUseEvent` — ID, stack, action, block — is what it listens to. Cancel that
event and the vanilla use is cancelled too, and the item's own actions do not
run either: the event is the stronger statement of the two.

## The numbers on an item

An item here is a vanilla item wearing a different model, which is what makes
the whole ID scheme work — and it left a custom sword hitting exactly as hard
as the stick underneath it. These are the vanilla components that fix that:

```yaml
sword:
  material: IRON_SWORD
  model: sword
  durability: 500                     # replaces the material's own
  enchantments: { sharpness: 3, unbreaking: 2 }
  attributes:
    - attack_damage: 9
    - attack_speed: -2.4
    - max_health: { amount: 4, operation: add, slot: hand }
  food: { nutrition: 6, saturation: 7.2, always: false }
```

- Names are **vanilla's, unprefixed** — `sharpness`, `attack_damage` — because
  that is what is written on the wiki you are reading them off.
- An attribute is `name: amount` for the usual case, or a block with an
  `operation` (`add`, `multiply_base`, `multiply`) and a `slot` (`hand`,
  `head`, `chest`, `legs`, `feet`, `any`).
- **Every one of these is a real item component.** The game applies them, other
  plugins read them, and an item that leaves your server in somebody's
  inventory keeps them. Nothing here needs the plugin present to work.
- A name that resolves to nothing is one line in the console the first time the
  item is given, not a load error — the registries need a running server, and
  the definition parser deliberately does not have one.

## WorldGuard

Two region flags, if WorldGuard is installed:

```
/rg flag spawn rpengine-place deny
/rg flag arena rpengine-use deny
```

`rpengine-place` covers putting a model down and breaking one; `rpengine-use`
covers using a custom item. Both allow by default, and anything that goes wrong
allows too — a server should never be locked out of its own content by a hook.

## Icons in chat

Set `chat.icons: true` in `config.yml` and anybody with `rpengine.chat.icons`
can type `:wave:` to get the icon called `wave`. `:mypack:wave:` where two
packs use one name.

Off by default. A name that is not an icon is left exactly as typed, so
`10:30`, `:)` and a URL all survive.

Two more flags, both of them things vanilla nearly does already:

```yaml
crown:
  material: GOLDEN_APPLE
  hat: true             # right-click to wear it, any item at all
  keep-on-death: true   # survives dying
```

`hat` is the click that saves a drag — vanilla already lets anybody wear
anything by dragging it into the helmet slot. A head that is already wearing
something is left alone rather than swapped.

There is a third, for one specific job: `liquid: mypack:acid` makes the item a
bucket of that [liquid](#liquids), which is how a pond gets built rather than
marked out afterwards.

**There is no `gun`, `vehicle` or `music_disc` here**, and that is the same
line as the actions list: those are whole games rather than item properties,
and an engine that shipped a half-opinionated gun would be one every server
has to fight. `ItemUseEvent` is what they are built on.

An item can also carry a permission:

```yaml
wand:
  material: STICK
  permission: mypack.wand
```

Checked when the item is **used**, not when it is given or held — a permission
that stopped somebody holding an item would mean taking it out of their
inventory, which is a thing to do to somebody's stuff rather than a decision an
engine makes.

## Placing a model

An item with a model can be put down in the world. Say so on the item:

```yaml
chair:
  material: PAPER
  geometry: chair
  place:
    facing: cardinal     # cardinal | diagonal | free | fixed
    scale: 1.0
    solid: false         # true puts a barrier behind it
    vehicle-collision: true  # false lets vehicles drive through it
    seat: 0.5            # sit on it, this far above its base. 0 is no seat
    light: 0            # 0-15, what it gives off. A lamp wants 14.
    surface: floor      # floor | wall | ceiling | any
    drop: mypack:shard  # what breaking it gives back. Default: itself
    # width and height are the hitbox, in blocks. Leave them out and they are
    # measured off the model, which is almost always what you want.
```

Not a category of its own, because an ID is unique across the whole registry:
`mypack:chair` cannot be an item and a placed model at once, and needing
`mypack:chair` plus `mypack:chair_placed` for one chair is the sort of tax that
makes a format feel like paperwork. It also means the item and the thing you
put down can never disagree about which model to use.

Right-clicking a block with that item places it. Punching it takes it back. It
is two entities — a display for what you see, an interaction for what you can
hit — both tagged in persistent data, so a placed model is an ordinary
chunk-saved entity and survives a restart with no file of its own.

**It renders at its real size.** 16 model units to the block, no transform
applied, so a model built to y=32 stands two blocks tall exactly as authored.
`scale:` multiplies that if you want it bigger.

**The hitbox is measured from the model** unless you state one. Whoever built
it already decided how big it is, and a hitbox smaller than what you can see
means most of a statue cannot be punched and the part that can is buried
inside it.

`solid: false` by default: a display entity has no collision at all, and
`solid: true` puts an invisible barrier block behind it, removed when the model
is broken.

`vehicle-collision: true` by default, and it is a different question with a
different answer. `solid` is about a **walking** player and is bought with a
barrier block — one cube, at the anchor, whatever shape the piece is — which is
why it is opt-in: it writes to the world. This is about a **driving** one, it
covers the whole piece rather than one block of it, and it costs the world
nothing: a vehicle asks the placed models around it where they are.

**It uses the model's own cubes**, not a box round them, so a vehicle can be
driven between the legs of a table and through an arch you modelled, and a
fence stops one along its whole length rather than only at its anchor block.
Any placement angle works, not only the four cardinals.

So a car stops at your fence whether or not anybody can walk through it, which
is what you want in every pack that has a fence. Set it to `false` for the
things a vehicle is meant to drive over rather than into — a rug, a manhole
cover, a painted road marking. Anything low enough to be a kerb is driven onto
and over anyway, so a flat piece rarely needs it; a piece a vehicle can stand on
holds it up, so you can lay a bridge out of models and drive across.

`light:` works the same way and for the same reason — a display entity emits
nothing, so a real light block goes in the anchor and is taken away when the
piece is broken. **A solid piece cannot also be a lamp**: one block cannot be a
barrier and a light at once, and the barrier wins.

`surface:` refuses a placement rather than turning it sideways. A torch on a
wall, a chandelier under a ceiling, and a chair on neither.

Category folders are walked recursively, so `items/weapons/swords.yml` is
fine. The subfolder is organisation only: **it contributes nothing to the ID**.

### Animating one

If the `.bbmodel` has animations in it, the piece moves. Nothing to declare:
the keyframes are read out of the save file, and a piece with any is placed as
one display entity per moving bone instead of one still one, retimed by the
server a few times a second.

```
assets/models/windmill.bbmodel     bones and keyframes, as Blockbench saved it
```

A looping animation loops on its own. A one-shot plays when the piece is
right-clicked. That pair is derived rather than declared, because a `.bbmodel`
has no notion of a trigger and those are the two things an animation is
usually for.

Three things worth knowing:

- **The save file, not an export.** Blockbench's *File > Export > Java
  Block/Item model* writes cubes and nothing else — no bones, no keyframes.
  Save the project into `assets/models/` and it is read whole.
- **Only bones animate.** A keyframe on a cube inside an animated bone is
  played too, composed inside the bone's; a keyframe on a loose cube moves
  that cube. Anything with no keyframes anywhere stays still and rides along
  as one piece.
- **It costs entities.** One display per moving bone, plus one for the
  remainder. A ten-bone model standing in a world is eleven entities, so an
  animated model is a centrepiece rather than something to place a hundred of.

Right-clicking a piece that animates plays it rather than sitting on it.
Shift-right-click still sits, if it has a `seat`.

#### How an animation plays

Blockbench's own **loop / hold / once** comes across as authored. `hold` is the
one worth knowing: it stops on the last frame and stays there, which is what a
door, a lid and a drawbridge all are — without it they spring shut the moment
they finish opening.

The rest is a decision about your server rather than about the model, so it
lives here:

```yaml
chair:
  material: PAPER
  model: chair
  place:
    animations:
      spin:
        mode: loop      # loop | hold | once, overriding the .bbmodel
        speed: 0.5      # half the authored speed
        priority: 10    # wins when two animations claim one trigger
        blend: 0.25     # seconds to ease in and out of it
        layer: 0        # 0 plays instead of what is running; 1+ plays OVER it
        weight: 1.0     # how strongly, 0-1. Half a wave is a smaller wave
        bones: [torso]  # only these bones, and everything hanging off them
```

- **`blend`** is the difference between a model that snaps between poses and
  one that moves. A quarter of a second covers most things. **It does nothing
  on a vehicle**, whose animations the engine changes off the vehicle's state:
  those always cut, because there is no smooth path between a wheel spinning
  one way and the same wheel spinning the other.
- **`priority`** matters once a model has more than one animation on the same
  trigger. Higher wins; equal falls back to the order they are in the file.
- The same walk cycle is a stroll on one server and a sprint on another, which
  is why `speed` is here and not a second Blockbench file.

An animation nobody mentions plays exactly as authored.

**`layer` is how two animations play at once.** Layer 0 is the base — a walk
cycle, an idle — and only one plays at a time. Anything above it composes on
top, so a wave on layer 1 plays over whichever gait is running rather than
replacing it. One animation per layer, so waving twice replaces the first wave
and not the walk.

Nothing needs to know about this to use it: `/rp` and the API play an animation
by name, and an animation that names a layer goes on that layer.

`bones:` is how a layer is made to move part of a model. Naming a bone reaches
**everything hanging off it**, so `bones: [torso]` moves the arms with it —
which is what "upper body only" means in practice. `weight:` scales how far the
layer moves what it touches: rotation and position toward zero, scale toward 1,
because 1 is what no scaling is.

### What a hit on a bone is worth

```yaml
  place:
    hitboxes:
      head: 2.0
      wing: 0.5
```

Only bones with a `b_` or `ob_` name have hitboxes at all. This says what a hit
on each is multiplied by before it reaches the mob — the pack's rule, not the
engine's. Matched against the bone's **own** name and not its lineage: a hitbox
is a place you aimed at, and counting everything inside a torso as a torso hit
would make a head worthless the moment it was inside one.

### Sitting on one

`seat:` is how far above the model's base a player's backside goes, in blocks —
about `0.5` for a dining chair. Right-click to sit, shift to get up.

A seat is not always in the middle of the piece — a bench, a car, an L-shaped
sofa — so it can be three numbers instead:

```yaml
  place:
    seat:
      x: 0.4     # to the piece's RIGHT. Negative is left
      y: 0.5     # up, the same number as the short form
      z: -0.15   # in FRONT of it. Negative is behind
```

**Side and forward, not world x and z.** They turn with the piece, so a bench
placed facing east seats people along itself rather than across it.

If every chair on your server sits people slightly wrong, that is the game
drawing a seated player rather than your number being off: put a nudge in
`models.seat-offset` in `config.yml` and `/rp reload`. Positive is higher, and
it moves every seat at once.

The seat itself is a marker armour stand the player rides, which is the only
way to sit somebody in vanilla. It is **never saved**: gone on dismount, on
quit, when the model is broken, and when the server stops. That is deliberate,
because the way this feature usually rots is a world full of invisible stands
somebody can stand on.

One player per model, and a model with a seat keeps its hitbox.

## Armour

Any item can be worn, with its own art:

```yaml
crown:
  material: GOLDEN_HELMET
  armor: head          # head | chest | legs | feet
```

Ship one texture, and which one depends on the slot:

- `legs` → `assets/textures/entity/equipment/humanoid_leggings/<id>.png`
- everything else → `assets/textures/entity/equipment/humanoid/<id>.png`

Leggings are a different layer rather than a second one: the game draws them
from their own narrower sheet, so art drawn for the wide one puts a belt buckle
on somebody's knee.

This is vanilla's own equipment path, which arrived in 1.21.4. It replaces the
old tricks outright — dyed leather spends a colour that can then never be used
for anything else, and armour trims are stuck in the trim palette.

**On an older server this is the one part of the format that is reduced.**
Below 1.21.2 only materials that are already armour can be worn, so `armor:` on
a stick does nothing; on 1.21.2 and 1.21.3 any item can be worn but draws with
vanilla artwork. The item itself works either way, and the plugin says which
you are on at startup.

## Entities

A real mob wearing a model.

```yaml
guard:
  type: ZOMBIE         # required. Chosen for BEHAVIOUR: the looks are replaced
  model: mypack:guard  # an item id, whose model it wears
  name: "&cTemple Guard"
  health: 40
  scale: 1.2
  silent: false
  tags: [temple, boss]
```

It is genuinely a mob: its own AI, its own loot, found by `@e[tag=boss]`, seen
by every other plugin. Pick `type` for how it should *behave* — a zombie hunts
and burns, a villager wanders and flees, an armour stand does nothing at all.

The model is an `ItemDisplay` riding the mob, with the vanilla body made
invisible rather than removed, so the hitbox stays where the model looks. A
custom entity never despawns: one that vanished because a player walked away
would leave its model standing there, because a removed mount ejects its
passengers rather than taking them.

`/rp spawn mypack:guard` puts one where you stand.

### Bones that do something

Name a bone with one of these prefixes in Blockbench and it does more than get
drawn. **They are Model Engine's prefixes**, deliberately: a rig you already
have, or bought, works here without being re-authored.

| Bone name | What it does |
|---|---|
| `h_head` | Turns to look at whatever its mob is targeting. |
| `hi_head` | The same, and every bone under it inherits it. |
| `b_wing` | A hitbox of its own — the mob is hit on the wing you aimed at. |
| `ob_wing` | The same, turning with the bone. |
| `p_seat1` | Somewhere to sit. Right-click it. |
| `mount` | Where the driver sits. One per model. |
| `tag_name` | Where a name floats, rather than inside the model's knee. |

Anything else is an ordinary bone, which is nearly all of them. A model needs
none of this.

Two honest limits:

- **`h_` and `mount` only mean something on a model worn by a mob.** A placed
  statue has nothing to look with and nobody to carry.
- **A head follows its mob's TARGET, not its gaze.** Bukkit does not expose a
  mob's head yaw — the yaw you can read is the body's — so "look where it is
  looking" is not a question the API can answer. Looking at what it is fighting
  is, and is what you actually want from a boss.
- **A sub-hitbox forwards damage** to the mob rather than having health of its
  own. Aiming matters; a wing is not separately killable.

## Putting a model on a mob

Any entity on the server can wear a model — one MythicMobs spawned, a Citizens
NPC, a shopkeeper, a mob another plugin owns. It is **not replaced**: it keeps
its AI, its loot, its health and its hitbox, and every plugin holding a
reference to it still has the same entity. Its vanilla body is just made
invisible.

Look at one and:

```
/rp bind mypack:golem
/rp unbind
```

From MythicMobs, which is where this is usually wanted:

```yaml
Skills:
- rpmodel{model=mypack:golem} @self ~onSpawn
- rpanimate{animation=roar} @self ~onDamaged
- rpunmodel @self ~onDeath
```

From a plugin, `Models.bind(entity, id)`, `unbind`, `animate` and
`modelOn`.

The model is an **item ID** — the same ID a `place:` block names — so one model
can be stood in a world and worn by a mob without being written twice. If it
animates, the bound copy animates: it faces wherever its host is facing, and
`rpanimate` plays an animation by name.

The difference from `entities:` above is who spawns the thing. That defines a
mob **we** spawn, with a model, from a content file. This puts a model on a mob
that already exists and belongs to somebody else.

## Vehicles

A model people ride, with somewhere for up to eight of them to sit.

```yaml
# vehicles/cars.yml
hatchback:
  model: mypack:hatchback_item  # an item id, whose model it wears - not this id, which is taken
  name: "&bHatchback"
  medium: land              # land | water | air
  speed: 18                 # top speed, blocks per second
  acceleration: 7.5         # how fast it gets there
  turn-speed: 140           # degrees per second the body swings round
  turn-in-place: false      # optional - can it turn while standing still?
  weight: 14                # 1-100. Slower to get going, and harder to shove
  scale: 1                  # how many times its built size it is drawn
  jump: false               # land and water: space jumps instead of braking
  hitbox:                   # what players click to get in, in blocks
    width: 1.4              # side to side
    height: 1.2             # up from the base
    length: 3.0             # front to back
  seats:
    - {role: driver,    x: -0.4, y: 0.6, z: 0.6}
    - {role: passenger, x:  0.4, y: 0.6, z: 0.6}
    - {role: passenger, x: -0.4, y: 0.6, z: -0.5}
    - {role: passenger, x:  0.4, y: 0.6, z: -0.5}
  capes: true               # optional — is a rider's cape drawn in it?
  speedometer: true         # optional — does its driver see their speed?
  animations:               # optional — which animation plays when
    idle: parked
    moving: drive
  sounds:                   # optional — what it sounds like, and when
    idle: mypack:tickover
    moving: mypack:engine
  particles:                # optional — what it throws, and when
    - effect: smoke
      states: [moving, reversing]
      x: 0
      y: 0.3
      z: -1.5
      count: 2
      interval: 2
  addons:                   # optional - configuration owned by installed addons
    vehicle-status:
      enabled: true
      max-health: 100
      damage-multiplier: 1
      minimum-collision-severity: 3
      disabled-health-percent: 20
      disable-at-threshold: true
      detachable-parts: [hood, left_door]
      part-detach-severity: 9
      part-despawn-seconds: 20
      crash-force-multiplier: 1
      broken-part-lean-degrees: 12
      status:
        enabled: true
        interval-seconds: 1
        format: "&cHealth {health}/{max_health} &7({health_percent}%) {state}"
```

`addons:` is deliberately opaque to RP Engine. Each child key belongs to that
installed addon and is also carried through Studio pushes and `/rp edit`
round-trips. An absent addon still means nothing: the engine does not fail a
vehicle because a block names a plugin the server has not installed.

**Right-click the body to get in** and you take the first free seat — the
driver seat first, so whoever gets in first is driving. Right-click a
particular seat to take that one instead. Without a `hitbox:` a vehicle is a
one-block cube, which is clickable but much smaller than most vehicles look.

`/rp vehicle mypack:hatchback` parks one where you stand,
`/rp vehicle remove` takes away the nearest, and `/rp vehicles` lists them.
**Getting in is a right-click on a seat**, not a command.

**The vehicle's item parks it too.** Right-click the ground holding the item
named by `model:` and the vehicle appears there, facing the way you face, and
the item is used up (not in creative). That is what makes a vehicle a thing a
shop can sell and a plugin can hand out: `/rp give mypack:hatchback`, and the
player parks it themselves.

### Driving one

How you drive depends on your server:

- **Paper 1.21.4 and up** — W and S to move, **A and D to steer**, space for
  the handbrake on land and water. A land vehicle with `jump: true` — a dirt
  bike, a skateboard — **jumps on space instead**, about a block and a half,
  and has no handbrake: S already brakes before it reverses. A water vehicle
  with `jump: true` pops off the surface the same way — a surfboard's air off
  the lip — once per press, from its float line.

  In the **air** the vertical controls are their own: **space climbs, S
  descends**, and S only reverses once you are back on the ground — there is
  nothing to reverse against in mid-air. Your look does not fly it, so you can
  look around while flying. An aircraft with a `takeoff-speed` will not leave
  the ground until it is going fast enough — see Aeroplanes and helicopters
  below.
- **Anything else** — right-click speeds up a notch, left-click slows down and
  then reverses, and you steer by **looking where you want to go**. An air
  vehicle climbs and dives with your look too, since there is no key to read.

**S is the brake before it is reverse.** Holding it at speed stops the vehicle
the way a brake does, and only engages reverse once it is actually stationary
— the same order a gearbox makes you use.

Sneak gets out, as it does for a boat. That is also why sneak is not the
brake: a driver braking would step off at speed.

`turn-speed` is the most the body will swing round, in degrees a second — a
low number is a lorry, a high one is a go-kart. How fast it actually turns at
any moment comes from the front wheels and the speed (see below); this is the
ceiling on that.

### How it drives

A vehicle has **grip**, and it can lose it. Steering turns the front wheels, the
wheels and the speed decide how hard the body comes round, and the tyres hold
the car to that line up to a limit. Corner harder than they can hold and the
car goes wide; hold it there and the tail gradually steps out. **Space is the
handbrake, and the handbrake is the drift button**: pull it in a corner and the
rear lets go, the car swings, and it comes back as you ease off and get back on
the power. A bike (anything under a block wide) leans into its corners; a car
rolls out of them.

The body follows the ground. Drive onto a kerb and the nose comes up, then the
whole car climbs and settles; drive along a slab road with two wheels on the
slabs and it leans. It dips under braking, squats when you floor it, and
bounces on landing. None of that moves the collision box — a tilted car still
fits where a level one does.

Hills are hills: slower going up, faster coming down, and a car left standing
on one holds where it is. A wall you clip at an angle scrapes you along it
rather than stopping you dead; a wall you hit square still does.

The driver sees their speed above the hotbar while moving, in km/h, yellow
while the tyres are sliding. `vehicles.speedometer: false` in `config.yml`
turns it off.

**A vehicle only turns while it is moving.** Steering is something you do to a
vehicle that is going somewhere: a parked one holds its heading however far
its driver turns their head, which is what lets you park it where you meant
to. Set `turn-in-place: true` for the things that genuinely pivot on the spot
— a tank, a hovercraft, an excavator — and they will turn at their full
`turn-speed` from a standstill. An aircraft that is off the ground always
steers, whatever this says, because a hovering helicopter has nothing to push
against and pointing itself is the whole of its steering.

### Making something bigger than three blocks

**A block model stops at three blocks on an axis.** The format bounds an
element to -16..32, so 48 units is the whole ceiling, and no amount of
redrawing geometry gets you a bus, a cargo ship or an airliner.

`scale` is the way past it. It grows the drawn model — 0.125 to 8, and 1
(the default) is the size it was built at — so a 3-block hull at `scale: 4`
stands twelve blocks long.

It moves the **seats and the particle emitters with the art**, because both
are positions quoted against the model: on a bus at `scale: 2` the driver is
twice as far forward and twice as high, which is where they were always
drawn relative to the bodywork.

It deliberately does **not** touch two things:

- **`hitbox`** — that is stated in blocks and is what players collide with
  and click. Scaling the art up and leaving the box alone is a legitimate
  thing to want (an airship you walk under), so growing it is a separate
  edit you make on purpose.
- **`speed`, `acceleration`, `weight`, `turn-speed`** — a bigger lorry is a
  bigger lorry, not a faster or heavier one. Tying handling to size would
  make one number quietly into two.

So a scaled vehicle usually wants its `hitbox` raised in the same breath, or
players will be clicking a box the size of the original around something four
times as big.

**Steering by look costs the driver their head**, and that is the real reason
to be on Paper for this. A player's body follows their head, so if steering is
looking then the driver swings round on every corner and cannot look at
anything except where they are going. With A and D their head is their own.

### Seats

**The order of the list is the order people are put in them.** The driver is
first — write it wherever you like and it is moved to the front — and the rest
are passenger 1, 2, 3 in exactly the order you wrote them. Somebody who
right-clicks the third seat gets the third seat. Reordering the list moves
where people sit, not just how the file reads.

`x` is to the vehicle's **right**, `z` is in **front** of it, both in blocks
and both negative the other way. They turn with the vehicle, so a bench seats
people along itself however it is parked — the same reading `place: seat:`
uses for furniture.

`yaw` turns the occupant, in degrees clockwise from the vehicle's own heading,
and it aims them **as they sit down** rather than holding them there. A
rear-facing bench writes `yaw: 180` and the passenger is turned round on
arrival; where they look after that is up to them.

`pose` is `sitting` (the default) or `standing`, and it decides what the
position **means**: a sitting seat's point is where the occupant's backside
goes, a standing one's is where their feet go. That is about a metre, so it is
worth getting right.

```yaml
    - {role: passenger, x: 0, y: 1.2, z: -1.4, pose: standing, yaw: 180, name: "Gunner"}
```

`hidden: true` draws **nobody** in the seat. Not "no pose" — no person: the
occupant is taken off every screen including their own, and wears no rig at
all. It is for a vehicle whose model already has its rider built into it, an
enclosed cockpit or a tank, where any body is a second person inside the
fuselage.

```yaml
    - {role: driver, y: 0.9, hidden: true}
```

Their own view is covered with an invisibility effect while they are in the
seat, so pressing F5 shows an empty cockpit too. If it shows a **see-through**
copy of them instead, that is your scoreboard: a team with
`canSeeFriendlyInvisibles` on — which is the default, and which most tab-list
and name-colour plugins set up — makes a client draw a friendly invisible as a
ghost rather than as nothing. The console says so once, naming the team. This
plugin will not turn that flag off for you; it is a PvP setting and it belongs
to whichever plugin owns the team.

Somebody who was **already** invisible when they sat down keeps their own
effect, and nothing is taken off them when they get out.

`/rp vehicles` lists how many hidden seats each vehicle has, which is the way
to check that a Studio sync actually carried the flag.

**A vehicle with no driver seat does not load at all**, and says so naming the
file. One that did would be a model claiming to be a vehicle with no way to
move, and there would be nothing to go on but it not working.

### Water vehicles need water

`medium: water` is a hull, and a hull out of water **crawls**: it does about a
seventh of its top speed, which is a shade slower than walking. It keeps its
full turning rate, and the driver is told once above their hotbar.

It is not stopped dead, deliberately. A boat that could not move at all on land
would beach itself on the first shore and be stuck there for ever — the driver
would have no way back to the water and nothing to do but log off. Crawling is
slow enough to be unmistakably wrong and fast enough to get you off the sand.

A `land` vehicle driven into deep water is unaffected by any of this. It falls
in and drives along the bottom, which is what a car does.

### Aeroplanes and helicopters

`medium: air` on its own is a **helicopter**: it lifts straight up from a
standstill, holds whatever height it is at, and never falls. That is what every
air vehicle here does unless you say otherwise, and for a flying saucer or a
hovering platform it is exactly right.

An **aeroplane** is the same medium with a `flight:` block:

```yaml
cessna:
  model: mypack:cessna
  medium: air
  speed: 30
  acceleration: 8
  flight:
    takeoff-speed: 12     # won't fly below this, blocks per second
    climb-rate: 7         # how fast space gains height
    dive-rate: 14         # how fast S loses it
    stall-sink: 6         # how fast it comes down when it's too slow
  seats:
    - {role: driver, y: 0.9}
```

**`takeoff-speed` is the whole of what makes it an aeroplane.** Below it the
climb key does nothing at all — the plane accelerates down the runway and that
is your takeoff run — and once you are up, dropping below it again is a stall:
the aircraft keeps the speed it has and sinks at `stall-sink` until you open
the throttle or reach the ground. Set it to `0` and you are back to a
helicopter.

**There is no takeoff *time*, and that is deliberate.** How long the run takes
is `takeoff-speed` and `acceleration` together — 12 blocks per second at 8
blocks per second squared is a second and a half, about nine blocks of runway.
A stated time would be a third number free to disagree with the other two, and
the only way to honour it would be to quietly override the acceleration you
set. Want a shorter run: raise the acceleration, or lower the takeoff speed.

**A dive keeps its momentum.** Pressing S in flight puts the nose down and
leaves the speed almost alone — an aircraft is not rolling on anything, so the
only thing slowing it is drag, which is small. This is different from letting
go on the ground, where a plane is a vehicle on wheels and stops like one.

Every number is blocks per second, and every one of them is ignored on a `land`
or `water` vehicle (the loader says so rather than pretending). `takeoff-speed`
above `speed` is an aircraft that can never leave the ground; that is a warning
in the console rather than a refusal, because the numbers are legal and it is
your vehicle.

### States

A vehicle is always doing something, and six words describe it:

| State | When |
|---|---|
| `airborne` | Off the ground with nothing holding it up — a jump, a fall, or an aircraft in flight |
| `reversing` | Travelling backwards |
| `moving` | Travelling forwards |
| `turning` | Swinging round faster than a nudge |
| `idle` | Stationary, or near enough |
| `submerged` | Its base is in water |

**It is in several of them at once.** A car coming down off a kerb mid-corner
is `moving`, `turning` and `airborne` together, and a boat under way is
`moving` and `submerged`. That is why `animations:` and `particles:` read the
list differently — see each below.

`turning` is measured against how fast the body **actually** came round, not
against how hard you are steering. A vehicle already pointing where you are
looking is not turning, and one held against a wall is not either.

### Animations

`animations:` maps a state to one of the model's own animations, authored the
usual way — bones and keyframes on the model itself. Nothing new is defined
here; this only says when each one plays.

**One animation at a time**, because a rig has one clock. The state is chosen
by the order in the table above, top first: an airborne car plays its jump
animation over its drive cycle.

**A state you leave out falls through to the next one down.** That is the part
worth internalising, because it is what makes a short answer a complete one:

```yaml
  animations:
    idle: parked
    moving: drive
```

is a finished vehicle. Cornering plays `drive`, because `turning` is unset and
falls through to `moving`. Going over a bump plays `drive` too. You only write
`turning:` if you have actually drawn a leaning animation, and you never have
to think about `submerged` at all.

**`idle` is where the fall-through stops.** Every state above it names
something the vehicle is *doing*, so a blank one carrying on with the next is
right. `idle` says it is doing nothing, and there is nothing quieter to fall
through to — so **leave `idle` blank and a vehicle standing still plays
nothing**, which is what makes clearing it a way to switch an animation off.

**A boat is always `submerged`**, which is exactly why that state is the last
one and sits *below* `idle`. A state that is permanently true describes
nothing, and while it sat above `idle` it took every quiet moment a boat had: a
rowing cycle mapped to `submerged` rowed at the mooring, and clearing `idle`
could not stop it. So a moored boat is `idle`, not `submerged` — put its
resting animation on `idle`, and reach for `submerged` only for a vehicle that
does something particular in water while the states above it are blank.

Whatever a state names **loops for as long as that state holds**, whether or
not the animation itself is authored as a loop. A state is a condition rather
than an event, so a rowing cycle written as a one-shot still rows continuously
while the boat is moving.

**Who may ride it** is `permission:`, and absent means anybody:

```yaml
  permission: mypack.ride.hovercar
```

Checked when somebody gets IN, which is the only moment that matters: a
vehicle is a thing standing in the world that anyone can walk up to and
right-click, so gating who was given the ITEM controls nothing once one is
parked in a public square. It is the same key, with the same meaning, as an
item's.

**How freely it rolls** is `coast:`, in blocks a second squared, and the
default is a car's:

```yaml
  coast: 0.15
```

A vehicle with nobody on the throttle slows at whatever the engine thinks a
car does, which is right for everything with an engine in it and wrong for
everything without one. Small numbers roll far - 0.15 is a skateboard on
smooth concrete, 0.6 a bicycle, 2 a shopping trolley with a bad wheel. It
replaces a floor rather than the whole of the drag, so a vehicle whose own
acceleration implies more still gets that.

**Landing badly** can throw the rider off, and is off unless you ask:

```yaml
  bail:
    from: 50          # degrees off the way it was travelling
    to: 130
    min-speed: 3.0    # slower than this is a stumble, not a fall
    damage: 1.0       # half a heart
```

`bail: true` on its own takes those numbers. The window has a far edge on
purpose: landing straight BACKWARDS is riding away fakie, which is a trick
rather than a crash, so only the sideways part of the range throws anybody.
A plugin can veto any individual one - see `VehicleBailEvent` in `API.md` -
which is how a server switch for it gets written without editing a pack.

**A vehicle that is WORN** rather than ridden hides its model while anybody is
in it:

```yaml
  worn: true
```

A pair of rollerskates, a jetpack, a horse costume: a vehicle whose art
belongs on the occupant's body rather than under it. The seat's emotes carry
the art instead - as props attached to the rig's bones, see "Emotes" below -
so it moves with the legs exactly the way the legs do, and the vehicle's own
model would only ever be a second copy standing on the floor. Empty, it is
drawn as normal: a pair of skates left in the road is a thing you can see and
step into. Off by default, because it is a decision about what the vehicle is.

**Riding a wall** is off unless you ask, for the same reason bailing is:

```yaml
  wall-ride: true
```

Jump at a wall fast enough and shallow enough, holding the jump key, and the
vehicle sticks to it - body and rider rolled right over onto it, sinking
gently, steering up and down it for as long as the key is held and there is
wall to ride. Letting go kicks you off. There is nothing to start or stop from
a command or a file: a ride begins on its own when the conditions are met and
ends when the speed or the wall does.

It belongs to the things that are RIDDEN rather than driven. A car that
climbed the side of a building the first time somebody clipped a wall would be
a bug, which is why this is a key rather than a default. A plugin can ask
whether a vehicle is riding one right now (`Vehicle.wallRiding()` in
`API.md`), which is what lets it pose the rider for it - the engine does the
physics and has no opinion about what the person on top should look like.

**A wheel turns because the vehicle MOVED, not because time passed.** By
default a cycle plays at the rate it was authored at, whatever the vehicle is
doing - which is a skateboard whose wheels spin at one speed from a crawl to a
tuck, and a milk float whose wheels race while it creeps. Say so and the
playhead is driven by the ground speed instead:

```yaml
  animation-follows-speed: true
```

At the vehicle's top speed the cycle runs at its authored rate; at half speed,
half of it; standing still it stops on the frame it reached. The phase carries
across a change of state, so braking out of `moving` into `reversing` picks the
wheels up where they were.

It is off by default because an animation on `moving` is not always a wheel - a
bobbing suspension, a flapping flag or an exhaust puff is authored at a rate
somebody chose, and slowing those down with the vehicle is not obviously right.
Turn it on for anything whose animation IS the motion.

When both `moving` and `reversing` are mapped, RP Engine keeps their cycle
phase across a direction change instead of restarting the other animation at
frame zero. Author them as the same cycle in opposite directions (for example,
a wheel turning 0→360 in `moving` and 360→0 in `reversing`); the engine
mirrors their normalised playheads, so different animation lengths are fine and
the wheel stays at the angle already on screen.

A vehicle whose model has no animated parts can carry this map and it simply
never plays anything — the model is drawn as one still piece. That is a
half-finished vehicle rather than an error.

### What the occupant does

A seat can say what its OCCUPANT's body is doing, per state:

```yaml
  seats:
    - role: driver
      y: 0.6
      animations:
        idle: mypack:lean-on-door
        moving: mypack:steering
```

**These are emote ids, not a new kind of animation.** The engine already has a
rig that animates a player's body and an editor that authors one by hand, so a
driver hauling a wheel round is an emote like any other — you make it the way
you make any emote, and this only says when it is worn.

It is worn the way a movement set is: the rig follows its wearer, there is no
anchor, and getting out puts their own body back. The difference is only who
decides — a movement set reads your legs, and this reads the vehicle.

**A state you leave out is the seat's own stance, NOT a fall-through to another
state.** No fall-through, deliberately: it would leave a driver hauling an
imaginary wheel round while the car sat still. What a missing state gets
instead is the engine's own stance for the seat's `pose` — legs out at the hip
for `sitting`, the rig at rest for `standing` — so an occupant is drawn sitting
IN the thing rather than standing up to their waist in the hull, on a pack that
authored no emotes at all.

**A named emote is worn OVER the seat's stance, not instead of it.** Bone by
bone: one that only moves the arms keeps its occupant's legs seated, and one
that deliberately swings a leg out of a kayak keeps its own. Without that,
naming any emote at all stood the rider up — the emote's animators replaced the
seated legs, they fell back to rest, and a driver hauled the wheel round
standing.

That still needs the pack to carry a baked rig for that player; without one
they ride as themselves and the console says so once. Set
`vehicles.seat-rig: false` in `config.yml` for a server that would rather see
ordinary players — it costs a rig's worth of entities per occupant. A seat that
NAMES an emote is unaffected by any of this.

So the state is resolved once, as the highest one in the table that holds, and
looked up.

**`submerged` on a seat never fires at all**, and it is worth being blunt about
because the bodywork's table above is different. Every vehicle is always
`moving`, `reversing` or `idle` — one of the three, every tick, by construction
— and all three come above `submerged`. The bodywork can still reach it,
because a blank state there falls through to the next one down; a seat cannot,
because a seat has no fall-through. **A moored boat is `idle`**: put a rower's
resting pose there and their rowing cycle on `moving`.

If the occupant is already mid-emote of their own when they get in, theirs
wins and the seat dresses nobody — a vehicle should not interrupt somebody's
handshake.

**When a seat cannot dress somebody it says so in the console**, naming the
vehicle, the player and which of the reasons it was: the pack carries no rig
for that player, the emote id no longer exists, they are mid-emote of their
own. Every one of those looks identical from the seat — nothing happens — so
without the line there is nothing to go on.

It is the console rather than the rider's chat because every remedy belongs to
whoever owns the pack, and because it fires on a state change: a boat crossing
in and out of a mapping while somebody manoeuvres it would say the same
sentence at them over and over. Once per player per reason.

**A rig taken off by something else comes back.** `/emote stop`, a death, or
another plugin ending the session leaves the rider as themselves; the seat
notices on its next tick and puts it on again.

### Sounds

`sounds:` maps a state to one of your own sounds, by id — an engine note, the
wash of a hull, the rotor of a helicopter.

```yaml
  sounds:
    idle: mypack:tickover
    moving: mypack:engine
```

**It is read exactly like `animations:`**: one sound at a time, chosen by the
same order, and a state you leave out falls through to the next one down. So a
vehicle that names only `moving` keeps its engine running through a corner and
over a bump, and goes quiet when it stops. That is the opposite of the
`particles:` rule below, and for the same reason animations have it: an engine
has one note the way a rig has one clock, and two playing over each other is a
vehicle that sounds broken rather than busy.

**A sound loops for as long as the state holds, and it needs a `length:` to do
it.** Minecraft has no looping sound — a sound event is a one-shot — so what
happens here is that the engine plays your file again the moment it ends, which
it can only do if the sound definition says how long it runs. Give the sound a
`length:` (see Sounds, above) and it loops seamlessly; leave it out and the
sound plays **once**, when the vehicle enters that state. That is deliberate:
a two-second engine looped on a guessed half-second is four engines.

The sound is played **from the vehicle's chassis**, so everybody nearby hears
it, it fades with distance, and its position follows the vehicle throughout
each repeat while it drives and turns.

A sound that does not exist on this server is silence, not an error: it may
belong to a pack that has not loaded, and a vehicle is worth more than a
refusal.

### Capes

`capes: false` stops a rider's cape being drawn while they are in this vehicle.
The default is `true`, because a cape is somebody's own and taking it off them
is the surprising direction.

It is worth setting for anything a rider sits **inside** — a car's cabin, an
aeroplane's fuselage, a tank. A cape hangs off the back of the rider's rig, and
in a cabin it hangs through the bodywork, which is not something you can fix
from the model. An open cart or a horse-drawn trap wants to keep it.

### The speedometer

`speedometer: false` stops this vehicle writing its driver's speed above their
hotbar. The default is `true`, which is what every vehicle did before the key
existed.

A car has a dashboard. A skateboard, a horse, a hang glider and a shopping
trolley do not, and a number counting up in the corner of the screen turns a
line you were riding into a stat you were watching. **Nothing takes its
place** — the readout is not moved to the chat, a boss bar or a title, it is
simply not written, and the action bar is left for whatever else wants it.

`vehicles.speedometer: false` in `config.yml` is the same switch for the whole
server at once. Either one off is off.

It takes away the CAPE and nothing else: the rider is still there, still posed,
still visible. `hidden: true` on a seat is the switch that removes the person.

### Particles

`particles:` is a list of emitters. Each one is a spot on the bodywork that
throws a particle effect while the vehicle is doing something.

```yaml
  particles:
    - effect: smoke           # a particle name
      states: [moving, reversing]
      x: 0                    # the same frame as a seat: right, up, forward
      y: 0.3
      z: -1.5
      count: 2                # particles per burst, at most 16
      interval: 2             # ticks between bursts; 1 is every tick
      spread: 0.1             # how far they scatter, in blocks
      speed: 0.02             # how fast they drift away
      enabled: true           # the default
    - effect: dust            # the one effect that takes a colour
      states: [idle]
      y: 1.2
      color: "#ff8800"
      size: 1
```

**An emitter fires if the vehicle is in ANY of its states**, which is the
opposite reading from `animations:` and is what makes one exhaust plume one
emitter rather than two. An emitter that lists no states never fires, and says
so at load rather than being guessed into meaning "always" — guessing would
turn a typo in the one state you wrote into a particle storm you did not ask
for.

`x`, `y` and `z` are **the same frame as a seat**: right, above the base, and
in front, in blocks, turning with the body. An exhaust pipe placed behind the
model stays behind it however the vehicle is parked.

`enabled: false` keeps the emitter and its settings and stops it firing, which
is what you want while tuning the other three.

**Particle names were renamed in 1.20.5** — `SMOKE_NORMAL` became `SMOKE`,
`REDSTONE` became `DUST`, and about twenty others. Write either; the engine
tries every spelling and uses whichever one your server has. What it cannot do
is invent one: a name no version has, or one that needs a block or an item to
draw itself with (`block`, `item`, `falling_dust`), is skipped with a line in
the console saying which and why. It says it once, not twenty times a second.

Keep `count` and `interval` modest. Every particle is drawn by every player in
range, so the cost of a generous emitter lands on other people's frame rates
rather than on the server that authored it.

### What a vehicle is, underneath

An invisible chassis that stays where you left it, wearing your model, with an
invisible seat for each entry in the list. It is **parked** rather than
conjured: it is there with nobody in it, it survives a restart, and only the
chassis is saved — the seats and the model are rebuilt whenever its chunk
loads.

Two honest limits:

- **It stops at a wall rather than sliding along it.** Driving into a building
  brings you to a halt. Land vehicles step up one block, like a player.
- **The hitbox is a shape, not a bounding box.** It decides what you click to
  get in, which blocks stop the vehicle, and — see below — where another
  vehicle hits this one. What it is NOT is a box the GAME knows about: a plugin
  cannot give an entity a bounding box of its own size, so a vehicle does not
  physically block an arrow, a minecart, or somebody walking into it.
- **A vehicle passes through players and shoves mobs.** Set
  `vehicles.push-players` in `config.yml` if you want it to shove people too;
  it is off because cars nudging each other's drivers about in a car park, and
  a passenger being flung as they get out, are both worse than driving through
  somebody. Nothing invisible ever blocks anybody — the seats have no collision
  at all.
- **Two vehicles DO collide with each other**, and that one is not a limit —
  it is worked out from the hitboxes above and `weight:`. What each comes away
  with depends on how fast they were closing, which way round they met, how far
  off centre the hit landed and what the two of them weigh, so a lorry shunts a
  hatchback out of the way and hardly slows, a clip on the corner spins you and
  a square rear-ending does not, and at a crawl it becomes pushing rather than
  bumping. `vehicles.collide: false` in `config.yml` turns it off for a server
  that would rather they passed through each other.
- **Speed above about 20 blocks a second stops looking right for passengers.**
  Their position is broadcast twenty times a second and their own client fills
  in the gaps, so past a point they lag the vehicle however fast the server
  is. The driver holds up better than the passengers, because steering is
  their own camera. `speed:` allows 60 because a hovercraft skimming a lake is
  not a car; treat anything above 20 as needing a look before you ship it.
- **Passengers are as smooth as vanilla riding.** The driver's view is the
  responsive one; everyone else sees the vehicle where the server last said it
  was. Nothing on this side changes that.

**If a seat is consistently a bit off from where the editor drew it**, that is
`vehicles.seat-offset` (up) and `vehicles.seat-forward` (along) in
`config.yml`, and `/rp reload`. Where a rider ends up is the chassis, plus the
seat, plus the game's own rule for placing a passenger — and only the first two
are ours. That last one cannot be read by a plugin and has changed between
versions before, so the two nudges close it on your server rather than waiting
for a release.

One server setting stops all of this working: `armor-stands-tick: false` in
Paper's config. A stand that does not tick never moves, so every vehicle sits
still. The engine notices and says so in the console rather than leaving you
to guess.

## Emotes

An emote is a player animation: the body cut into bones — head, body, arms,
legs, forearms and shins — with keyframes on each. `emotes/` holds them, one
file holding as many as you like, keyed by name:

```json
{
  "wave": {
    "name": "Wave",
    "length": 1.2,
    "loop": false,
    "animators": {
      "rightArm": { "rotation": [
        { "time": 0,   "value": [0, 0, 0] },
        { "time": 0.3, "value": [-160, 0, -20], "interpolation": "smooth" },
        { "time": 1.2, "value": [0, 0, 0] }
      ] }
    }
  }
}
```

**This is exactly what Studio's emote editor produces**, and the intended way
to write one is to make it there and paste the entry — the editor's export is
this JSON, and a Studio push carries the same shape. Hand-written is fine too,
and YAML works as well as JSON for anybody who prefers it. The bones are
`head`, `body`, `rightArm`, `leftArm`, `rightLeg`, `leftLeg`, `rightForearm`,
`leftForearm`, `rightShin`, `leftShin`; `root` moves the whole figure about the
hip. Channels are `rotation` (degrees), `position` (px) and `scale`. Times are
seconds; `length` is how long it runs and `loop` whether it repeats.

**Emote names are shared across the whole server**, not per pack. `/emote wave`,
a vehicle seat's `animations:` and a plugin all name an emote by its bare
name, so two packs both defining `wave` is an error rather than two emotes —
prefix yours (`mypack_wave`) if you ship a pack other people will install
beside theirs.

### Carrying a model

An emote can carry models - a chair to sit on, a sword to swing, a pair of
skates on the feet - as `props`, beside `animators`:

```json
{
  "rollerskates_glide": {
    "length": 2.0, "loop": true,
    "animators": { "...": "..." },
    "props": [
      { "id": "right", "modelId": "rollerskates:skate_right", "attach": "rightShin",
        "offset": [2, -16, 0], "scale": 1,
        "animator": { "rotation": [ { "time": 0, "value": [0, 0, 0] } ] } }
    ]
  }
}
```

`attach` is a bone (the same names as above), `root` for the whole body, or
`none` for a model that stands where it was put and does not follow the
player at all. `modelId` names what is drawn: **one of your own items**
(`namespace:path`, whatever wears the model you want) or, for a pushed pack,
a model's carrier string. `offset` is where the model's centre sits, in px,
measured from the rig's origin - which is a block above the feet, so a
skate whose sole is on the ground under the right foot is at `[2, -16, 0]` -
and moved by the bone it rides. `animator` is the prop's own motion on top of
that, the same channels as a bone's. `animation` names one of the prop
model's OWN animations to play while it is carried - a lantern that swings,
wheels that turn - on the emote's clock, or on a clock a plugin drives
(`Emotes.seekProp` in API.md). This is the shape Studio's emote editor
writes; the only difference for a hand-written one is that a model is reached
through an item, because that is how an authored pack addresses its models.

A prop rides its bone the way the bone's own geometry does, interpolated on
the same clock, which is what makes it the way to attach anything to a body.
A vehicle with `worn: true` (above) is the other half of that: its own model
is put away while somebody is in it, and the seat's emotes wear the art.

### The rig it plays on

An emote is drawn on a **rig**: a copy of the player's own skin, cut into
bones, baked into the resource pack. The engine bakes one for every player
who has joined this server (their skin is kept under `plugins/RPEngine/skins/`
the first time they do) and a shared default figure for everybody else, on
every `/rp reload` and restart. Somebody joining for the first time wears the
default until the next build bakes theirs; the console says so once.

That needs Minecraft 1.21.4 or newer. On an older server the emotes still
load and pushed rigs still work, but nothing is baked here and a hand-authored
emote plays on nobody. `emotes.rigs: false` in `config.yml` turns baking off.

## Custom blocks

A block you can place, mine and stand on — an ore, a machine, a crate.

```yaml
# blocks/ores.yml
ruby_ore:
  base: note_block     # note_block | mushroom_stem
  model: ruby_ore      # assets/models/ruby_ore.bbmodel
  hardness: 3.0        # stone is 1.5
  tool: pickaxe        # what has to be held for the drop
  drop: mypack:ruby    # default: itself
  sound: minecraft:block.stone.place
```

**A block is an item too.** `/rp give mypack:ruby_ore` hands you the thing that
places it; nothing declares that item, because a block you cannot obtain is not
a block anybody can use.

`hardness` and `tool` are real: the engine breaks the block itself, with the
ten-stage crack overlay, because hardness belongs to a block's TYPE in
Minecraft and every custom block is a note block underneath. The wrong tool
still breaks it and gives nothing, the way stone and a shovel do.

**A custom block cannot give off light, and cannot change its sound.** Both
belong to the block's type rather than its state. `sound:` plays something of
yours *over* the base block's own, which is the honest half of it; for light,
use a [placed model](#placing-a-model), which puts a real light block in its
anchor.

### What a custom block really is

The game has no way to add a block, so this is **a real vanilla block in a
state nothing else uses, wearing your model**. Every plugin doing this uses the
same trick, and it brings the same three costs — worth reading before building
a hundred of them.

**The pool is finite, and smaller than you would guess.** A note block gives
**49** blocks, a mushroom stem **63**. Not eight hundred: a note block's
instrument is recomputed from whatever is underneath it, and nothing can stop
that — so the instrument cannot be part of what identifies a block, and only
the note and the powered flag are. Every instrument for a given note points at
the same model, which is what makes the game changing it invisible.

`/rp blocks` says how many are left.

**The mapping has to be kept.** A block in your world is a note block in a
particular state, and `blocks.json` in the plugin folder is what says which id
that was. **Back it up.** Lose it and every custom block on the server becomes
a different block — not missing, not broken, but visibly the wrong thing, and
no reload repairs it. Ids are appended in the order they are first seen and
never renumbered, so adding a pack cannot disturb one already placed.

**Vanilla note blocks are hijacked.** On a server with note-block-based custom
blocks, the pack has repainted every state, so a plain note block still looks
right but a custom one does not play a note. `mushroom_stem` has none of this
— nothing in vanilla ever changes one — which makes it the better base for
anything that does not need the bigger pool.

**A placed model is still the right answer for furniture.** It has no pool, no
limit, no mapping to lose, and any shape you like. A custom block is for the
things a display entity cannot be: something you mine, something that holds
you up, something a piston should refuse to move.

## Liquids

Minecraft has two fluids and a server cannot add a third. A custom liquid is
**real water or lava with your rules applied to whoever is in it** — it swims,
flows, floats boats and is seen as water by every other plugin, because it is
water. What tells acid from ocean is a volume somebody marked out, not the
blocks in it.

```yaml
acid:
  base: water          # water | lava
  color: "#3FBF4A"     # optional; quote it, or YAML reads # as a comment
  effect: POISON
  amplifier: 1
  damage: 1.0          # per second
  fireproof: false
```

`color:` takes `"#3FBF4A"`, `0x3FBF4A`, `3FBF4A` or one of the sixteen dye
names (`RED`, `LIGHT_BLUE`, …). Leave it out and the liquid is whatever colour
the water there already was.

### Two ways to make a pool

Mark out water that is already there: `/rp liquid corner`, walk to the opposite
corner, `/rp liquid fill mypack:acid`. Or build the pond in the first place with
a bucket:

```yaml
acid_bucket:
  material: BUCKET
  liquid: mypack:acid
```

Right-clicking with that puts one source block down and makes the place count
as that liquid. A block placed against an existing pool of the same liquid
**joins it** rather than starting a second, so a pond built click by click
carries one rule, not fifty.

Pools are saved to `liquids.json` beside the other stores, in the order they
were made, and the first one containing a point wins — so a small pool drawn
inside a big one only counts if it was drawn first.

Boxes rather than a record of every block: a lake is thousands of blocks that
change shape as it flows, and a per-block record would be wrong within a second
of somebody breaking a bank.

### What a colour costs

The game tints water **by biome**, which is the only knob it has for this and
brings three things with it:

- **A colour needs a restart.** The engine writes one biome per tinted liquid
  into a datapack in your world folder (`datapacks/rpengine_liquids`) on every
  load, and biomes are registered when the server starts. Until you restart,
  the pool works and is the wrong colour.
- **It lands on a 4×4×4 grid.** Painting one block paints its neighbours, so a
  tinted pool has a rim of tinted water around it.
- **It fades at the edges.** The client blends biome colours over several
  blocks, so a pool under about 8 blocks across never reaches its full colour.
  A player with biome blend turned up sees less colour again.
- **It is multiplied by the water texture**, which is a dark blue-grey, so a
  saturated hex still lands muted. This is the ceiling of the mechanism rather
  than something to tune: ItemsAdder tints the same texture the same way and
  has the same limit. A pack CAN ship a paler `water_still` through
  `overrides/` and get vivid colours — at the price of every ocean on the
  server going pale too, which is why the engine will not do it for you.

Clearing a pool paints it back to the biome that was there when it was made —
one biome for the whole box, which is all `liquids.json` records.

## An ItemsAdder pack

**Drop it in and it loads.** A folder from somebody's ItemsAdder
`contents/` — configs at the root, `textures/` and `models/` beside them, no
`pack.yml` — is read where it lies. A file is recognised by its own shape (an
`info:` block beside `items:` or `font_images:`), so there is nothing to turn
on and no conversion step, and one of their files works inside a pack of yours
just as well.

What comes across:

| Theirs | Ours |
|---|---|
| `items.<id>` | an item |
| `resource.material` · `textures` · `model_path` | `material` · `texture` · `model` |
| `display_name` or `name` · `lore` · `permission` | the same |
| `enchants` · `attribute_modifiers.mainhand` · `durability` · `max_stack_size` | `enchantments` · `attributes` · `durability` · `stack` |
| `specific_properties.armor.slot` | `armor` |
| `behaviours.liquid_bucket` | `liquid` |
| `behaviours.furniture` | `place:`, with its light, solidity and seat |
| `font_images.<id>` | an icon |
| `enabled: false` | skipped, as theirs is |

What does not, each of them a warning naming the id rather than a silence:
**custom blocks**, which are not a feature here and are not going to be; their
**entities** and **recipes**, which are a different feature rather than a
different spelling and want writing as `entities/` and `recipes/`; and the
parts of an item that are their plugin's own behaviour rather than a property
of the item — `events`, `drop`, `item_flags`.

**The folder name is still the namespace.** A file whose `info.namespace` says
something else is loaded under the folder's name and warns, because the folder
is what this engine claimed and ids written elsewhere have to resolve.

## A Model Engine blueprints folder

**Drop it in and it loads.** A `blueprints/` folder of `.bbmodel` files —
Model Engine's layout — gives one item per blueprint, named after the file, that
wears it:

```
mypack/
  pack.yml
  blueprints/
    golem.bbmodel        ->  mypack:golem
    bosses/dragon.bbmodel ->  mypack:dragon
```

There is nothing to convert, because their content format IS the save files,
and **the bone names already mean the same thing here** — `h_`, `hi_`, `b_`,
`ob_`, `p_seat`, `mount` and `tag_name` are Model Engine's, adopted on purpose
so a rig somebody already has works without being re-authored.

The generated item is a plain one on paper. An `items/` definition under the
same id beats it, which is how a blueprint gains a material, a name, a
`place:` block or anything else.

## pack.yml

```yaml
name: My Pack              # display only, may contain anything
author: Steve
version: 1.0.0
bundles: [main]            # which bundles this namespace ships in
enabled: true
```

Every field is optional. With no `bundles`, the namespace ships in `main`.
With `enabled: false` the whole folder is skipped, which is the supported way
to park a pack without deleting it.

Bundle names follow the same character rules as a namespace.

`pack.yml` is the one file whose absence is an error: a folder without it is
not a content pack, and treating it as one is how a stray directory becomes a
namespace nobody meant to claim.

## A definition file

Top-level keys are **ID paths**. The kind comes from the folder, so nesting
under a `items:` key would be saying the same thing twice.

`items/gems.yml`:

```yaml
ruby:
  material: DIAMOND
  name: "Ruby"

sapphire:
  material: DIAMOND
  name: "Sapphire"
```

That declares `mypack:ruby` and `mypack:sapphire`.

Slashes are allowed in an ID path, and they are how you get a hierarchy:

```yaml
weapons/ruby_sword:
  material: DIAMOND_SWORD
```

is `mypack:weapons/ruby_sword`, regardless of which file or subfolder it was
written in.

### YAML or TOML

A definition file may be `.yml`, `.yaml` or `.toml`, and both spellings can sit
in one folder. They produce exactly the same thing; nothing downstream knows
which one it came from.

```toml
[ruby]
material = "DIAMOND"
name = "&cRuby"
lore = ["Shiny."]

[chair]
material = "PAPER"
model = "chair"

[chair.place]
facing = "cardinal"
seat = 0.5
```

**An ID with a `/` or a `.` in it has to be quoted** — `["weapons/sword"]` —
because both mean something to TOML. That is the one thing that catches people,
and the error message says so when it happens.

**One file may declare many, and many files may declare into one prefix.**
The file name means nothing. This is deliberate: a format that ties IDs to
file names forces a choice between one file per item and one enormous file
per category, and both are unpleasant at the sizes real packs reach.

The body of a definition is whatever the layer for that kind understands, and
is not the loader's business. The loader validates the ID and the kind, and
hands the body along untouched.

## Sounds

```yaml
# sounds/ambient.yml
chime:
  file: bells/chime    # assets/sounds/bells/chime.ogg. Defaults to the id.
  category: ambient    # which volume slider it answers to
  subtitle: "A chime rings"
  volume: 1.0
  pitch: 1.0
  stream: false        # true for anything long
  length: 2.4          # seconds. Only needed by something that loops it
```

Play it with `/rp sound mypack:chime`, or from another plugin through the
`Sounds` API. The ID **is** the sound event name, so
`playSound(loc, "mypack:chime", ...)` works from anywhere without asking us.

**Ogg Vorbis only.** Minecraft plays nothing else, and an mp3 renamed to `.ogg`
is silence with nothing in game to say why. Missing audio is a build error that
names the path.

`category` decides which volume slider it obeys: `master`, `music`, `record`,
`weather`, `block`, `hostile`, `neutral`, `player`, `ambient`, `voice`. Getting
it wrong means somebody who turned music down still hears you.

**Write a subtitle.** It becomes a language entry automatically, and without
one your sound does not exist for anybody playing with subtitles instead of
audio — which is more people than most server owners expect.

`stream: true` for anything long. A file loaded whole keeps its decompressed
audio in memory for the session.

**`length` is how long the file runs, in seconds**, and it exists for one
reason: Minecraft has no looping sound. A sound event is a one-shot, so
anything that plays continuously is the server re-playing a short file on a
timer — a vehicle's engine note, today — and the only way to do that without a
gap or an overlap is to know how long the file is. Nothing here can measure
that for you, so leave it out and a vehicle plays the sound once when it enters
that state; write it (any audio player will tell you) and the sound loops for
as long as the state lasts. Nothing else reads it.

## Icons

An icon is a picture that behaves like a letter.

```yaml
# fonts/icons.yml
sword:
  file: sword     # assets/textures/font/sword.png. Defaults to the id.
  height: 10      # 8 is the height of a capital letter
  ascent: 8       # how far above the baseline. Never more than height.
```

Put one into any piece of text with `:namespace:id:`:

```
/rp say Cheers :mypack:beer:
```

The engine's `Icons.format(text)` does that substitution, so a plugin can run
config text through it and let server owners write icons into their own
messages. An ID that names nothing is **left exactly as written** rather than
removed, because text that silently loses a chunk of itself is much harder to
diagnose than text that still says `:mypack:sword:`.

**Icons go into the game's default font**, so one renders in chat, an item name
typed in an anvil, a sign, a scoreboard — anywhere the game draws text. A font
of our own would only work where a plugin can set the font of a component,
which is far less of the game than it sounds.

That makes `assets/minecraft/font/default.json` a file every pack would want to
write, so **nothing may**: it is generated once per bundle from every
namespace's icons at once. A pack shipping its own copy through `overrides/`
would delete every icon in the bundle, so that is refused with an error rather
than silently obeyed.

**Never store the character, store the ID.** Codepoints are handed out in ID
order, so adding an icon whose ID sorts earlier shifts the ones after it. That
is invisible to anything resolving the ID as it writes the text, and wrong for
anything that saved the character — a glyph written into a sign or a book is a
different picture after the next reload. Stable codepoints would need a file
mapping ID to number that must never be lost or reordered, which is exactly the
problem the item scheme was designed to delete.

## Screens and HUDs

A custom GUI and a HUD overlay are the same trick as an icon, scaled up: the
picture is one enormous glyph, drawn into text the game already renders, with
negative space in front to slide it into place.

```yaml
# screens/menus.yml
shop:
  file: shop           # assets/textures/gui/shop.png
  container: chest_9x6 # which real container it opens as
  # height, ascent and offset are worked out from the container. State them
  # only for art that is not laid out the usual way.
```

**Draw your sheet 256×256 with the vanilla window art centred on it**, and say
which container it was drawn for. That is the whole contract, and everything
else follows from it: a five-row chest window is 176×204, so it is inset 40
across and 26 down, so the backdrop needs an ascent of 39 and a shift of 48.
Nothing to state and nothing to tune.

**`container:` has to be the one the art was drawn for.** It is not just how
many slots the screen has — it is what positions the picture. Declaring a
five-row sheet as a six-row chest puts the whole screen 9 pixels out.

**Draw outside the window freely.** A title plate above it, a border around it,
a character leaning on it: the placement keys off the window region, not off
where your ink happens to reach, so decoration outside the window does not move
the window.

**The word "Inventory" disappears** from every container screen while a bundle
holds any custom GUI. The client draws that label itself and draws it *after*
the title, so a backdrop cannot cover it, and the open-screen packet carries
nothing that would move it. Blanking `container.inventory` in a language file is
the only lever, and it is global: the player's own inventory loses the word too.
That is a property of the key rather than a choice.

```yaml
# huds/overlays.yml
mana:
  file: mana
  slot: action_bar     # action_bar | boss_bar
  height: 64
  ascent: 32
  offset: 0
```

`/rp screen mypack:shop` and `/rp hud mypack:mana` open and draw them.

**A GUI is a real container wearing a picture.** The rows and the slots are
vanilla's; only the backdrop is yours, so `container:` has to name one the game
actually has. Anything else is refused at load with the list in the message,
rather than becoming a command that silently does nothing.

**Icons, screens and HUDs share one number line.** They are one mechanism with
three names, so allocating per kind would hand the same codepoint to an icon
and a screen — and that failure is a chat message drawing a full-screen GUI
across somebody's view.

The `offset:` is negative space, built from powers of two, so any shift is at
most nine characters rather than one per pixel. Without it a backdrop starts
where the title text starts, which is not where the window is.

## Recipes

```yaml
# recipes/gems.yml
ruby_cube:
  type: shaped              # the default
  result: mypack:ruby_cube  # a content id, or a vanilla material
  amount: 1
  pattern:
    - "RRR"
    - "RRR"
    - "RRR"
  keys:
    R: mypack:ruby          # a space in the pattern means an empty slot

ruby_from_cube:
  type: shapeless
  result: mypack:ruby
  amount: 9
  ingredients: [mypack:ruby_cube]

sapphire_from_lapis:
  type: smelting            # blasting | smoking | campfire | stonecutting
  result: mypack:sapphire
  ingredient: LAPIS_LAZULI
  experience: 0.5
  time: 100                 # ticks; defaults to vanilla's own per type
```

**Ingredients and results can be either.** A content ID matches that exact item
— an ordinary diamond will not satisfy a recipe calling for `mypack:ruby`, even
though a ruby is a diamond underneath. A vanilla material name matches loosely,
the way a vanilla ingredient should.

**A recipe ID is not a content ID.** It lives outside the ID space, so a recipe
may be called `ruby_cube` while an item is called `ruby_cube` — which is the
first thing anybody writes. Two recipes still cannot share a name.

Recipes are removed from the server when the plugin unloads and before every
reload. A recipe deleted from a content pack stops working immediately, rather
than lingering until a restart.

## Errors

Two levels, because they have different blast radii:

- A bad **definition** is skipped, with an error naming the file and the key.
  The rest of the namespace still loads. One typo in one item should not cost
  a server everything else in the pack.
- A bad **namespace** aborts that folder whole: no `pack.yml`, a folder name
  that is not a legal namespace, a `pack.yml` that is not a YAML map, or a
  namespace already claimed by another source. Nothing from it is registered.

Other packs are unaffected either way. A failing pack never stops a working
one loading.

**Errors are collected, not thrown.** A load returns a report of everything
that went wrong across every pack, so a server owner fixes ten problems in one
pass rather than restarting ten times.

## Reserved

`minecraft` and `realms` cannot be namespaces. Content there would write an
`item_model` that resolves to vanilla assets and fail as a missing texture,
which is untraceable back to here.
