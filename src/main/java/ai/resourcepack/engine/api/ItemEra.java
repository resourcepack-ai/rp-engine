package ai.resourcepack.engine.api;

/**
 * How a custom item's model is addressed on a given server.
 *
 * <p>Unlike {@link Feature}, this is not a capability that is present or
 * absent — it is a fork with three arms, and exactly one of them is taken.
 * It gets its own type because the choice reaches both halves of the engine
 * at once: it decides what the pack builder writes into the zip <em>and</em>
 * what the plugin puts on an {@code ItemStack}, and those two must agree or
 * every custom item in the game is a purple cube. A pair of booleans read at
 * two call sites is exactly how they would come to disagree.
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
     * 1.19.4 to 1.20.4. The same numbering as {@link #COMPONENTS} and the same
     * predicates in the pack — the difference is entirely in how the value is
     * attached to a stack, which is item NBT rather than a component.
     *
     * <p>Two arms rather than one because the pack side is identical and the
     * server side is not: the API that sets it changed underneath, so the
     * split is where the code has to fork, not where the format does.
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
