package ai.resourcepack.engine.core.serve;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Below 1.20.3: one slot, and sending a pack replaces whatever was in it.
 *
 * <p>The id is accepted and dropped. That is not sloppiness — the caller
 * genuinely has one, and having this arm take it keeps the fact that it cannot
 * be used in one place instead of at every call site.
 */
final class SinglePackSending implements PackSending {

    @Override
    public boolean stacks() {
        return false;
    }

    @Override
    public void send(Player player, UUID id, String url, byte[] sha1, String prompt, boolean force) {
        player.setResourcePack(url, sha1, prompt, force);
    }

    @Override
    public void remove(Player player, UUID id) {
        // Nothing to remove by. There is one slot, and the only way to empty
        // it is to fill it with something else, which is what the caller's
        // next send does. Removing "everything" here would take a pack the
        // caller still wants off the player.
    }
}
