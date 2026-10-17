package ai.resourcepack.engine.core.recipe;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.block.BlockState;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * {@link VanillaRecipeGuard}'s rule for the crafter, the block that crafts on
 * a redstone pulse. It never opens a crafting screen, so it fires an event of
 * its own rather than the one the guard listens to, and without this three
 * custom tomatoes hoppered into one still make a book.
 *
 * <p>A class of its own because the crafter and its event are 1.21 API: it is
 * constructed only when {@code Feature.CRAFTER} says the server has them, and
 * never loaded on a server that does not.
 */
final class CrafterGuard implements Listener {

    private final VanillaRecipeGuard guard;

    CrafterGuard(VanillaRecipeGuard guard) {
        this.guard = guard;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (guard.allowsAll() || !VanillaRecipeGuard.isVanilla(event.getRecipe())) {
            return;
        }
        BlockState state = event.getBlock().getState();
        if (!(state instanceof Crafter)) {
            return;
        }
        Optional<ContentId> made = guard.idOf(event.getResult());
        for (ItemStack input : ((Crafter) state).getInventory().getContents()) {
            if (guard.substitutes(input, made)) {
                // The crafter clicks and nothing comes out, its grid untouched.
                event.setCancelled(true);
                return;
            }
        }
    }
}
