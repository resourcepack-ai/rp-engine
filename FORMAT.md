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
`mypack` would be one id to us and two to the client.

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

Nothing in the engine gives an item behaviour, on purpose: a wand that casts, a
key that opens a door, a compass that points somewhere are all decisions about a
particular server. What the engine does is say what happened, with
`ItemUseEvent` — id, stack, action, block. Cancel it and the vanilla use of the
stack is cancelled too, so an item that is a bucket underneath does not fill
with water while it is meant to be a wand.

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
    seat: 0.6            # sit on it, this far above its base. 0 is no seat
    # width and height are the hitbox, in blocks. Leave them out and they are
    # measured off the model, which is almost always what you want.
```

Not a category of its own, because an id is unique across the whole registry:
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

Category folders are walked recursively, so `items/weapons/swords.yml` is
fine. The subfolder is organisation only: **it contributes nothing to the id**.

### Sitting on one

`seat:` is how far above the model's base a player's backside goes, in blocks —
about `0.6` for a dining chair. Right-click to sit, shift to get up.

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

## Liquids

Minecraft has two fluids and a server cannot add a third. A custom liquid is
**real water or lava with your rules applied to whoever is in it** — it swims,
flows, floats boats and is seen as water by every other plugin, because it is
water. What tells acid from ocean is a volume somebody marked out, not the
blocks in it.

```yaml
acid:
  base: water          # water | lava
  effect: POISON
  amplifier: 1
  damage: 1.0          # per second
  fireproof: false
```

Marking one out is two corners and a name: `/rp liquid corner`, walk to the
opposite corner, `/rp liquid fill mypack:acid`. Pools are saved to
`liquids.json` beside the other stores, in the order they were made, and the
first one containing a point wins — so a small pool drawn inside a big one only
counts if it was drawn first.

Boxes rather than a record of every block: a lake is thousands of blocks that
change shape as it flows, and a per-block record would be wrong within a second
of somebody breaking a bank.

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

Top-level keys are **id paths**. The kind comes from the folder, so nesting
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

Slashes are allowed in an id path, and they are how you get a hierarchy:

```yaml
weapons/ruby_sword:
  material: DIAMOND_SWORD
```

is `mypack:weapons/ruby_sword`, regardless of which file or subfolder it was
written in.

**One file may declare many, and many files may declare into one prefix.**
The file name means nothing. This is deliberate: a format that ties ids to
file names forces a choice between one file per item and one enormous file
per category, and both are unpleasant at the sizes real packs reach.

The body of a definition is whatever the layer for that kind understands, and
is not the loader's business. The loader validates the id and the kind, and
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
`Sounds` API. The id **is** the sound event name, so
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
messages. An id that names nothing is **left exactly as written** rather than
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

**Never store the character, store the id.** Codepoints are handed out in id
order, so adding an icon whose id sorts earlier shifts the ones after it. That
is invisible to anything resolving the id as it writes the text, and wrong for
anything that saved the character — a glyph written into a sign or a book is a
different picture after the next reload. Stable codepoints would need a file
mapping id to number that must never be lost or reordered, which is exactly the
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

**Ingredients and results can be either.** A content id matches that exact item
— an ordinary diamond will not satisfy a recipe calling for `mypack:ruby`, even
though a ruby is a diamond underneath. A vanilla material name matches loosely,
the way a vanilla ingredient should.

**A recipe id is not a content id.** It lives outside the id space, so a recipe
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
