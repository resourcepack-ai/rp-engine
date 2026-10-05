package ai.resourcepack.engine.core.storage;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.StorageSpec;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a {@code storage:} block.
 *
 * <p><strong>One parser for everything that can hold items</strong>, which is
 * why it is not inside {@code ModelDefinitions}: a placed model and a custom
 * block both say {@code storage:}, and two parsers would be two answers to what
 * {@code rows: 4} means. Call {@link #parse} with the body the key sits in and
 * this finds it.
 *
 * <p>Free of Bukkit, so every mistake an author can make in one is a tested
 * diagnostic rather than a container that silently opens the wrong size.
 *
 * <p>Three spellings, because the short ones are what people write:
 * <pre>
 * storage: true          # a three-row chest
 * storage: personal      # that type, everything else default
 * storage:
 *   type: chest
 *   rows: 3
 *   title: "Cabinet"
 *   open-sound: minecraft:block.chest.open
 *   close-sound: minecraft:block.chest.close
 * </pre>
 */
public final class StorageDefinitions {

    /**
     * What a sound key may look like: a resource location, namespace optional.
     * Checked here rather than with Bukkit's own parser so this stays testable,
     * and because a key with a space in it is the commonest typo and is silent
     * in game.
     */
    private static final Pattern SOUND = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    private StorageDefinitions() {
    }

    /**
     * The {@code storage:} block in {@code body}, if it has one.
     *
     * @param body   the node {@code storage:} is a key OF — a model's
     *               {@code place:} block, or a custom block's definition
     * @param origin the file, for the diagnostic
     * @param where  the definition's name, for the diagnostic
     * @return empty if there is no storage, or it was switched off with
     *         {@code storage: false}
     */
    public static Optional<StorageSpec> parse(DefinitionNode body, String origin, String where,
                                              List<Diagnostic> diagnostics) {
        if (body == null || !body.has("storage")) {
            return Optional.empty();
        }
        Optional<DefinitionNode> block = body.node("storage");
        if (block.isPresent()) {
            return Optional.of(parseNode(block.get(), origin, where, diagnostics));
        }
        Optional<Boolean> on = body.bool("storage");
        if (on.isPresent()) {
            return on.get() ? Optional.of(StorageSpec.chest()) : Optional.empty();
        }
        Optional<String> named = body.string("storage");
        if (named.isPresent()) {
            Optional<StorageSpec.Type> type = type(named.get(), origin, where, diagnostics);
            return type.map(one -> StorageSpec.of(one, StorageSpec.DEFAULT_ROWS, null, null, null));
        }
        diagnostics.add(Diagnostic.warning(origin, where,
                "storage: should be a block of settings, true, or a type. It holds nothing."));
        return Optional.empty();
    }

    /**
     * The {@code storage:} block itself, for a caller that has already taken it
     * out of its parent. Never empty: a block that is present is a container,
     * and every setting in it has a default.
     */
    public static StorageSpec parseNode(DefinitionNode storage, String origin, String where,
                                        List<Diagnostic> diagnostics) {
        StorageSpec.Type type = StorageSpec.Type.CHEST;
        Optional<String> declaredType = storage.string("type");
        if (declaredType.isPresent()) {
            type = type(declaredType.get(), origin, where, diagnostics).orElse(StorageSpec.Type.CHEST);
        }

        int rows = StorageSpec.DEFAULT_ROWS;
        if (storage.has("rows")) {
            Optional<Integer> declared = storage.integer("rows");
            if (declared.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "storage rows: " + storage.raw("rows") + " is not a whole number. Using "
                                + StorageSpec.DEFAULT_ROWS + "."));
            } else if (declared.get() < 1 || declared.get() > StorageSpec.MAX_ROWS) {
                rows = Math.max(1, Math.min(StorageSpec.MAX_ROWS, declared.get()));
                diagnostics.add(Diagnostic.warning(origin, where,
                        "storage rows: " + declared.get() + " is outside 1-" + StorageSpec.MAX_ROWS
                                + " (a container screen is at most a double chest). Using " + rows + "."));
            } else {
                rows = declared.get();
            }
            if (type == StorageSpec.Type.ENDERCHEST) {
                // Not an error worth refusing, but worth saying: the author
                // asked for a size and will not get it.
                diagnostics.add(Diagnostic.warning(origin, where,
                        "storage rows: does nothing for an enderchest, which opens the player's own "
                                + "ender chest at whatever size the game makes it."));
            }
        }

        String title = storage.string("title").orElse(null);
        String open = sound(storage, "open-sound", origin, where, diagnostics);
        String close = sound(storage, "close-sound", origin, where, diagnostics);
        return StorageSpec.of(type, rows, title, open, close);
    }

    private static Optional<StorageSpec.Type> type(String written, String origin, String where,
                                                   List<Diagnostic> diagnostics) {
        String key = written.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        // The spelling everybody reaches for first, accepted rather than taught.
        if (key.equals("ENDER_CHEST")) {
            key = "ENDERCHEST";
        }
        try {
            return Optional.of(StorageSpec.Type.valueOf(key));
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "storage type: " + written + " is not one of chest, personal, enderchest, disposal, "
                            + "shulker. Using chest."));
            return Optional.of(StorageSpec.Type.CHEST);
        }
    }

    /**
     * A sound key, {@code ""} for an explicit silence, or null for the type's
     * own default.
     */
    private static String sound(DefinitionNode storage, String key, String origin, String where,
                                List<Diagnostic> diagnostics) {
        if (!storage.has(key)) {
            return null;
        }
        Optional<Boolean> off = storage.bool(key);
        if (off.isPresent() && !off.get()) {
            return "";
        }
        String written = storage.string(key).orElse("").trim();
        if (written.isEmpty() || written.equalsIgnoreCase("none")) {
            return "";
        }
        String lower = written.toLowerCase(Locale.ROOT);
        if (!SOUND.matcher(lower).matches()) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "storage " + key + ": " + written + " is not a sound key like "
                            + "minecraft:block.chest.open. Using the default."));
            return null;
        }
        return lower;
    }
}
