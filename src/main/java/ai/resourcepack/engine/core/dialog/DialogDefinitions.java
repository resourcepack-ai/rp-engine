package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.DialogInfo;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads hand-authored dialog definitions.
 *
 * <p><strong>This is a small front on a big format, and that is the whole
 * design.</strong> Minecraft's dialog schema has five types, four kinds of
 * input, a dozen click events and more arriving every release; re-declaring
 * all of it in YAML would be a second implementation of somebody else's codec,
 * out of date on the first snapshot. So the YAML here covers the shape people
 * actually author — a title, some lines, some buttons that run commands — and
 * anything past it is written as the game's own JSON in a file named by
 * {@code json:}, which the engine transports without reading.
 *
 * <p>Those two doors are not a compromise between them: the YAML is for the
 * server owner writing a menu, the JSON door is for somebody using a feature
 * this engine has never heard of, and the second is what stops the first from
 * having to grow every time Mojang adds a field.
 */
public final class DialogDefinitions {

    private DialogDefinitions() {
    }

    /** What a button may do, in the game's click-event vocabulary. */
    private static final Set<String> ACTIONS =
            Set.of("run_command", "suggest_command", "open_url", "copy_to_clipboard", "show_dialog");

    public static Result parse(LoadReport loaded, java.util.function.Function<String, String> readFile) {
        Map<ContentId, DialogInfo> dialogs = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.DIALOG)) {
            parseOne(definition, readFile, diagnostics)
                    .ifPresent(dialog -> dialogs.put(dialog.id(), dialog));
        }
        return new Result(Map.copyOf(dialogs), List.copyOf(diagnostics));
    }

    private static Optional<DialogInfo> parseOne(ContentDefinition definition,
                                                 java.util.function.Function<String, String> readFile,
                                                 List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();
        String name = body.string("name").orElse(definition.id().path());

        // The escape hatch first: a definition naming a file is that file, and
        // nothing below applies to it.
        Optional<String> file = body.string("json");
        if (file.isPresent()) {
            String json = readFile == null ? null : readFile.apply(file.get());
            if (json == null || json.isBlank()) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "json: " + file.get() + " is not a file in this pack."));
                return Optional.empty();
            }
            return Optional.of(DialogInfo.authored(definition.id(), json, name));
        }

        List<String> lines = body.strings("body");
        List<DefinitionNode> buttons = body.nodes("buttons");
        if (buttons.isEmpty() && !body.bool("can_close_with_escape").orElse(Boolean.TRUE)) {
            // A screen with no button and no escape is a screen nobody can
            // leave. Refused rather than warned about: the cost of being wrong
            // is a player stuck until they quit.
            diagnostics.add(Diagnostic.error(origin, where,
                    "This dialog has no buttons and cannot be closed with escape, "
                            + "so nothing would ever close it."));
            return Optional.empty();
        }

        StringBuilder json = new StringBuilder("{\n");
        String type = buttons.size() <= 1 ? "notice" : buttons.size() == 2 ? "confirmation" : "multi_action";
        json.append("  \"type\": \"minecraft:").append(type).append("\",\n");
        json.append("  \"title\": ").append(quote(body.string("title").orElse(name))).append(",\n");
        json.append("  \"can_close_with_escape\": ")
                .append(body.bool("can_close_with_escape").orElse(Boolean.TRUE)).append(",\n");
        json.append("  \"pause\": ").append(body.bool("pause").orElse(Boolean.TRUE)).append(",\n");
        json.append("  \"after_action\": ")
                .append(quote(after(body.string("after").orElse("close")))).append(",\n");

        if (!lines.isEmpty()) {
            json.append("  \"body\": [\n");
            for (int i = 0; i < lines.size(); i++) {
                json.append("    { \"type\": \"minecraft:plain_message\", \"contents\": ")
                        .append(quote(lines.get(i))).append(", \"width\": 256 }")
                        .append(i == lines.size() - 1 ? "\n" : ",\n");
            }
            json.append("  ],\n");
        }

        List<String> rendered = new ArrayList<>();
        for (DefinitionNode button : buttons) {
            rendered.add(button(button, origin, where, diagnostics));
        }
        if (type.equals("notice")) {
            if (!rendered.isEmpty()) {
                json.append("  \"action\": ").append(rendered.get(0)).append("\n");
            } else {
                // Vanilla's default is an Ok button, so a notice with none is
                // still perfectly usable — the trailing comma is what has to go.
                json.setLength(json.length() - 2);
                json.append("\n");
            }
        } else if (type.equals("confirmation")) {
            json.append("  \"yes\": ").append(rendered.get(0)).append(",\n");
            json.append("  \"no\": ").append(rendered.get(1)).append("\n");
        } else {
            json.append("  \"columns\": ").append(body.integer("columns").orElse(2)).append(",\n");
            json.append("  \"actions\": [\n");
            for (int i = 0; i < rendered.size(); i++) {
                json.append("    ").append(rendered.get(i)).append(i == rendered.size() - 1 ? "\n" : ",\n");
            }
            json.append("  ]\n");
        }
        json.append("}\n");
        return Optional.of(DialogInfo.authored(definition.id(), json.toString(), name));
    }

    private static String button(DefinitionNode node, String origin, String where, List<Diagnostic> diagnostics) {
        String label = node.string("label").orElse("Ok");
        StringBuilder out = new StringBuilder("{ \"label\": ").append(quote(label));
        node.string("tooltip").ifPresent(tip -> out.append(", \"tooltip\": ").append(quote(tip)));
        out.append(", \"width\": ").append(node.integer("width").orElse(150));

        // One key per action, rather than an `action:` naming one and a
        // `value:` beside it. An author writing `command: spawn` has said both
        // things in one line, and the pair is the shape people get wrong.
        for (String action : ACTIONS) {
            Optional<String> value = node.string(key(action));
            if (value.isEmpty()) {
                continue;
            }
            out.append(", \"action\": { \"type\": \"minecraft:").append(action).append("\", ")
                    .append(quote(field(action))).append(": ").append(quote(value.get())).append(" }");
            return out.append(" }").toString();
        }
        if (node.has("action")) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "action: is not a key on a button. Write what it does instead — "
                            + "command:, url:, suggest:, copy: or dialog:."));
        }
        return out.append(" }").toString();
    }

    /** The YAML key that asks for an action. */
    private static String key(String action) {
        switch (action) {
            case "run_command":
                return "command";
            case "suggest_command":
                return "suggest";
            case "open_url":
                return "url";
            case "copy_to_clipboard":
                return "copy";
            default:
                return "dialog";
        }
    }

    /** The JSON field that action's value goes in. */
    private static String field(String action) {
        switch (action) {
            case "run_command":
            case "suggest_command":
                return "command";
            case "open_url":
                return "url";
            case "copy_to_clipboard":
                return "value";
            default:
                return "dialog";
        }
    }

    private static String after(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.equals("none") || lower.equals("wait_for_response") ? lower : "close";
    }

    /** A JSON string. Minimal and correct — this is the only escaping here. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    /** What a parse produced, and what went wrong doing it. */
    public record Result(Map<ContentId, DialogInfo> dialogs, List<Diagnostic> diagnostics) {
    }
}
