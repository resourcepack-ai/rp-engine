package ai.resourcepack.engine.api;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What a pack said about something that holds items: a cabinet, a wardrobe,
 * a bin, a crate that keeps its contents when you pick it up.
 *
 * <p><strong>In {@code api} rather than beside the code that opens one</strong>,
 * because {@link ModelInfo} is public and hands this out, and an API class
 * returning something from {@code core} would make {@code core} API by the back
 * door. The parser ({@code core.storage.StorageDefinitions}) and the thing that
 * opens a container ({@code core.storage.Storages}) are both engine internals.
 *
 * <p>Not tied to a placed model. A custom block can hold one too, and both read
 * the same {@code storage:} block with the same parser, so the two can never
 * disagree about what {@code rows: 4} means.
 *
 * <p>Free of Bukkit, so parsing is testable without a server.
 */
public final class StorageSpec {

    /** Rows in a container that does not say. A chest. */
    public static final int DEFAULT_ROWS = 3;

    /** A double chest. The most a container screen can draw. */
    public static final int MAX_ROWS = 6;

    /** The vanilla chest's own sounds, which most containers want. */
    public static final String CHEST_OPEN = "minecraft:block.chest.open";
    public static final String CHEST_CLOSE = "minecraft:block.chest.close";
    public static final String ENDER_OPEN = "minecraft:block.ender_chest.open";
    public static final String ENDER_CLOSE = "minecraft:block.ender_chest.close";

    /**
     * Where the contents live, which is the whole difference between the five.
     */
    public enum Type {

        /**
         * One inventory per placed piece, shared by everybody who opens it.
         * Breaking the piece spills it on the ground, as breaking a chest does.
         */
        CHEST,

        /**
         * One inventory per PLAYER per kind of piece, like an ender chest of
         * its own: every wardrobe of that kind opens the same wardrobe for the
         * same person, and nobody else ever sees into it. Nothing spills when
         * a piece is broken, because nothing in it belongs to the piece.
         */
        PERSONAL,

        /** The player's own vanilla ender chest. Nothing is stored by us at all. */
        ENDERCHEST,

        /**
         * An empty inventory whose contents are destroyed when it closes. A
         * bin. The one type that deletes items, which is what it is for.
         */
        DISPOSAL,

        /**
         * Like a chest, but breaking it gives back the item WITH the contents
         * still inside it, and putting that item down again brings them back.
         * A shulker box, a backpack you set down, a crate.
         */
        SHULKER;

        /** The name the format writes, {@code chest} for {@link #CHEST}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Whether this keeps contents of its own on the thing it is part of. */
        public boolean keepsContents() {
            return this == CHEST || this == SHULKER;
        }
    }

    private final Type type;
    private final int rows;
    private final String title;
    private final String openSound;
    private final String closeSound;

    private StorageSpec(Type type, int rows, String title, String openSound, String closeSound) {
        this.type = type;
        this.rows = rows;
        this.title = title;
        this.openSound = openSound;
        this.closeSound = closeSound;
    }

    /**
     * @param type       null for a chest
     * @param rows       clamped into 1&ndash;6
     * @param title      null or blank for whatever the holder is called
     * @param openSound  null for the type's own; empty for silence
     * @param closeSound null for the type's own; empty for silence
     */
    public static StorageSpec of(Type type, int rows, String title, String openSound, String closeSound) {
        Type resolved = type == null ? Type.CHEST : type;
        return new StorageSpec(resolved,
                Math.max(1, Math.min(MAX_ROWS, rows)),
                title == null || title.isBlank() ? null : title,
                openSound == null ? defaultOpen(resolved) : openSound,
                closeSound == null ? defaultClose(resolved) : closeSound);
    }

    /** A plain three-row chest. What {@code storage: true} means. */
    public static StorageSpec chest() {
        return of(Type.CHEST, DEFAULT_ROWS, null, null, null);
    }

    private static String defaultOpen(Type type) {
        switch (type) {
            case ENDERCHEST:
                return ENDER_OPEN;
            case DISPOSAL:
                // A bin is not a chest, and the chest's creak on something
                // that is about to eat your items reads as the wrong promise.
                return "";
            default:
                return CHEST_OPEN;
        }
    }

    private static String defaultClose(Type type) {
        switch (type) {
            case ENDERCHEST:
                return ENDER_CLOSE;
            case DISPOSAL:
                return "";
            default:
                return CHEST_CLOSE;
        }
    }

    /** Where its contents live. */
    public Type type() {
        return type;
    }

    /**
     * Rows of nine, 1&ndash;6.
     *
     * <p>Meaningless for an {@link Type#ENDERCHEST}, which is whatever size the
     * game's own ender chest is.
     */
    public int rows() {
        return rows;
    }

    /** Slots, which is rows times nine. */
    public int size() {
        return rows * 9;
    }

    /**
     * What the screen is called, {@code &} colour codes and all, or empty for
     * the name of whatever it is part of.
     */
    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    /** The sound it makes opening, or empty for none. A vanilla key or one of the pack's own. */
    public Optional<String> openSound() {
        return openSound.isEmpty() ? Optional.empty() : Optional.of(openSound);
    }

    /** The sound it makes when the last person closes it, or empty for none. */
    public Optional<String> closeSound() {
        return closeSound.isEmpty() ? Optional.empty() : Optional.of(closeSound);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof StorageSpec)) {
            return false;
        }
        StorageSpec that = (StorageSpec) other;
        return type == that.type && rows == that.rows && Objects.equals(title, that.title)
                && openSound.equals(that.openSound) && closeSound.equals(that.closeSound);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, rows, title, openSound, closeSound);
    }

    @Override
    public String toString() {
        return type.key() + " x" + rows;
    }
}
