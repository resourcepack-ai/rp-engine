package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.core.version.Compatibility;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * The little list of strings a rig's part item carries: which part it is,
 * which animation it was given, and how big it was built.
 *
 * <p>On 1.21.4 these ride in {@code custom_model_data}, which became a
 * component holding four parallel lists — one of them strings. That is not a
 * rendering use: only index 0 does anything to the model, and the rest are
 * markers found by prefix. It is a data channel that happens to be visible to
 * a hand-written {@code /give}, which is why studio's panel can produce a rig
 * part without this plugin's help.
 *
 * <p>Below 1.21.4 that list does not exist — {@code custom_model_data} is a
 * single number there — so the same values go into the stack's persistent
 * data. That is older, stricter and frankly better; the one thing lost is that
 * a person cannot type a rig part into existence with a {@code /give} command.
 *
 * <p><b>Reading is more permissive than writing.</b> The modern arm falls back
 * to persistent data when it finds no strings, because a server that upgrades
 * past 1.21.4 has part items in the world written by the older arm and they
 * have to keep working. The other direction is not covered and is not worth
 * covering: a downgrade changes the pack format underneath every item anyway.
 *
 * @see Feature#ITEM_STRING_TAGS
 */
public interface RigTags {

    /**
     * The tags on a stack, in order. Index 0 is the part id where there is
     * one; everything after it is a prefixed marker.
     *
     * @return the tags, or an empty list — never null
     */
    List<String> read(ItemStack item);

    /** Puts {@code tags} on a stack's meta. The caller applies the meta. */
    void write(ItemMeta meta, List<String> tags);

    /**
     * Reads nothing and writes nothing.
     *
     * <p>The value the static holders start on, so that a stack inspected
     * before the plugin has resolved its compatibility answers "no tags"
     * rather than throwing. Not a fallback anything should run on: a rig part
     * built through this carries no identity and will not place.
     */
    RigTags NONE = new RigTags() {

        @Override
        public List<String> read(ItemStack item) {
            return java.util.Collections.emptyList();
        }

        @Override
        public void write(ItemMeta meta, List<String> tags) {
        }
    };

    static RigTags forServer(Compatibility compatibility, Plugin plugin) {
        PersistentRigTags persistent = new PersistentRigTags(plugin);
        return compatibility.has(Feature.ITEM_STRING_TAGS)
                ? new StringRigTags(persistent)
                : persistent;
    }
}
