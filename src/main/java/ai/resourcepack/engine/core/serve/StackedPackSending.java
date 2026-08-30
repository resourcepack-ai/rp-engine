package ai.resourcepack.engine.core.serve;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 1.20.3 and up: packs have ids and a player can hold several.
 */
final class StackedPackSending implements PackSending {

    @Override
    public boolean stacks() {
        return true;
    }

    @Override
    public void send(Player player, UUID id, String url, byte[] sha1, String prompt, boolean force) {
        player.addResourcePack(id, url, sha1, prompt, force);
    }

    @Override
    public void remove(Player player, UUID id) {
        player.removeResourcePack(id);
    }
}
