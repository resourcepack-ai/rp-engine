package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.Packs;
import ai.resourcepack.engine.api.event.PackBuildEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@link Packs}, over what the plugin last built. */
public final class PacksImpl implements Packs {

    /** What the plugin knows and does. */
    public interface Host {

        /** What players are sent, stack order: the bundles, then the rigs pack when it is apart. */
        List<BuiltPack> sent();

        /** The same packs before PackSquash, any order. */
        List<BuiltPack> readable();

        String defaultBundle();

        /** A player's marks changed: send them what they should now hold, if they are here. */
        void marksChanged(UUID player);
    }

    private final Host host;
    private final HeldElsewhere external;
    /** Bundle -> SHA-1, as the last {@link PackBuildEvent} reported them. */
    private Map<String, String> announced = Map.of();

    public PacksImpl(Host host, HeldElsewhere external) {
        this.host = host;
        this.external = external;
    }

    @Override
    public List<BuiltPack> all() {
        return host.sent();
    }

    @Override
    public Optional<BuiltPack> get(String bundle) {
        return find(host.sent(), bundle);
    }

    @Override
    public Optional<BuiltPack> readable(String bundle) {
        Optional<BuiltPack> before = find(host.readable(), bundle);
        return before.isPresent() ? before : get(bundle);
    }

    @Override
    public String defaultBundle() {
        return host.defaultBundle();
    }

    @Override
    public boolean markExternal(UUID player, String bundle) {
        if (!external.mark(player, bundle)) {
            return false;
        }
        host.marksChanged(player);
        return true;
    }

    @Override
    public boolean unmarkExternal(UUID player, String bundle) {
        if (!external.unmark(player, bundle)) {
            return false;
        }
        host.marksChanged(player);
        return true;
    }

    @Override
    public boolean isExternal(UUID player, String bundle) {
        return external.marked(player, bundle);
    }

    /** The event for the packs as they are now, with what changed since the last one. Main thread. */
    public PackBuildEvent rebuilt() {
        List<BuiltPack> now = host.sent();
        Map<String, String> hashes = new HashMap<>();
        for (BuiltPack pack : now) {
            hashes.put(pack.bundle(), pack.sha1());
        }
        Set<String> changed = changed(announced, hashes);
        announced = Map.copyOf(hashes);
        return new PackBuildEvent(now, changed);
    }

    /** Bundles new, gone, or with a different SHA-1 between {@code before} and {@code after}. */
    static Set<String> changed(Map<String, String> before, Map<String, String> after) {
        Set<String> changed = new HashSet<>();
        for (Map.Entry<String, String> entry : after.entrySet()) {
            if (!entry.getValue().equals(before.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        for (String bundle : before.keySet()) {
            if (!after.containsKey(bundle)) {
                changed.add(bundle);
            }
        }
        return changed;
    }

    private static Optional<BuiltPack> find(List<BuiltPack> packs, String bundle) {
        if (bundle == null) {
            return Optional.empty();
        }
        for (BuiltPack pack : packs) {
            if (pack.bundle().equals(bundle)) {
                return Optional.of(pack);
            }
        }
        return Optional.empty();
    }
}
