package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A scoreboard: the sidebar on the right of the screen, as a pack wrote it.
 *
 * <p>The title and lines are the text as written, placeholders and colour
 * codes included; what a player sees is that text filled in for them, which
 * is why none of it is here.
 */
public final class ScoreboardInfo {

    private final ContentId id;
    private final String title;
    private final List<String> lines;
    private final boolean background;
    private final int update;
    private final boolean automatic;
    private final List<String> worlds;
    private final String permission;
    private final int priority;

    private ScoreboardInfo(ContentId id, String title, List<String> lines, boolean background, int update,
                           boolean automatic, List<String> worlds, String permission, int priority) {
        this.id = id;
        this.title = title;
        this.lines = lines;
        this.background = background;
        this.update = update;
        this.automatic = automatic;
        this.worlds = worlds;
        this.permission = permission;
        this.priority = priority;
    }

    /** Engine internal; built by the scoreboard loader. */
    public static ScoreboardInfo of(ContentId id, String title, List<String> lines, boolean background, int update,
                                    boolean automatic, List<String> worlds, String permission, int priority) {
        return new ScoreboardInfo(Objects.requireNonNull(id, "id"), title == null ? "" : title,
                List.copyOf(lines), background, Math.max(1, update), automatic, List.copyOf(worlds),
                permission == null || permission.isBlank() ? null : permission.trim(), priority);
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /** The title as written. Empty when it has none. */
    public String title() {
        return title;
    }

    /** The lines as written, top to bottom. At most fifteen, which is all the game draws. */
    public List<String> lines() {
        return lines;
    }

    /** Whether the pack draws a panel behind it, in place of the game's own grey box. */
    public boolean background() {
        return background;
    }

    /** How often it is filled in again for each player, in ticks. */
    public int update() {
        return update;
    }

    /**
     * Whether players are shown it without anybody asking: in its
     * {@link #worlds}, with its {@link #permission}. One that is not is shown
     * only by {@link Scoreboards#show} or {@code /rp scoreboard show}.
     */
    public boolean automatic() {
        return automatic;
    }

    /** The worlds it is shown in by itself. Empty means every world. */
    public List<String> worlds() {
        return worlds;
    }

    /** The permission a player needs to be shown it by itself, if it asks for one. */
    public Optional<String> permission() {
        return Optional.ofNullable(permission);
    }

    /** Which is shown where a player could be shown several: the higher. */
    public int priority() {
        return priority;
    }

    @Override
    public String toString() {
        return id + " (" + lines.size() + " lines)";
    }
}
