package ai.resourcepack.engine.api;

/**
 * How a custom item's model is addressed on a given server.
 *
 * <p>Unlike {@link Feature}, this is not a capability that is present or
 * absent — it is a fork, and exactly one arm is taken. It gets its own type
 * because the choice reaches both halves of the engine at once: it decides
 * what the pack builder writes into the zip <em>and</em> what the plugin puts
 * on an {@code ItemStack}, and those two must agree or every custom item in
 * the game is a purple cube. A pair of booleans read at two call sites is
 * exactly how they would come to disagree.
 *
 * <p>Three arms, but the engine only ever branches two ways — see
 * {@link #NBT} for why the third is carried anyway.
 *
 * <p><b>These names are shared with studio deliberately.</b> Studio's pack
 * exporter has the same three arms under the same names, in
 * {@code Studio's carrier-era table} ({@code CarrierEra}), because a pack
 * exported from studio and a pack built here have to be loadable on the same
 * server. If an arm is added or its boundary moves, it moves in both — there
 * is no shared type between a Worker and a jar to catch it.
 */
public enum ItemEra {

    /**
     * 1.21.4 and up. The item's id is its model reference, written into the
     * {@code item_model} component and resolved through an {@code items/}
     * definition file.
     *
     * <p>The arm the engine was designed around, and the only one where
     * nothing is allocated: the id <em>is</em> the path.
     */
    DEFINITIONS,

    /**
     * 1.20.5 to 1.21.3. The model is chosen by a number in the
     * {@code custom_model_data} component, matched by a predicate in the base
     * item's own model file.
     *
     * <p>Numbers have to come from somewhere and stay put, which is what
     * {@code ModelNumbers} exists for.
     */
    COMPONENTS,

    /**
     * 1.19.4 to 1.20.4. The same numbering as {@link #COMPONENTS}, the same
     * predicates in the pack, and — the part worth writing down — the same
     * code here.
     *
     * <p>{@code ItemMeta.setCustomModelData(Integer)} has existed since 1.14
     * and still does; what changed underneath in 1.20.5 is where the server
     * stores the value, which is not something this side can see. So the
     * engine does not fork between this arm and {@link #COMPONENTS} anywhere:
     * ask {@link #needsNumbers()} instead, which is the question it actually
     * has.
     *
     * <p>The arm exists anyway, because the boundary is real elsewhere.
     * Studio writes {@code /give} command text, and the syntax for attaching
     * the number differs across exactly this line. Keeping both sides' arms
     * named the same is what lets somebody hold the two files up against each
     * other, which is the only check these two have.
     */
    NBT;

    /** Which arm a server on {@code version} takes. */
    public static ItemEra on(McVersion version) {
        if (Feature.ITEM_DEFINITIONS.on(version)) {
            return DEFINITIONS;
        }
        return Feature.ITEM_COMPONENTS.on(version) ? COMPONENTS : NBT;
    }

    /**
     * Whether this arm needs a number allocated for every model.
     *
     * <p>True for both legacy arms, and the single question most of the
     * builder actually wants to ask — it keeps the two-of-three test out of
     * call sites that do not otherwise care which legacy arm they are on.
     */
    public boolean needsNumbers() {
        return this != DEFINITIONS;
    }
}
