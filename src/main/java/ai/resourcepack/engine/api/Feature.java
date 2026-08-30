package ai.resourcepack.engine.api;

/**
 * One thing the engine can do only on a new enough server.
 *
 * <p><b>This enum is the whole version policy.</b> Nothing else in the engine
 * is allowed to compare the server's version against a literal — it asks
 * {@link #on(McVersion)} instead, or asks the {@code Compatibility} the
 * plugin builds at startup. The reason is that a version check written inline
 * is invisible: it does not appear in the startup report, it cannot be
 * explained to the server owner whose feature silently did nothing, and the
 * next person adding a version-gated feature has no list to add to.
 *
 * <p>So the contract for adding a feature that needs a newer game is: add a
 * constant here, and the startup report, the {@code /rp info} output and the
 * docs table all pick it up. Adding one is meant to be the easy part —
 * deciding what happens <em>without</em> it is the work, and that is what
 * {@link #without()} is forced to answer in words a server owner can act on.
 *
 * <p>Every floor here is a <em>degradation</em>: the engine runs, and this
 * one capability is reduced or absent. A version below
 * {@link McVersion#OLDEST_SUPPORTED} is the other thing entirely and is
 * refused at startup rather than modelled here.
 */
public enum Feature {

    /**
     * Several resource packs at once, added and removed per player by id.
     *
     * <p>The engine's bundle model rests on this: a player can hold a base
     * bundle and be handed an event bundle on top, and swapping back to a
     * bundle the client already cached costs nothing. Before 1.20.3 there is
     * one pack slot and sending a second replaces the first.
     */
    PACK_STACKING(
            McVersion.of(1, 20, 3),
            "Multiple resource packs per player",
            "Players hold one pack at a time. Bundles are merged into a single "
                    + "pack per player and swapping bundles re-sends it, so a swap "
                    + "costs a full download instead of being instant."),

    /**
     * Item models chosen by name, via the {@code item_model} component and the
     * {@code items/} definitions format.
     *
     * <p>The reason the id scheme has nothing to allocate: an item's id is
     * written verbatim as its model reference. Without it, models are chosen
     * by a number, and numbers have to be handed out and remembered.
     */
    ITEM_DEFINITIONS(
            McVersion.of(1, 21, 4),
            "Item models addressed by name",
            "Custom items are addressed by an allocated number instead. The engine "
                    + "keeps that allocation itself, but two packs built by different "
                    + "people can no longer be installed side by side without it."),

    /**
     * Data components on an item stack: stack size, food values, a durability
     * that is not the base material's, and a glint with no enchantment.
     *
     * <p>These are per-item settings an author writes in YAML, so the absence
     * shows up as options that parse and then do nothing, which is why they
     * are reported rather than dropped in silence.
     */
    ITEM_COMPONENTS(
            McVersion.of(1, 20, 5),
            "Per-item stack size, food and durability",
            "An item's stack size, food values and durability are whatever its base "
                    + "material's are. Those options are reported as unsupported at load "
                    + "rather than applied."),

    /**
     * Any item wearable in any armour slot, whatever its base material is.
     *
     * <p>This is what lets a wizard hat be a real helmet without also being a
     * leather cap.
     */
    EQUIPPABLE_ITEMS(
            McVersion.of(1, 21, 2),
            "Any item wearable as armour",
            "Only real armour materials can be worn, so an item's armour slot is "
                    + "honoured only when its base material already fits that slot."),

    /**
     * Per-item armour artwork, through the pack's {@code equipment/} assets.
     *
     * <p>Distinct from {@link #EQUIPPABLE_ITEMS} and one release later: 1.21.2
     * decides <em>whether</em> a thing can be worn, 1.21.4 decides what it
     * looks like on a body. A server between the two can wear a custom helmet
     * that draws as the vanilla one.
     */
    ARMOUR_ART(
            McVersion.of(1, 21, 4),
            "Per-item armour artwork",
            "Worn armour draws with vanilla artwork. Old Minecraft supports roughly "
                    + "one custom armour look for a whole server, which is not something "
                    + "the engine can allocate per item."),

    /**
     * A list of strings carried on an item stack in {@code custom_model_data}.
     *
     * <p>The engine uses this as a data channel rather than for rendering: a
     * placed rig's part id, its animation choice and its size ride on it.
     * Without it the same values go in the stack's persistent data, which is
     * strictly better and older — the only thing lost is that a hand-written
     * {@code /give} cannot produce one.
     */
    ITEM_STRING_TAGS(
            McVersion.of(1, 21, 4),
            "String tags on an item stack",
            "Rig parts carry their identity in persistent data instead, which works "
                    + "the same in game. A rig part cannot be produced by a hand-written "
                    + "/give command on this version."),

    /**
     * Biome colours for tinted liquids, written as a generated datapack.
     *
     * <p>The datapack biome format is not stable across versions the way a
     * resource pack's is, so this has a floor of its own rather than riding
     * the engine's.
     */
    LIQUID_TINTING(
            McVersion.of(1, 21, 6),
            "Tinted water and lava",
            "Liquids keep their vanilla colour. Everything else about a custom liquid "
                    + "still works; only the tint needs the biome format this version writes.");

    private final McVersion since;
    private final String label;
    private final String without;

    Feature(McVersion since, String label, String without) {
        this.since = since;
        this.label = label;
        this.without = without;
    }

    /** The oldest version that has this. */
    public McVersion since() {
        return since;
    }

    /** A few words naming the capability, for a table or a status line. */
    public String label() {
        return label;
    }

    /**
     * What a server owner actually gets on a version without this, in their
     * terms rather than the game's.
     *
     * <p>Required of every constant, and the reason this enum is worth having:
     * a feature nobody can describe the absence of is a feature that will go
     * missing silently on somebody's server.
     */
    public String without() {
        return without;
    }

    /** Whether a server on {@code version} has this. */
    public boolean on(McVersion version) {
        return version != null && version.atLeast(since);
    }
}
