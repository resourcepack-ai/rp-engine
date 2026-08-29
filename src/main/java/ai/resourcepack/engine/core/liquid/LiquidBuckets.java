package ai.resourcepack.engine.core.liquid;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LiquidInfo;
import ai.resourcepack.engine.core.command.EngineCommand;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * An item that puts a liquid down: {@code liquid: mypack:acid} on a bucket.
 *
 * <p>The other way of making a pool — {@code /rp liquid corner} and
 * {@code fill} — marks out a volume that is already full of water. This one is
 * for building the pond in the first place, one block at a time, which is what
 * somebody decorating actually does. They are the same pool underneath: a
 * placed block joins the pool it is touching, so a pond built with fifty
 * clicks carries one rule, not fifty.
 *
 * <p>What it places is a real vanilla source block, so it flows, and the water
 * that runs out of the box is ordinary water — colour and rules stop at the
 * pool, and a builder who wants a still pond digs a basin the way they would
 * for vanilla water.
 */
public final class LiquidBuckets {

    private final Liquids liquids;
    private final LiquidPools pools;
    private final LiquidBiomes biomes;
    private final Logger log;

    public LiquidBuckets(Liquids liquids, LiquidPools pools, LiquidBiomes biomes, Logger log) {
        this.liquids = liquids;
        this.pools = pools;
        this.biomes = biomes;
        this.log = log;
    }

    /**
     * Places {@code item}'s liquid against a clicked block.
     *
     * @return whether this was a bucket and the placement happened, so the
     *         caller can cancel the click that caused it
     */
    public boolean place(Player player, ItemInfo item, ItemStack stack, Block clicked, BlockFace face) {
        Optional<ContentId> id = item.liquid();
        if (id.isEmpty() || clicked == null || face == null) {
            return false;
        }
        Optional<LiquidInfo> liquid = liquids.info(id.get());
        if (liquid.isEmpty()) {
            // A bucket of something this server does not have. Said out loud
            // rather than silently doing nothing, because the pack is what is
            // wrong and the builder is the one who can fix it.
            say(player, "There is no liquid called " + id.get() + " on this server.");
            return true;
        }

        Block target = clicked.getType().isSolid() ? clicked.getRelative(face) : clicked;
        if (!target.getType().isAir() && !target.isLiquid()) {
            return false;
        }

        Material fluid = liquid.get().base() == LiquidInfo.Base.LAVA ? Material.LAVA : Material.WATER;
        BlockState before = target.getState();
        target.setType(fluid, true);

        // Fired so every protection plugin on the server gets its say, since
        // none of them know what this item is. Put back rather than left
        // standing if one of them says no: the block is already placed by the
        // time the question can be asked.
        BlockPlaceEvent placed = new BlockPlaceEvent(target, before, clicked, stack, player, true,
                EquipmentSlot.HAND);
        player.getServer().getPluginManager().callEvent(placed);
        if (placed.isCancelled() || !placed.canBuild()) {
            before.update(true, false);
            return true;
        }

        LiquidPools.Pool pool = pools.addBlock(id.get(), target.getWorld().getName(),
                target.getX(), target.getY(), target.getZ());
        biomes.paint(target.getWorld(), pool.min()[0], pool.min()[1], pool.min()[2],
                        pool.max()[0], pool.max()[1], pool.max()[2], id.get())
                .ifPresent(was -> pool.remember(was.getKey().toString()));
        pools.save(log);

        if (liquid.get().color().isPresent() && biomes.biomeOf(id.get()).isEmpty()) {
            say(player, "That liquid has a colour the server has not loaded yet. "
                    + "It needs a restart; the pool works either way.");
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            stack.setAmount(stack.getAmount() - 1);
        }
        return true;
    }

    /**
     * One prefixed line, the way the handful of other classes outside the
     * command package do it.
     */
    private static void say(Player player, String line) {
        player.sendMessage(EngineCommand.prefix() + line);
    }
}
