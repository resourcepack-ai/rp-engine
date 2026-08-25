package ai.resourcepack.engine.api;

import java.util.Optional;

/**
 * The outcome of asking for a namespace.
 *
 * <p>A result rather than an exception or a bare null, because a claim fails
 * in several genuinely different ways and the caller writes the sentence. Two
 * content packs picking the same name is an ordinary mistake a server owner
 * makes, and the console line that explains it has to name both sources —
 * which only this type knows.
 */
public final class ClaimResult {

    /** Why a claim did or did not succeed. */
    public enum Reason {

        /** The namespace is now owned by the caller. */
        CLAIMED,

        /** Somebody else already owns it. {@link #heldBy()} says who. */
        ALREADY_CLAIMED,

        /** Not a legal namespace: see {@link ContentId#isValidNamespace}. */
        INVALID,

        /** A namespace the client already means something by. */
        RESERVED
    }

    private final Reason reason;
    private final Namespace namespace;
    private final ContentSource heldBy;

    private ClaimResult(Reason reason, Namespace namespace, ContentSource heldBy) {
        this.reason = reason;
        this.namespace = namespace;
        this.heldBy = heldBy;
    }

    /** Engine internal. */
    public static ClaimResult claimed(Namespace namespace) {
        return new ClaimResult(Reason.CLAIMED, namespace, namespace.source());
    }

    public static ClaimResult alreadyClaimed(ContentSource heldBy) {
        return new ClaimResult(Reason.ALREADY_CLAIMED, null, heldBy);
    }

    public static ClaimResult refused(Reason reason) {
        return new ClaimResult(reason, null, null);
    }

    /** Why the claim ended the way it did. */
    public Reason reason() {
        return reason;
    }

    /** Whether the caller now owns the namespace. */
    public boolean success() {
        return reason == Reason.CLAIMED;
    }

    /** The handle, present only when {@link #success()}. */
    public Optional<Namespace> namespace() {
        return Optional.ofNullable(namespace);
    }

    /**
     * The source that owns the namespace, present when it was claimed here or
     * was already held by somebody else. This is the half of a collision
     * message that is worth reading.
     */
    public Optional<ContentSource> heldBy() {
        return Optional.ofNullable(heldBy);
    }
}
