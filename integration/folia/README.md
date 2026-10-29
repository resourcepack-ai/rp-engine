# The Folia session

RP Engine on a real **Folia** server, with two real players in two different
regions, checked from both ends: what the players receive, and what the server
logs.

```bash
cd integration/folia
npm install                 # once: mineflayer, for the two players
node run.mjs                # build, boot Folia, play, check
node run.mjs --no-build     # the jar already in build/libs
node run.mjs --jar <jar>    # any RP Engine jar
node run.mjs --keep         # leave build/folia behind
```

It downloads a Folia build (cached in `integration/.cache`, gitignored), boots
it in `build/folia` with the harness's test pack plus a few things the harness
does not need (a vehicle, two emotes, a VFX, a dialog), and then two
[mineflayer](https://github.com/PrismarineJS/mineflayer) players join. One is
sent 3,000 blocks away, so the two are in different regions, and each does
things in its own region, to the other's, and through the console:

- a model with an animation placed in each region, and its parts seen moving
- a custom block placed and broken
- a custom mob spawned, a VFX cast, an emote played
- a dialog opened by one player for the other, and by the console
- the console casting a VFX at a place in each region
- a vehicle parked, boarded and driven
- a liquid pool marked out
- `/rp reload` with all of that standing, and the models still moving after it
- a clean stop

Every `CHECK` line it prints is one thing a player saw. **The run also fails on
any stack trace in the server's log**, because on Folia a thread mistake is an
exception wherever it happens, including ones the engine catches and carries on
from. Pass `VERBOSE=1` to see the players' chat as it goes.

## Why it is not part of `run.mjs`

The harness in `integration/` drives the engine from a second plugin, through
the Bukkit scheduler, with no player. Folia has no main thread and refuses that
scheduler, and the things most likely to be wrong on Folia only happen with
players in the world: two regions, a command aimed from one at the other, a
rig posed on its own region every tick. So this is players and a log rather
than a plugin, and it needs a dependency (mineflayer) the harness does not.

Running both is the whole check: `node integration/run.mjs` for Paper, which
has to behave exactly as it did before Folia support, and this for Folia.

## What it cannot reach

Anything that needs a client to draw it, Geyser, the pack host's download by a
real client, and anything needing a third player.
