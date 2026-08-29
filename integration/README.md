# The integration harness

A second, tiny plugin that drives RP Engine against a **real Paper server**,
with no player and no client.

```bash
node integration/run.mjs                 # every scenario
node integration/run.mjs buckets events  # only these
node integration/run.mjs --keep          # leave the server folder behind
```

It builds both jars, fetches a Paper server (cached in `integration/.cache`,
gitignored), and boots it once per scenario in `build/integration`. Every check
prints an `RPTEST` line the runner reads back; the process exits non-zero if any
of them said FAIL. A full run is about six boots and takes a couple of minutes.

The Paper jar can come from elsewhere: `--paper <path>`, or
`RPENGINE_TEST_PAPER`. `MINECRAFT` at the top of `run.mjs` pins the version.

## Why this exists

The things most likely to be wrong here are not reachable from a unit test:

- a **biome** the server has to accept and register, from a datapack this
  plugin wrote
- a **chunk** that has to be re-sent before anybody sees a colour change
- a **command** withdrawn from a map that only CraftBukkit has
- a **block** placed, and the pool bookkeeping that follows it

Two real defects turned up the first time this ran — a generated biome that
repainted the grass, and a bucket that replaced the torch you clicked — and
both passed everything in `src/test`.

## How a scenario works

`Harness.java` is one plugin with a switch on `-Drpengine.it=<name>`, and
`run.mjs` owns the list. **One scenario per boot on purpose**: a biome only
becomes real on the run after the one that wrote it, which nothing inside a
single JVM can fake. `first-boot` and `painting` are that pair.

`arrange` in the runner sets up what a scenario needs first — wiping the
generated datapack, planting a `liquids.json` written by an older build,
flipping a config key — so each one states its own preconditions rather than
inheriting whatever the last run happened to leave.

Reaching into `core` by reflection, and handing the placement path a `Player`
that is a dynamic proxy, are both deliberate: this is a test, and the point is
to drive the shipping code rather than a copy of it.

## What it cannot reach

Anything needing a real client or a real body: `ModelSeatEvent` (somebody has
to sit down), `PlayerLiquidEvent` (somebody has to stand in the water),
`ModelAnimationEndEvent` (needs a rig with an animation in it), and every
question about how any of this *looks*. The harness says so out loud at the end
of the `events` scenario rather than quietly not testing them.
