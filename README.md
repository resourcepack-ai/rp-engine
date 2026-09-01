# RP Engine

A custom content engine for Spigot and Paper servers. Custom items, blocks,
furniture you can place and sit on, animated model rigs, player emotes,
sounds, chat icons, GUI screens, HUD overlays, recipes, entities and liquids —
all defined in a folder of YAML on your own server, and all served to players
as a resource pack the plugin builds for you.

You write the content. The engine writes the pack.

```
plugins/RPEngine/content/
  mypack/
    pack.yml
    items/gems.yml
    assets/textures/item/ruby.png
```

```yaml
# items/gems.yml
ruby:
  material: DIAMOND
  name: "Ruby"
```

`/rp reload`, then `/rp give mypack:ruby`. The texture, the model, the pack
and the `minecraft:item_model` component that ties them together are all
derived.

## Requirements

- **Minecraft 1.19.4 or newer.** The floor is where display entities arrived;
  a placed model is one, so there is no reduced version of the feature below
  it and the plugin refuses to start rather than half-work.
- **Java 17 or newer**, which is what a 1.19.4 server already runs on.
- Spigot, Paper, or any fork of either.

Everything between 1.19.4 and current works, degrading feature by feature
rather than refusing. The startup report names every capability your server
version does not have and what you lose without it — see `Feature` in the
`api` package, which is the whole of that policy.

## Installing

Drop `RPEngine.jar` in `plugins/` and start the server. It creates
`plugins/RPEngine/content/` and builds an empty pack; add a namespace folder
and `/rp reload`.

## Documentation

| | |
|---|---|
| [`FORMAT.md`](FORMAT.md) | The content folder. Every file, key and default. This is the reference for writing content. |
| [`API.md`](API.md) | For plugin developers: the services, the events, and what is guaranteed to keep working. |

## For plugin developers

The `ai.resourcepack.engine.api` package is the supported surface: `Items`,
`Models`, `Emotes`, `Sounds`, `Icons`, the `ContentRegistry`, and the events
in `api.event`. Nothing under `core` is API, and it changes without notice.

Everything hangs off the plugin instance, so add `softdepend: [RPEngine]` to
your own `plugin.yml` and ask for it by name:

```java
RPEnginePlugin engine = (RPEnginePlugin) Bukkit.getPluginManager().getPlugin("RPEngine");

ContentId ruby = ContentId.parse("mypack:ruby").orElseThrow();
Optional<ItemStack> stack = engine.items().create(ruby);
```

`API.md` has the rest, including which events are cancellable and what
cancelling one actually prevents.

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`. Gradle 8.10.2 needs a JDK it can run on —
17 or 21 both work, 24 does not. If your default `java` is newer:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew build
```

That is separate from the bytecode the jar ships, which is Java 17 and is set
by a toolchain Gradle downloads on its own.

`./gradlew auditOldestApi` compiles against the oldest supported Spigot API
and is what stops a new call quietly raising the 1.19.4 floor. Run it before
opening a pull request that touches a Bukkit call.

`integration/` is a second plugin that drives this one against a real Paper
server; `node integration/run.mjs` runs it. Several defects have been caught
there and nowhere else.

## ResourcePack AI Studio

[resourcepack.ai](https://resourcepack.ai) is a web editor that can generate
content and push it straight to a running server, which is where this plugin
came from. It is **one content source among several, and never the contract**:
a hand-authored content folder gets a complete plugin, and anything that only
works with Studio in the picture is a bug in this repository. `/rp sync
<code>` is the whole of the connection, and a server that never runs it loses
nothing but the pushing.

## Licence

GPL-3.0-or-later. See [`LICENSE`](LICENSE).

The jar bundles [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket)
(MIT) and [night-config](https://github.com/TheElectronWill/night-config)
(LGPL-3.0), both relocated so this plugin cannot break another plugin's copy.
Everything else — the Spigot API, Geyser, PlaceholderAPI, MythicMobs,
WorldGuard, Citizens — is compile-only and comes from the server at runtime.
