package ai.resourcepack.engine.api;

import java.util.Locale;
import java.util.Optional;

/**
 * What puts an overlay on screen without anybody writing code.
 *
 * <p><b>A fixed vocabulary, not an expression language.</b> Each kind is a
 * question this engine can answer about a player from what it already knows, so
 * a rule is cheap to evaluate and impossible to write in a way that never
 * fires. Anything outside the list is {@link Overlays#show}, which is the other
 * door and is always open.
 *
 * <p>Studio's {@code lib/shaders/types.ts} is the other end of this and there is
 * no shared type between them. The wire names are what agree; a rename here is
 * a trigger that silently stops firing.
 */
public final class OverlayTrigger {

    public enum Kind {

        /** Shown once, as they join. Stays until something hides it. */
        JOIN("join"),

        /** Kept on for as long as they are online. */
        ALWAYS("always"),

        /** While they are crouching. */
        SNEAK("sneak"),

        /** While they are holding {@link OverlayTrigger#item()}. */
        HOLDING("holding");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** The kind a manifest named, or empty if it named one we do not have. */
        public static Optional<Kind> of(String wire) {
            if (wire == null) {
                return Optional.empty();
            }
            String want = wire.trim().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.wire.equals(want)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    private final Kind kind;
    private final String item;
    private final String permission;

    private OverlayTrigger(Kind kind, String item, String permission) {
        this.kind = kind;
        this.item = item == null ? "" : item;
        this.permission = permission == null ? "" : permission;
    }

    public static OverlayTrigger of(Kind kind, String item, String permission) {
        return new OverlayTrigger(kind, item, permission);
    }

    public Kind kind() {
        return kind;
    }

    /**
     * The item {@link Kind#HOLDING} watches for. Empty for every other kind.
     *
     * <p>A material name or a content id. A {@code HOLDING} trigger with no item
     * would mean "while holding anything", which nobody means, so Studio drops
     * one rather than sending it.
     */
    public String item() {
        return item;
    }

    /**
     * A permission that must also hold, or empty.
     *
     * <p>A gate on whichever kind this is, rather than a kind of its own: "show
     * this to admins while they crouch" is one rule, and a separate permission
     * kind would have been two rules that must agree.
     */
    public String permission() {
        return permission;
    }

    /**
     * Whether this kind describes a STATE rather than a moment.
     *
     * <p>The difference decides what happens when it stops holding. A state
     * trigger takes its overlay off again — stop crouching and the overlay
     * goes — while a moment trigger has nothing to stop being true, so what it
     * showed stays until something hides it. {@link Kind#JOIN} is the only
     * moment, and that is why joining and then being shown something else does
     * not fight.
     */
    public boolean isState() {
        return kind != Kind.JOIN;
    }

    @Override
    public String toString() {
        return kind.wire() + (item.isEmpty() ? "" : " " + item)
                + (permission.isEmpty() ? "" : " (" + permission + ")");
    }
}
