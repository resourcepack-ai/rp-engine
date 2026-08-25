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
    blocks/      *.yml
    furniture/   *.yml
    models/      *.yml
    emotes/      *.yml
    sounds/      *.yml
    fonts/       *.yml
    screens/     *.yml
    huds/        *.yml
    assets/                  -> assets/mypack/ in the built pack
      textures/  **.png
      geometry/  **.bbmodel
      sounds/    **.ogg
      fonts/     **.png
    overrides/               -> assets/minecraft/ in the built pack
      textures/block/stone.png
    pack.png                 optional, offered to the bundle
```

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

**`pack.png` is offered, not claimed.** A bundle has one icon and takes it from
the first namespace alphabetically that ships one. The rest get a warning
saying theirs is unused.

**The category folder decides the kind.** `items/` yields `ITEM`, `blocks/`
yields `BLOCK`, and so on through `ContentKind`. A folder that is not one of
the nine is ignored with a warning rather than an error, so a `README/` or a
`.git/` in somebody's pack does not stop it loading.

Category folders are walked recursively, so `items/weapons/swords.yml` is
fine. The subfolder is organisation only: **it contributes nothing to the id**.

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
