package ai.resourcepack.engine.api;

import java.util.Objects;

/**
 * A dialog — the data-driven screen a server opens on a client, added in
 * Minecraft 1.21.6.
 *
 * <p><strong>The engine does not model what is on it.</strong> A dialog is
 * defined by a JSON object whose schema is the game's, moves with the game, and
 * is read by the client rather than by us — so this carries that object as
 * TEXT and hands it to the game unread. Everything the engine does with a
 * dialog it can do without knowing whether the thing has three buttons or a
 * slider on it: write the file, and name it in a command.
 *
 * <p>That is deliberate rather than lazy. The alternative is a second
 * implementation of Minecraft's dialog codec living here, drifting from the
 * real one release by release, and turning every new field into a plugin
 * update before anybody can use it. What the engine adds is the part the game
 * does not: a NAME. A dialog in a datapack is addressed by a namespaced id
 * somebody has to type; one registered here is {@code mypack:shop}, listed by
 * {@code /rp dialogs} and openable from the API like any other content.
 *
 * <p>Two things about a dialog are worth knowing from outside this class.
 * A dialog is <em>registry</em> data, so it reaches a client through a
 * datapack rather than a resource pack — see the dialog datapack writer for
 * what that costs. And a pack drawn in Studio puts its picture in the dialog's
 * body as a font glyph, which means the art is in the resource pack and the
 * dialog is not: a player who has one without the other sees a screen with the
 * right buttons and no picture, or a picture with nothing to draw it in.
 */
public final class DialogInfo {

    private final ContentId id;
    private final String json;
    private final String name;
    private final boolean pushed;

    private DialogInfo(ContentId id, String json, String name, boolean pushed) {
        this.id = Objects.requireNonNull(id, "id");
        this.json = json == null ? "{}" : json;
        this.name = name == null || name.isEmpty() ? id.path() : name;
        this.pushed = pushed;
    }

    /** One loaded from a content folder. */
    public static DialogInfo authored(ContentId id, String json, String name) {
        return new DialogInfo(id, json, name, false);
    }

    /** One that arrived with a pushed Studio pack. */
    public static DialogInfo pushed(ContentId id, String json, String name) {
        return new DialogInfo(id, json, name, true);
    }

    public ContentId id() {
        return id;
    }

    /** The {@code minecraft:dialog} object, as JSON. Written out verbatim. */
    public String json() {
        return json;
    }

    /** What to call it in a listing. */
    public String name() {
        return name;
    }

    /**
     * Whether this came from a pushed Studio pack rather than from the server's
     * own content folder.
     *
     * <p>Same gate a pushed overlay has, for the same reason: its picture is a
     * glyph that only exists in the pack that was pushed, so opening it for a
     * player wearing the server's own bundle is a screen of missing-glyph
     * boxes.
     */
    public boolean fromPushedPack() {
        return pushed;
    }

    @Override
    public String toString() {
        return "DialogInfo[" + id + "]";
    }
}
