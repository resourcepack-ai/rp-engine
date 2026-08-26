package ai.resourcepack.engine.core.skin;

import ai.resourcepack.engine.api.SkinResult;
import ai.resourcepack.engine.api.Skins;
import ai.resourcepack.engine.core.Host;

import org.bukkit.entity.Player;

/**
 * The public {@link Skins} surface over {@link SkinApplier}.
 *
 * <p>All it adds is the thread check and the translation from the applier's
 * reason strings to a typed result - the reflection, and the reasoning behind
 * it, stay in one place.
 *
 * <p>Internal. Not part of the supported API.
 */
public final class SkinsImpl implements Skins {

    private final SkinApplier applier;

    public SkinsImpl(SkinApplier applier) {
        this.applier = applier;
    }

    @Override
    public boolean available() {
        return applier.isAvailable();
    }

    @Override
    public SkinResult apply(Player target, String value, String signature) {
        Host.requireMainThread();
        if (target == null || !target.isOnline()) return SkinResult.FAILED;
        if (value == null || value.isEmpty() || signature == null || signature.isEmpty()) {
            return SkinResult.INVALID;
        }
        SkinApplier.Result result = applier.apply(target, value, signature);
        if (result.ok) return SkinResult.APPLIED;
        return "needs-paper".equals(result.reason) ? SkinResult.NEEDS_PAPER : SkinResult.FAILED;
    }
}
