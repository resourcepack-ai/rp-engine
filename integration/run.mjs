#!/usr/bin/env node
// Runs RP Engine's integration harness against a real Paper server.
//
//   node integration/run.mjs                  every scenario
//   node integration/run.mjs buckets events   only these
//   node integration/run.mjs --keep           leave the server folder behind
//
// The server jar: --paper <path>, else RPENGINE_TEST_PAPER, else downloaded
// once into integration/.cache and reused for ever after.
//
// Plain Node, no dependencies, same as scripts/deploy.mjs at the repo root.

import { spawn, spawnSync } from "node:child_process";
import { createWriteStream } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const WORK = path.join(ROOT, "build", "integration");
const CACHE = path.join(HERE, ".cache");

/** The Minecraft this is pinned to. Bump it deliberately, not by drifting. */
const MINECRAFT = "1.21.8";

/**
 * The scenarios, in order, and what each one needs set up first.
 *
 * `fresh` wipes the generated datapack and the pool store before booting, so
 * "the colour is not live on the run that wrote it" is being tested rather
 * than remembered. The rest run against what the previous boot left, which is
 * the point: a restart is the only way a biome becomes real.
 */
const SCENARIOS = [
  { name: "first-boot", fresh: true },
  { name: "painting", pools: "old" },
  { name: "buckets" },
  { name: "commands", config: { "player-commands": true } },
  { name: "commands-off", scenario: "commands", config: { "player-commands": false } },
  { name: "events" },
  { name: "blocks" },
];

/**
 * An animated Blockbench project, so the animation events have something to
 * play. Written out rather than checked in as a binary: a fixture you can read
 * is a fixture you can fix.
 */
const WINDMILL = "{\n  \"meta\": {\n    \"format_version\": \"4.5\",\n    \"model_format\": \"free\",\n    \"box_uv\": false\n  },\n  \"name\": \"windmill\",\n  \"resolution\": {\n    \"width\": 16,\n    \"height\": 16\n  },\n  \"elements\": [\n    {\n      \"name\": \"blade\",\n      \"type\": \"cube\",\n      \"uuid\": \"10000000-0000-0000-0000-000000000001\",\n      \"from\": [\n        6,\n        0,\n        6\n      ],\n      \"to\": [\n        10,\n        12,\n        10\n      ],\n      \"origin\": [\n        8,\n        0,\n        8\n      ],\n      \"faces\": {\n        \"north\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"east\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"south\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"west\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"up\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"down\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        }\n      }\n    }\n  ],\n  \"outliner\": [\n    {\n      \"name\": \"b_blades\",\n      \"uuid\": \"20000000-0000-0000-0000-000000000001\",\n      \"origin\": [\n        8,\n        0,\n        8\n      ],\n      \"children\": [\n        \"10000000-0000-0000-0000-000000000001\"\n      ]\n    }\n  ],\n  \"textures\": [\n    {\n      \"name\": \"windmill\",\n      \"id\": \"0\",\n      \"source\": \"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==\"\n    }\n  ],\n  \"animations\": [\n    {\n      \"uuid\": \"30000000-0000-0000-0000-000000000001\",\n      \"name\": \"spin\",\n      \"loop\": \"loop\",\n      \"length\": 2.0,\n      \"animators\": {\n        \"20000000-0000-0000-0000-000000000001\": {\n          \"name\": \"b_blades\",\n          \"type\": \"bone\",\n          \"keyframes\": [\n            {\n              \"channel\": \"rotation\",\n              \"time\": 0.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 0,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            },\n            {\n              \"channel\": \"rotation\",\n              \"time\": 2.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 360,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            }\n          ]\n        }\n      }\n    },\n    {\n      \"uuid\": \"30000000-0000-0000-0000-000000000002\",\n      \"name\": \"open\",\n      \"loop\": \"hold\",\n      \"length\": 1.0,\n      \"animators\": {\n        \"20000000-0000-0000-0000-000000000001\": {\n          \"name\": \"b_blades\",\n          \"type\": \"bone\",\n          \"keyframes\": [\n            {\n              \"channel\": \"rotation\",\n              \"time\": 0.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 0,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            },\n            {\n              \"channel\": \"rotation\",\n              \"time\": 1.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 0,\n                  \"z\": 90\n                }\n              ],\n              \"interpolation\": \"linear\"\n            }\n          ]\n        }\n      }\n    },\n    {\n      \"uuid\": \"30000000-0000-0000-0000-000000000003\",\n      \"name\": \"pop\",\n      \"loop\": \"once\",\n      \"length\": 0.5,\n      \"animators\": {\n        \"20000000-0000-0000-0000-000000000001\": {\n          \"name\": \"b_blades\",\n          \"type\": \"bone\",\n          \"keyframes\": [\n            {\n              \"channel\": \"position\",\n              \"time\": 0.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 0,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            },\n            {\n              \"channel\": \"position\",\n              \"time\": 0.5,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 2,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            }\n          ]\n        }\n      }\n    }\n  ]\n}";

const PACK = {
  "pack.yml": "name: Integration Test Pack\nauthor: harness\nversion: 1.0.0\n",
  "liquids/liquids.yml": `acid:
  base: water
  color: "#3FBF4A"
  effect: POISON
  amplifier: 1
  damage: 1.0
  tags: [dangerous]

blood:
  base: water
  color: RED
  damage: 0.5

magma:
  base: lava
  color: 0xFF6A00
  fireproof: true

plain:
  base: water
  effect: SPEED

wrong_colour:
  base: water
  color: ochre

hash_unquoted:
  base: water
  color: 3FBF4A
`,
  "items/buckets.yml": `acid_bucket:
  material: BUCKET
  name: "Acid Bucket"
  liquid: testpack:acid

magma_bucket:
  material: BUCKET
  liquid: testpack:magma

ghost_bucket:
  material: BUCKET
  liquid: testpack:not_a_liquid

broken_bucket:
  material: BUCKET
  liquid: "not an id"
`,
  "assets/models/windmill.bbmodel": WINDMILL,
  "items/models.yml": `windmill:
  material: PAPER
  name: "Test Windmill"
  model: windmill
  place:
    facing: cardinal
`,
  "blocks/blocks.yml": `ruby_ore:
  base: note_block
  model: chair
  hardness: 3.0
  tool: pickaxe
  drop: testpack:ruby
  sound: stone

inert_block:
  base: mushroom_stem
  hardness: 1.0
`,
  "entities/mobs.yml": `sentry:
  type: ZOMBIE
  name: "Sentry"
  health: 10
  tags: [test]
`,
};

/** A pool written by a build that had never heard of colours. */
const OLD_POOLS = JSON.stringify({
  pools: [{
    liquid: "old:pool", world: "world",
    minX: 10, minY: 60, minZ: 10, maxX: 20, maxY: 64, maxZ: 20,
  }],
});

const args = process.argv.slice(2);
const keep = args.includes("--keep");
const paperArg = valueOf("--paper");
const only = args.filter((a) => !a.startsWith("--") && a !== paperArg);

function valueOf(flag) {
  const at = args.indexOf(flag);
  return at === -1 ? null : args[at + 1];
}

function say(line) {
  process.stdout.write(`${line}\n`);
}

async function exists(at) {
  try {
    await fs.access(at);
    return true;
  } catch {
    return false;
  }
}

/** Builds both jars. Gradle is the only thing that knows how. */
function build() {
  say("Building RPEngine.jar and the harness…");
  // The wrapper by absolute path: this is run from wherever somebody happens
  // to be, and a bare "gradlew.bat" is only found when it is the cwd.
  const wrapper = path.join(ROOT, process.platform === "win32" ? "gradlew.bat" : "gradlew");
  const built = spawnSync(wrapper, ["shadowJar", "harnessJar", "-q"], {
    cwd: ROOT, stdio: "inherit", shell: process.platform === "win32",
  });
  if (built.status !== 0) {
    throw new Error("Gradle build failed.");
  }
}

/** The server jar, downloaded once. */
async function paper() {
  if (paperArg) return path.resolve(paperArg);
  if (process.env.RPENGINE_TEST_PAPER) return path.resolve(process.env.RPENGINE_TEST_PAPER);

  await fs.mkdir(CACHE, { recursive: true });
  // v3 (fill.papermc.io). v2 was sunset in 2025 and answers every request with
  // a refusal, so a runner pinned to it fails looking like a network problem.
  const builds = await fetch(
    `https://fill.papermc.io/v3/projects/paper/versions/${MINECRAFT}/builds`,
    { headers: { "User-Agent": "rp-engine-integration/1.0" } },
  ).then((r) => r.json());
  const download = builds[0]?.downloads?.["server:default"];
  if (!download) {
    throw new Error(`No Paper build for ${MINECRAFT}. Pass --paper <path> instead.`);
  }
  const at = path.join(CACHE, download.name);
  if (await exists(at)) return at;

  say(`Downloading ${download.name}…`);
  const jar = await fetch(download.url, {
    headers: { "User-Agent": "rp-engine-integration/1.0" },
  });
  await fs.writeFile(at, Buffer.from(await jar.arrayBuffer()));
  return at;
}

/** A server folder with nothing in it but this plugin and the harness. */
async function prepare(paperJar) {
  await fs.rm(WORK, { recursive: true, force: true });
  await fs.mkdir(path.join(WORK, "plugins"), { recursive: true });

  await fs.copyFile(paperJar, path.join(WORK, "paper.jar"));
  await fs.copyFile(
    path.join(ROOT, "build", "libs", `rp-engine-${await version()}.jar`),
    path.join(WORK, "plugins", "RPEngine.jar"),
  );
  await fs.copyFile(
    path.join(ROOT, "build", "libs", "RPEngineHarness.jar"),
    path.join(WORK, "plugins", "RPEngineHarness.jar"),
  );

  await fs.writeFile(path.join(WORK, "eula.txt"), "eula=true\n");
  await fs.writeFile(path.join(WORK, "server.properties"), [
    // A port nothing else is on, offline mode, and as little world as the
    // server will agree to generate: this boots six times per run.
    "server-port=25777",
    "online-mode=false",
    "enable-rcon=false",
    "max-players=1",
    "view-distance=4",
    "simulation-distance=4",
    "spawn-protection=0",
    "level-type=minecraft\\:flat",
    "allow-nether=false",
    "generate-structures=false",
    "sync-chunk-writes=false",
    "",
  ].join("\n"));

  for (const [name, body] of Object.entries(PACK)) {
    const at = path.join(WORK, "plugins", "RPEngine", "content", "testpack", name);
    await fs.mkdir(path.dirname(at), { recursive: true });
    await fs.writeFile(at, body);
  }
}

async function version() {
  const gradle = await fs.readFile(path.join(ROOT, "build.gradle"), "utf8");
  return gradle.match(/^version = '([^']+)'/m)[1];
}

/** Whatever this scenario wants to be true before the server starts. */
async function arrange(step) {
  const data = path.join(WORK, "plugins", "RPEngine");
  if (step.fresh) {
    await fs.rm(path.join(WORK, "world", "datapacks", "rpengine_liquids"),
      { recursive: true, force: true });
    await fs.rm(path.join(data, "liquids.json"), { force: true });
  }
  if (step.pools === "old") {
    await fs.mkdir(data, { recursive: true });
    await fs.writeFile(path.join(data, "liquids.json"), OLD_POOLS);
  }
  if (step.config) {
    // config.yml is written by the plugin on its first boot, so this edits
    // what is there rather than writing one from nothing.
    const at = path.join(data, "config.yml");
    if (await exists(at)) {
      let text = await fs.readFile(at, "utf8");
      for (const [key, value] of Object.entries(step.config)) {
        text = text.includes(`  ${key}:`)
          ? text.replace(new RegExp(`^ {2}${key}:.*$`, "m"), `  ${key}: ${value}`)
          : text.replace(/^emotes:$/m, `emotes:\n  ${key}: ${value}`);
      }
      await fs.writeFile(at, text);
    }
  }
}

/** One boot. Resolves with the RPTEST lines it printed. */
function boot(step) {
  return new Promise((resolve, reject) => {
    const log = createWriteStream(path.join(WORK, `${step.name}.log`));
    const server = spawn("java", [
      `-Drpengine.it=${step.scenario ?? step.name}`,
      "-Xms1G", "-Xmx2G", "-jar", "paper.jar", "nogui",
    ], { cwd: WORK });

    const lines = [];
    let done = false;
    const watch = (chunk) => {
      log.write(chunk);
      for (const line of chunk.toString().split("\n")) {
        const found = line.match(/RPTEST (PASS|FAIL|NOTE|DONE|SCENARIO) ?(.*)/);
        if (!found) continue;
        lines.push({ kind: found[1], text: found[2].trim() });
        if (found[1] === "DONE") done = true;
      }
    };
    server.stdout.on("data", watch);
    server.stderr.on("data", watch);

    // The harness shuts the server down when it is finished. This is for when
    // it never got that far — a plugin that failed to enable, a world that
    // would not generate — so a broken run fails rather than hanging CI.
    const patience = setTimeout(() => {
      server.kill();
      reject(new Error(`${step.name}: no result after 6 minutes; see ${step.name}.log`));
    }, 6 * 60_000);

    server.on("close", (code) => {
      clearTimeout(patience);
      log.end();
      if (!done) {
        reject(new Error(
          `${step.name}: server exited (${code}) without finishing; see ${step.name}.log`));
        return;
      }
      resolve(lines);
    });
  });
}

/**
 * Clears up after a run. The logs stay; the world and the jars do not.
 *
 * <p>Retried, because the server has only just exited and Windows can still
 * be holding level.dat for a moment after that. A failure here is never worth
 * failing a run over — the results are already in — so it gives up quietly.
 */
async function tidy() {
  for (const name of ["world", "plugins", "paper.jar", "libraries", "versions", "cache"]) {
    for (let tries = 0; tries < 5; tries++) {
      try {
        await fs.rm(path.join(WORK, name), { recursive: true, force: true });
        break;
      } catch {
        await new Promise((wake) => setTimeout(wake, 400));
      }
    }
  }
}

async function main() {
  build();
  const jar = await paper();
  await prepare(jar);

  // Named scenarios run everything up to and including them, because the
  // sequence is the test: the colour datapack has to have been written by an
  // earlier boot for a later one to find it registered. Asking for `buckets`
  // and getting a server that never wrote a biome is a confusing way to fail.
  let steps = SCENARIOS;
  if (only.length) {
    const last = SCENARIOS.reduce(
      (found, step, at) => (only.includes(step.name) ? at : found), -1);
    if (last === -1) {
      throw new Error(`No scenario called ${only.join(", ")}.`);
    }
    steps = SCENARIOS.slice(0, last + 1);
    const before = steps.filter((s) => !only.includes(s.name)).map((s) => s.name);
    if (before.length) {
      say(`Running ${before.join(", ")} first: ${only.join(", ")} needs what they leave behind.`);
    }
  }

  let passed = 0;
  let failed = 0;
  for (const step of steps) {
    say(`\n── ${step.name} ──`);
    await arrange(step);
    for (const line of await boot(step)) {
      if (line.kind === "PASS") passed++;
      if (line.kind === "FAIL") failed++;
      if (line.kind === "FAIL" || line.kind === "NOTE") {
        say(`   ${line.kind === "FAIL" ? "FAIL" : "note"}  ${line.text}`);
      }
    }
    say(`   ${passed} passed, ${failed} failed so far`);
  }

  if (!keep) {
    await tidy();
  }

  say(`\n${failed ? "FAILED" : "OK"}: ${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  say(`\n${e.message}`);
  process.exit(1);
});
