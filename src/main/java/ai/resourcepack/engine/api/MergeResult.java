package ai.resourcepack.engine.api;

/**
 * What happened when a manifest was merged in.
 *
 * <p>A result rather than an exception, on the same terms as every other load
 * here: a manifest that fails to arrive or fails to parse is an ordinary
 * Tuesday — an expired signed URL, a truncated download, a studio newer than
 * this jar — and not a bug in whoever asked. The caller writes the sentence.
 */
public final class MergeResult {

    private final boolean ok;
    private final String packId;
    private final int count;
    private final String error;

    private MergeResult(boolean ok, String packId, int count, String error) {
        this.ok = ok;
        this.packId = packId;
        this.count = count;
        this.error = error;
    }

    /** It merged, and brought {@code count} things with it. */
    public static MergeResult ok(String packId, int count) {
        return new MergeResult(true, packId, count, null);
    }

    /** It did not, for a reason a server owner can read. */
    public static MergeResult failed(String error) {
        return new MergeResult(false, null, 0, error);
    }

    /** Whether anything was merged. */
    public boolean ok() {
        return ok;
    }

    /** Which pack it belonged to, or null when it failed. */
    public String packId() {
        return packId;
    }

    /** How many entries it carried. */
    public int count() {
        return count;
    }

    /** Why it failed, or null when it did not. */
    public String error() {
        return error;
    }

    @Override
    public String toString() {
        return ok ? count + " from " + packId : "failed: " + error;
    }
}
