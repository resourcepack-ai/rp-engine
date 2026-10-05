package ai.resourcepack.engine.core.recipe;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Brewing recipes, through Paper's potion mixes.
 *
 * <p><b>Bukkit has no brewing recipe.</b> A brewing stand's recipes are a
 * table inside the game that no Bukkit API reaches, so a plugin either
 * re-implements brewing on top of the stand's events - the fuel, the timer,
 * the bubbles, the hopper rules, every one of them a place to differ from
 * vanilla - or uses Paper's {@code PotionMix}, which adds a row to the game's
 * own table and leaves brewing to the game. This is the second, and so
 * brewing recipes need Paper. A Spigot server keeps them out and says so once
 * at load, rather than half-brewing.
 *
 * <p>Everything here is reflective. The engine compiles against Spigot so
 * that it runs on Spigot, and one class is not worth a second API on the
 * classpath; the price is that a renamed Paper method shows up as "not
 * available" at load rather than as a compile error, which is the same thing
 * a server owner would see on Spigot.
 */
final class PaperBrewing {

    private final Object brewer;
    private final Constructor<?> mix;
    private final Method add;
    private final Method remove;
    private final Method predicateChoice;

    private PaperBrewing(Object brewer, Constructor<?> mix, Method add, Method remove, Method predicateChoice) {
        this.brewer = brewer;
        this.mix = mix;
        this.add = add;
        this.remove = remove;
        this.predicateChoice = predicateChoice;
    }

    /** Paper's potion brewer, or empty on a server that has none. */
    static Optional<PaperBrewing> find() {
        try {
            Class<?> mixClass = Class.forName("io.papermc.paper.potion.PotionMix");
            Class<?> brewerClass = Class.forName("org.bukkit.potion.PotionBrewer");
            Object brewer = Server.class.getMethod("getPotionBrewer").invoke(Bukkit.getServer());
            if (brewer == null) {
                return Optional.empty();
            }
            Constructor<?> constructor = mixClass.getConstructor(
                    NamespacedKey.class, ItemStack.class, RecipeChoice.class, RecipeChoice.class);
            Method add = brewerClass.getMethod("addPotionMix", mixClass);
            Method remove = brewerClass.getMethod("removePotionMix", NamespacedKey.class);
            Method predicate;
            try {
                predicate = mixClass.getMethod("createPredicateChoice", Predicate.class);
            } catch (NoSuchMethodException e) {
                predicate = null;
            }
            return Optional.of(new PaperBrewing(brewer, constructor, add, remove, predicate));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Adds a mix: {@code base} in the bottle slots and {@code ingredient} on
     * top brew into {@code result}.
     */
    void add(NamespacedKey key, ItemStack result, RecipeItem base, RecipeItem ingredient)
            throws ReflectiveOperationException {
        Object potionMix = mix.newInstance(key, result, choiceOf(base), choiceOf(ingredient));
        add.invoke(brewer, potionMix);
    }

    /** Takes a mix away again; nothing happens if it is not there. */
    void remove(NamespacedKey key) {
        try {
            remove.invoke(brewer, key);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A brewer that cannot remove a mix it was given has nothing
            // left for us to do about it, and a reload must not stop here.
        }
    }

    /**
     * A custom item is matched by its id through a predicate where Paper
     * offers one, so a brewing ingredient that was renamed or stacked from a
     * different source still counts; otherwise the whole stack.
     */
    private RecipeChoice choiceOf(RecipeItem item) throws ReflectiveOperationException {
        if (item.isCustom() && predicateChoice != null) {
            Predicate<ItemStack> test = item::test;
            return (RecipeChoice) predicateChoice.invoke(null, test);
        }
        return item.exact();
    }
}
