package ai.resourcepack.engine.core.model;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * What a potion and a reagent brew into, asked of the game rather than written
 * down here, so a datapack's or a plugin's brewing recipes count.
 *
 * <p><strong>Two ways of asking, because the game changed.</strong> From 26.1
 * brewing is data recipes the game looks up from a potion and a reagent. Before
 * that it keeps a table of its own. Neither has a Bukkit API that can answer:
 * 26.x's Bukkit lists the recipes but drops which potion each one takes, so a
 * nether wart reads as brewing with an Awkward Potion for ever. So the game's
 * classes are asked by name, which works where those names are Mojang's: every
 * 26.x server and Paper from 1.20.5. Everywhere else {@link #find} says there is
 * no answer rather than guess at a table of its own.
 */
interface BrewingRules {

    /** What {@code bottle} becomes with {@code reagent}, or null when they make nothing. */
    ItemStack mix(ItemStack bottle, ItemStack reagent);

    /** Whether the brewing screen wants its totals sent too, as it has since brewing became data. */
    boolean sendsTotals();

    /** The rules for this server, or null when it cannot say what brews into what. */
    static BrewingRules find() {
        BrewingRules data = DataRecipes.find();
        return data != null ? data : GameTable.find();
    }

    /** Going between Bukkit's item stacks and the game's. */
    final class Stacks {

        final Class<?> game;
        private final Method toGame;
        private final Method toBukkit;

        private Stacks(Class<?> game, Method toGame, Method toBukkit) {
            this.game = game;
            this.toGame = toGame;
            this.toBukkit = toBukkit;
        }

        static Stacks find() throws ReflectiveOperationException {
            Class<?> game = Class.forName("net.minecraft.world.item.ItemStack");
            Class<?> craft = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            // By name alone: its parameter became an interface in 26.x.
            for (Method method : craft.getMethods()) {
                if (method.getName().equals("asBukkitCopy") && method.getParameterCount() == 1) {
                    return new Stacks(game, craft.getMethod("asNMSCopy", ItemStack.class), method);
                }
            }
            throw new NoSuchMethodException("asBukkitCopy");
        }

        Object toGame(ItemStack stack) throws ReflectiveOperationException {
            return toGame.invoke(null, stack);
        }

        ItemStack toBukkit(Object stack) throws ReflectiveOperationException {
            return (ItemStack) toBukkit.invoke(null, stack);
        }

        /** The game's server object. */
        static Object server() throws ReflectiveOperationException {
            return Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        }
    }

    /** The game's data recipes, from 26.1. */
    final class DataRecipes implements BrewingRules {

        private final Stacks stacks;
        private final Method overworld;
        private final Method recipeAccess;
        private final Object type;
        private final Constructor<?> input;
        private final Method lookup;
        private final Method value;
        private final Method assemble;

        private DataRecipes(Stacks stacks, Method overworld, Method recipeAccess, Object type, Constructor<?> input,
                            Method lookup, Method value, Method assemble) {
            this.stacks = stacks;
            this.overworld = overworld;
            this.recipeAccess = recipeAccess;
            this.type = type;
            this.input = input;
            this.lookup = lookup;
            this.value = value;
            this.assemble = assemble;
        }

        /** Only finds the methods. The worlds do not exist yet when this first runs. */
        static DataRecipes find() {
            try {
                Stacks stacks = Stacks.find();
                Class<?> inputClass = Class.forName("net.minecraft.world.item.crafting.BrewingInput");
                Class<?> recipeInput = Class.forName("net.minecraft.world.item.crafting.RecipeInput");
                Class<?> typeClass = Class.forName("net.minecraft.world.item.crafting.RecipeType");
                Class<?> levelClass = Class.forName("net.minecraft.world.level.Level");
                Class<?> manager = Class.forName("net.minecraft.world.item.crafting.RecipeManager");
                return new DataRecipes(stacks,
                        Class.forName("net.minecraft.server.MinecraftServer").getMethod("overworld"),
                        Class.forName("net.minecraft.server.level.ServerLevel").getMethod("recipeAccess"),
                        typeClass.getField("BREWING").get(null),
                        inputClass.getConstructor(stacks.game, stacks.game),
                        manager.getMethod("getRecipeFor", typeClass, recipeInput, levelClass),
                        Class.forName("net.minecraft.world.item.crafting.RecipeHolder").getMethod("value"),
                        Class.forName("net.minecraft.world.item.crafting.BrewingRecipe")
                                .getMethod("assemble", inputClass));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                return null;
            }
        }

        @Override
        public ItemStack mix(ItemStack bottle, ItemStack reagent) {
            try {
                Object made = input.newInstance(stacks.toGame(bottle), stacks.toGame(reagent));
                Object level = overworld.invoke(Stacks.server());
                Optional<?> holder = (Optional<?>) lookup.invoke(recipeAccess.invoke(level), type, made, level);
                if (holder.isEmpty()) {
                    return null;
                }
                return stacks.toBukkit(assemble.invoke(value.invoke(holder.get()), made));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean sendsTotals() {
            return true;
        }
    }

    /** The game's own table, on Paper before brewing became data. */
    final class GameTable implements BrewingRules {

        private final Stacks stacks;
        private final Method potionBrewing;
        private final Method hasMix;
        private final Method mix;

        private GameTable(Stacks stacks, Method potionBrewing, Method hasMix, Method mix) {
            this.stacks = stacks;
            this.potionBrewing = potionBrewing;
            this.hasMix = hasMix;
            this.mix = mix;
        }

        /** Only finds the methods. The table does not exist yet when this first runs. */
        static GameTable find() {
            try {
                Stacks stacks = Stacks.find();
                Class<?> table = Class.forName("net.minecraft.world.item.alchemy.PotionBrewing");
                return new GameTable(stacks,
                        Class.forName("net.minecraft.server.MinecraftServer").getMethod("potionBrewing"),
                        table.getMethod("hasMix", stacks.game, stacks.game),
                        table.getMethod("mix", stacks.game, stacks.game));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                return null;
            }
        }

        @Override
        public ItemStack mix(ItemStack bottle, ItemStack reagent) {
            try {
                Object brewing = potionBrewing.invoke(Stacks.server());
                Object potion = stacks.toGame(bottle);
                Object ingredient = stacks.toGame(reagent);
                if (!Boolean.TRUE.equals(hasMix.invoke(brewing, potion, ingredient))) {
                    return null;
                }
                return stacks.toBukkit(mix.invoke(brewing, ingredient, potion));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean sendsTotals() {
            return false;
        }
    }
}
