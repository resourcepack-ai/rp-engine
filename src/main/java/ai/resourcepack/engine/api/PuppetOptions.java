package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * How a puppet is stood up; see {@link Puppets#spawn}.
 *
 * @since 0.2.92
 */
public final class PuppetOptions {

    public static final PuppetOptions DEFAULT = builder().build();

    private final String skin;
    private final Boolean slim;
    private final Set<UUID> audience;
    private final String name;

    private PuppetOptions(Builder b) {
        this.skin = b.skin;
        this.slim = b.slim;
        this.audience = b.audience == null ? null : Set.copyOf(b.audience);
        this.name = b.name;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The skin key, or null for the default rig. */
    public String skin() {
        return skin;
    }

    /** The arm model, or null for whatever the skin was kept with. */
    public Boolean slim() {
        return slim;
    }

    /** Exactly who sees it, or null for everybody. */
    public Set<UUID> audience() {
        return audience;
    }

    /** The name over its head, or null for none. */
    public String name() {
        return name;
    }

    public static final class Builder {
        private String skin;
        private Boolean slim;
        private Set<UUID> audience;
        private String name;

        private Builder() {
        }

        /** A key from {@link Puppets#skin} or {@link Puppets#skinOf}. */
        public Builder skin(String key) {
            this.skin = key == null || key.isBlank() ? null : key;
            return this;
        }

        public Builder slim(Boolean slim) {
            this.slim = slim;
            return this;
        }

        /** Only these players see it. Null (the default) is everybody. */
        public Builder audience(Collection<? extends Player> players) {
            if (players == null) {
                this.audience = null;
                return this;
            }
            Set<UUID> ids = new LinkedHashSet<>();
            for (Player p : players) {
                if (p != null) {
                    ids.add(p.getUniqueId());
                }
            }
            this.audience = ids;
            return this;
        }

        public Builder name(String name) {
            this.name = name == null || name.isEmpty() ? null : name;
            return this;
        }

        public PuppetOptions build() {
            return new PuppetOptions(this);
        }
    }
}
