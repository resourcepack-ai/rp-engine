package ai.resourcepack.engine.api;

/**
 * What a piece of content is.
 *
 * <p>One flat enum rather than a class hierarchy, because the registry's job
 * is to answer "what exists" for every kind uniformly and the kinds do not
 * share behaviour, only an id. Behaviour lives on the per-kind service
 * ({@code Items}, {@code Blocks}, {@code Models}, ...) that reads this
 * registry.
 *
 * <p><strong>This enum only grows.</strong> A constant is never removed or
 * renamed once released: the ordinal is not persisted anywhere, but the name
 * appears in every authored YAML file on every server that runs this, and a
 * rename turns those into load errors on somebody else's machine.
 */
public enum ContentKind {

    /** A custom item: a base material plus an {@code item_model}. */
    ITEM,

    /** A custom block. */
    BLOCK,

    /** A display-entity prop with a hitbox. */
    FURNITURE,

    /** A model, animated or still, that can be placed or bound to an entity. */
    MODEL,

    /** A player emote. */
    EMOTE,

    /** A sound shipped by the pack. */
    SOUND,

    /** A font, and the glyphs it carries. */
    FONT,

    /** A full-screen GUI background. */
    SCREEN,

    /** A HUD overlay, including meters with fill steps. */
    HUD,

    /** A crafting, cooking or stonecutting recipe. */
    RECIPE
}
