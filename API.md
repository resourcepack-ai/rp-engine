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
engine.sounds();    // custom sounds
engine.icons();     // icons, and putting one into a piece of text
engine.registry();  // everything this server holds, by id
engine.registration(); // and how to put content of your own into it
```

Add `softdepend: [RPEngine]` to your `plugin.yml` so you load after it.

**Everything in `ai.resourcepack.engine.api` is supported. Nothing in
`ai.resourcepack.engine.core` is** — it is public only because javac demanded
it across a package boundary, and it may change in any release including a
patch.

## Ids

One `namespace:path` id for everything, and it is a Minecraft resource
location. `ContentId.parse` answers empty rather than throwing, so an id out of
somebody's config is a message rather than a stack trace.

```java
ContentId ruby = ContentId.parse("mypack:ruby").orElseThrow();
```

**Store ids, never the things they resolve to.** An icon's codepoint moves when
content changes, and an item's model is derived from its id — the id is the
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
asked what it is, told to play an animation, and removed.

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

## Icons in your own text

```java
String line = engine.icons().format(config.getString("welcome"));
```

Every `:namespace:id:` becomes its picture. An id that names nothing is left
exactly as written, so text never silently loses a chunk of itself.

## Events

All cancellable unless noted.

| Event | When |
|---|---|
| `ItemUseEvent` | A custom item was right- or left-clicked. Cancelling also cancels the vanilla use |
| `ModelPlaceEvent` | A model is about to be put down |
| `ModelBreakEvent` | A model is about to be broken. Carries a drop flag separate from cancelling |
| `ModelInteractEvent` | A placed model was right-clicked |
| `ModelAnimationEvent` | A rig is about to play an animation |
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

## Content of your own

A plugin can be a content source rather than shipping a folder. Claim a
namespace, define into the handle, release it when you disable.

```java
ClaimResult claim = engine.registration().claim("myplugin", ContentSource.EMBEDDED);
claim.namespace().ifPresent(ns -> ns.define(ContentKind.ITEM, "ruby"));
```

The handle is what proves ownership: holding `myplugin` cannot define
`otherpack:thing`, so two sources loading at once cannot corrupt each other's
half of the id space. `EMBEDDED` content is not second class — same registry,
same id rules, and the pack builder cannot tell it from a hand-written folder.

## Threading

Reads that ask what the server **holds** — `ids`, `info`, the whole of
`ContentRegistry` — are safe from any thread. Anything touching a player, an
entity or a world is main thread only, and says so on the method.

## What is not API

`core.*`, the content loader, the pack builder, and the sync client. If you
find yourself wanting one, that is the signal something belongs in `api` —
raise it rather than reaching in, because a patch release will move it.
