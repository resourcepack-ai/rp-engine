package ai.resourcepack.engine.api;

import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Optional;

/**
 * The custom items this server holds.
 *
 * <p>Reads ({@link #ids}, {@link #info}) ask what the server HOLDS and are safe
 * from any thread. Everything that touches an {@link ItemStack} is main-thread
 * only, because Bukkit's item factory is.
 *
 * <p>Nothing here returns null and nothing throws on a null argument: an id out
 * of somebody's config should answer empty rather than a stack trace.
 */
public interface Items {

    /** Every custom item id, sorted. */
    Collection<ContentId> ids();

    /** What the pack said an item is, or empty if there is no such item. */
    Optional<ItemInfo> info(ContentId id);

    /** As {@link #info(ContentId)}, from the text form of an id. */
    Optional<ItemInfo> info(String id);

    /**
     * Builds one of them.
     *
     * @return the stack, or empty if there is no such item. Main thread only.
     */
    Optional<ItemStack> create(ContentId id);

    /** Builds {@code amount} of them. Main thread only. */
    Optional<ItemStack> create(ContentId id, int amount);

    /**
     * Which custom item a stack is, or empty if it is an ordinary one.
     *
     * <p>Read from the stack's persistent data rather than inferred from its
     * model or its name, so an item somebody renamed in an anvil is still
     * itself, and a vanilla item somebody named "Ruby" is still not.
     */
    Optional<ContentId> idOf(ItemStack stack);

    /** Whether {@code stack} is the custom item {@code id}. */
    boolean is(ItemStack stack, ContentId id);

    /**
     * Points {@code stack} at the model belonging to {@code modelId}, however
     * this server's Minecraft addresses models.
     *
     * <p>For building a stack the engine did not create: one part of a rig,
     * or a stack of your own that should wear a pack's art. It changes only
     * the model — not the item's identity, which stays whatever the stack
     * already was.
     *
     * <p><b>Use this rather than setting the model yourself.</b> On 1.21.4 and
     * up a model is named by the {@code item_model} component; below that it
     * is chosen by a number that has to be allocated and remembered, and the
     * number this returns is the one the built pack was written against.
     * Calling {@code setItemModel} directly works on a current server and
     * silently produces an unmodelled item on an older one.
     *
     * @return whether the model was applied; false if the stack has no meta
     */
    boolean wearModel(ItemStack stack, ContentId modelId);

    /**
     * Which part fills each slot of an item made of parts ({@code parts:} in
     * its definition), in slot order.
     *
     * <p>A slot the stack has never been given a part for holds that slot's
     * first option, which is also what it is drawn with, so every slot always
     * has an answer. Empty for a stack that is not an item made of parts.
     */
    java.util.Map<String, ContentId> partsOf(ItemStack stack);

    /**
     * A copy of {@code stack} with some of its parts changed; slots not named
     * keep what they had. How a crafting recipe or a plugin's own workbench
     * puts a tool together:
     *
     * <pre>{@code
     * items.create(ContentId.of("tools", "pickaxe").get())
     *     .flatMap(pick -> items.withParts(pick, Map.of(
     *         "head", ContentId.of("tools", "gold_head").get(),
     *         "handle", ContentId.of("tools", "birch_handle").get())));
     * }</pre>
     *
     * Only the look and this record change: the stack's name, lore, damage
     * and everything else are yours to set to match.
     *
     * @return the new stack, or empty if {@code stack} is not an item made of
     *         parts, or a slot is not one of its own, or a part is not one of
     *         that slot's options. Main thread only.
     */
    Optional<ItemStack> withParts(ItemStack stack, java.util.Map<String, ContentId> parts);
}
