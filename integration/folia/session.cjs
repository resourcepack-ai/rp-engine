// Two players, two Folia regions, and RP Engine working on each from the
// other and from the console. Run by run.mjs, which reads what this prints:
//
//   CONSOLE <command>    run as the server console
//   CHECK PASS|FAIL <what>
//
// Anything else is narration, shown with VERBOSE=1.
const mineflayer = require("mineflayer");
const { Vec3 } = require("vec3");

const port = Number(process.argv[2] || 25778);
const FAR = 3000;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const consoleCmd = (line) => console.log(`CONSOLE ${line}`);
const check = (what, ok) => console.log(`CHECK ${ok ? "PASS" : "FAIL"} ${what}`);

function join(name) {
  const bot = mineflayer.createBot({ host: "127.0.0.1", port, username: name, version: "1.21.8", auth: "offline" });
  const say = (...a) => console.log(name, ...a);
  bot.seen = { dialogs: 0, packs: 0, displays: new Set(), transforms: 0 };
  bot._client.on("show_dialog", () => bot.seen.dialogs++);
  bot._client.on("add_resource_pack", (p) => {
    bot.seen.packs++;
    // Accepted, then loaded: what a client that took it says.
    bot._client.write("resource_pack_receive", { uuid: p.uuid, result: 3 });
    bot._client.write("resource_pack_receive", { uuid: p.uuid, result: 0 });
  });
  // An item display is what every rig part, sprite, emote bone and armour
  // piece is drawn with, and a pose reaches the client as its metadata.
  bot._client.on("spawn_entity", (p) => {
    const type = bot.registry.entitiesArray.find((e) => e.id === p.type);
    if (type && type.name === "item_display") bot.seen.displays.add(p.entityId);
  });
  bot._client.on("entity_metadata", (p) => {
    if (bot.seen.displays.has(p.entityId)) bot.seen.transforms++;
  });
  bot.on("messagestr", (m) => { if (m.trim() && !/km\/h/.test(m)) say("chat:", m.slice(0, 150)); });
  bot.on("kicked", (r) => say("kicked", JSON.stringify(r).slice(0, 200)));
  bot.on("error", (e) => say("error", e.message));
  bot.say = say;
  return new Promise((resolve) => bot.once("spawn", () => resolve(bot)));
}

/** How many display updates arrive in a while: a posed rig or a playing sprite keeps sending them. */
async function transforms(bot, ms) {
  const before = bot.seen.transforms;
  await wait(ms);
  return bot.seen.transforms - before;
}

async function placeHeld(bot, itemName) {
  const item = bot.inventory.items().find((i) => i.name === itemName);
  if (!item) return false;
  await bot.equip(item, "hand");
  const below = bot.blockAt(bot.entity.position.offset(2, -1, 0).floored());
  if (!below) return false;
  await bot.lookAt(below.position.offset(0.5, 1, 0.5));
  try {
    await bot.activateBlock(below, new Vec3(0, 1, 0));
    return true;
  } catch (e) {
    bot.say("place:", e.message);
    return false;
  }
}

(async () => {
  const near = await join("FoliaBot");
  const far = await join("FarBot");
  consoleCmd("op FoliaBot");
  consoleCmd("op FarBot");
  consoleCmd(`tp FarBot ${FAR} -60 ${FAR}`);
  await wait(8000);
  check("the far player is in another region", far.entity.position.distanceTo(near.entity.position) > 2000);
  check("both were offered the packs", near.seen.packs > 0 && far.seen.packs > 0);

  // ---- a rig in each region ------------------------------------------------
  near.chat("/rp give testpack:windmill");
  far.chat("/rp give testpack:windmill");
  await wait(1500);
  await placeHeld(near, "paper");
  await placeHeld(far, "paper");
  await wait(1500);
  check("a rig placed here animates", await transforms(near, 3000) > 0);
  check("a rig placed far away animates", await transforms(far, 3000) > 0);

  // ---- a custom block, placed and broken -----------------------------------
  near.chat("/rp give testpack:inert_block");
  await wait(1000);
  const block = near.inventory.items().find((i) => i.name === "mushroom_stem");
  let broken = false;
  if (block) {
    await near.equip(block, "hand");
    const ground = near.blockAt(near.entity.position.offset(-2, -1, 0).floored());
    try {
      await near.placeBlock(ground, new Vec3(0, 1, 0));
      const placed = near.blockAt(ground.position.offset(0, 1, 0));
      if (placed && placed.name === "mushroom_stem") {
        await near.dig(placed, true);
        broken = near.blockAt(ground.position.offset(0, 1, 0)).name === "air";
      }
    } catch (e) {
      near.say("block:", e.message);
    }
  }
  check("a custom block is placed and broken", broken);

  // ---- the far player's own content ----------------------------------------
  far.chat("/rp spawn testpack:sentry");
  far.chat("/rp cast frost");
  check("a VFX plays far away", await transforms(far, 2500) > 0);
  const displaysBefore = far.seen.displays.size;
  far.chat("/emote dance");
  await wait(3000);
  check("an emote's rig appears far away", far.seen.displays.size > displaysBefore);
  far.chat("/emote stop");
  await wait(1000);

  // ---- one player's command, the other player's region ---------------------
  const farDialogs = far.seen.dialogs;
  near.chat("/rp dialog foliapack:welcome FarBot");
  near.chat("/rp cast frost FarBot");
  await wait(2000);
  check("a dialog opened by one player reaches the other", far.seen.dialogs > farDialogs);

  // ---- the console, at a player and at a place in each region --------------
  const nearDialogs = near.seen.dialogs;
  consoleCmd("rp dialog foliapack:welcome FoliaBot");
  consoleCmd(`rp cast frost at ${FAR + 4} -60 ${FAR} world`);
  consoleCmd("rp cast frost at 4 -60 0 world");
  consoleCmd("emote wave FarBot");
  check("the console's VFX at a place plays", await transforms(near, 2500) > 0);
  check("a dialog the console opens reaches the player", near.seen.dialogs > nearDialogs);

  // ---- a vehicle, driven, in the far region -------------------------------
  far.chat("/rp vehicle foliapack:kart");
  await wait(1500);
  const seat = far.nearestEntity((e) => e.name === "interaction"
    && e.position.distanceTo(far.entity.position) < 6);
  let rode = false;
  let moved = 0;
  if (seat) {
    far.activateEntityAt(seat, seat.position.offset(0, 0.5, 0));
    await wait(1000);
    rode = !!far.entity.vehicle;
    const from = far.entity.position.clone();
    const go = { forward: true, backward: false, left: false, right: false, jump: false, shift: false, sprint: false };
    far._client.write("player_input", { inputs: go });
    await wait(3000);
    far._client.write("player_input", { inputs: { ...go, forward: false } });
    moved = far.entity.position.distanceTo(from);
  }
  check("a player gets into a vehicle", rode);
  check(`the vehicle drives (${moved.toFixed(1)} blocks)`, moved > 3);

  // ---- a liquid pool -------------------------------------------------------
  near.chat("/rp liquid corner");
  await wait(600);
  near.chat("/rp liquid fill testpack:acid");
  await wait(1500);

  // ---- a reload with all of that standing in two regions -------------------
  consoleCmd("rp reload");
  await wait(8000);
  check("the rig here still animates after a reload", await transforms(near, 3000) > 0);
  check("the rig far away still animates after a reload", await transforms(far, 3000) > 0);

  near.chat("/rp liquid clear");
  far.chat("/rp vehicle remove");
  near.chat("/rp purge 16");
  await wait(2000);
  near.quit();
  far.quit();
  await wait(1500);
  process.exit(0);
})().catch((e) => {
  console.log(`CHECK FAIL the session threw: ${e.message}`);
  process.exit(1);
});

setTimeout(() => {
  console.log("CHECK FAIL the session timed out");
  process.exit(2);
}, 240_000);
