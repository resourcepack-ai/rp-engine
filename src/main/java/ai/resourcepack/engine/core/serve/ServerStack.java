package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;

import java.util.ArrayList;
import java.util.List;

/**
 * The packs one player should hold from this server, in stack order, bottom
 * first.
 *
 * <p>Free of Bukkit, so the order is testable. From the bottom:
 *
 * <ol>
 *   <li>the server's bundle, which is everything the content folder built;</li>
 *   <li>the emote rigs, when they are a pack of their own (where packs stack)
 *       rather than files inside the bundle. Above the bundle so the bundle
 *       never has to change when a new skin arrives;</li>
 *   <li>the player's own rig, while the rigs pack under it does not have them
 *       yet. It replaces the rigs' shared item definitions with a copy that
 *       also names this player, so it has to sit above the rigs;</li>
 *   <li>a pack pushed to this player, which is tried against everything the
 *       server itself gives them.</li>
 * </ol>
 *
 * <p>Nothing of the server's is sent without its bundle: the rigs and a
 * player's own rig are made to sit on it, and a server with no bundle to send
 * sends nothing of its own.
 */
public final class ServerStack {

    private ServerStack() {
    }

    /**
     * @param main   the server's bundle, or null when there is none to send
     * @param rigs   the rigs pack, or null when rigs ride inside the bundle
     * @param ownRig the player's own rig pack, or null when they need none
     * @param pushed a pack pushed to the player, or null
     */
    public static List<BuiltPack> of(BuiltPack main, BuiltPack rigs, BuiltPack ownRig, BuiltPack pushed) {
        List<BuiltPack> stack = new ArrayList<>(4);
        if (main != null) {
            stack.add(main);
            if (rigs != null) {
                stack.add(rigs);
            }
            if (ownRig != null) {
                stack.add(ownRig);
            }
        }
        if (pushed != null) {
            stack.add(pushed);
        }
        return List.copyOf(stack);
    }

    /**
     * {@code stack} with the merged-shader pack on top of it, above even a
     * push: it holds the one copy of the text shader both ours and another
     * plugin's need, and a core shader is read from the top pack that has it.
     * Nothing to add is the stack unchanged.
     */
    public static List<BuiltPack> withShaders(List<BuiltPack> stack, BuiltPack shaders) {
        if (shaders == null || stack.isEmpty()) {
            return stack;
        }
        List<BuiltPack> out = new ArrayList<>(stack);
        out.add(shaders);
        return List.copyOf(out);
    }
}
