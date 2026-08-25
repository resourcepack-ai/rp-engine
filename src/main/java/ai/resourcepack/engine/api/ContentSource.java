package ai.resourcepack.engine.api;

/**
 * Where a namespace's content came from.
 *
 * <p>The engine has more than one front door on purpose. A server owner may
 * hand-author a {@code contents/} folder, or take a compiled pack pushed by
 * ResourcePack AI Studio, or embed content inside their own plugin, and all
 * three land in the same registry with the same id rules. Nothing downstream
 * of the registry is allowed to branch on this: it exists so a load error can
 * say where the bad content came from, and so a reload knows which namespaces
 * it is entitled to replace.
 *
 * <p>Studio is not privileged here. That is the whole point of the enum
 * being flat: the moment one source can do something another cannot, the
 * plugin stops being usable by somebody who has never heard of us.
 */
public enum ContentSource {

    /** Hand-written by the server owner, under the engine's content folder. */
    AUTHORED,

    /** A compiled pack pushed by ResourcePack AI Studio. */
    STUDIO,

    /** Shipped inside a third-party plugin that embeds the engine. */
    EMBEDDED
}
