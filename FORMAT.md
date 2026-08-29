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
`models/` — a placed model is a `place:` block on the item, below. No
`blocks/`: custom blocks are not a feature here. No `emotes/` yet: emote
keyframes arrive from a Studio push, and the day they can be hand-written is
the day the list above gains a line. Any other folder is warned about by name,
which is what catches `item/` and `Sounds/`.

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
  one that moves. A quarter of a second covers most things.
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

This is vanilla's own equipment path, which arrived in 1.21.4 and is the reason
the version floor is where it is. It replaces the old tricks outright — dyed
leather spends a colour that can then never be used for anything else, and
armour trims are stuck in the trim palette.

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
drawn. **They are ModelEngine's prefixes**, deliberately: a rig you already
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
