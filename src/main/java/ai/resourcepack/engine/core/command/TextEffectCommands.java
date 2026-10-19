package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.TextEffectInfo;
import ai.resourcepack.engine.core.textfx.ChatTextEffects;
import ai.resourcepack.engine.core.textfx.TextEffectCatalogue;
import ai.resourcepack.engine.core.textfx.TextEffectPresets;
import ai.resourcepack.engine.core.textfx.TextEffectTags;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Text effects: {@code effects}, {@code effects presets} and
 * {@code effect <name> [text]}.
 *
 * <p>Every listing draws each effect in itself, because a name and a hex code
 * say nothing about what "seasick" looks like; the line is the preview. It is
 * drawn only for somebody whose pack has the effect, which is also the
 * quickest way to find out whether theirs does.
 */
public final class TextEffectCommands implements Area {

    private final TextEffectCatalogue effects;

    public TextEffectCommands(TextEffectCatalogue effects) {
        this.effects = effects;
    }

    @Override
    public String title() {
        return "Text Effects";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("effects", "each Text Effect, drawn in itself"),
                Help.of("effects", "presets", "the ready-made ones"),
                Help.of("effect", "<name> [text]", "text in an effect"));
    }

    @Override
    public List<String> signatures() {
        return List.of("effects", "effects presets", "effect");
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if ("effects".equals(sub)) {
            if (args.length >= 2 && "presets".equalsIgnoreCase(args[1])) {
                return presets(sender);
            }
            return list(sender);
        }
        return preview(sender, args);
    }

    private boolean list(CommandSender sender) {
        List<ContentId> ids = effects.ids();
        if (ids.isEmpty()) {
            Reply.to(sender, "No Text Effects. Make one in Studio, or write one in a content folder's "
                    + "text-effects/ (/rp effects presets lists the ready-made ones).");
            return true;
        }
        Reply.heading(sender, "Text Effects", Reply.plural(ids.size(), "effect")
                + ", write one as <fx:name>text</fx>");
        for (ContentId id : ids) {
            Optional<TextEffectInfo> info = effects.info(id);
            if (info.isEmpty()) {
                continue;
            }
            TextEffectInfo effect = info.get();
            String from = effect.source() == TextEffectInfo.Source.STUDIO ? "Studio"
                    : effect.preset().map(p -> "preset " + p).orElse("own code");
            Reply.row(sender, id.toString(), effect.color() + " · " + from + " · "
                    + TextEffectTags.apply(effect, effect.name()));
        }
        Reply.note(sender, "In chat: " + ChatTextEffects.ALL + " for all, or "
                + ChatTextEffects.PREFIX + "<name> for one.");
        return true;
    }

    private boolean presets(CommandSender sender) {
        List<TextEffectPresets.Preset> all = TextEffectPresets.get().all();
        Reply.heading(sender, "Presets", Reply.plural(all.size(), "preset")
                + ", use one with preset: <key> in text-effects/");
        for (TextEffectPresets.Preset preset : all) {
            Reply.row(sender, preset.key(), preset.group() + " · " + preset.blurb());
        }
        return true;
    }

    private boolean preview(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rp effect <name> [text]. /rp effects lists them.");
            return true;
        }
        Optional<TextEffectInfo> effect = effects.find(args[1]);
        if (effect.isEmpty()) {
            Reply.to(sender, "No Text Effect called " + args[1] + ". /rp effects lists them.");
            return true;
        }
        String text = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : effect.get().name();
        // Bare, with no prefix in front: the line is the effect, and a prefix
        // would be drawn in our colours right beside it.
        sender.sendMessage(TextEffectTags.apply(effect.get(), text));
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if ("effects".equals(sub) && args.length == 2) {
            return startingWith(List.of("presets"), args[1]);
        }
        if ("effect".equals(sub) && args.length == 2) {
            List<String> names = new ArrayList<>();
            for (ContentId id : effects.ids()) {
                names.add(id.toString());
            }
            return startingWith(names, args[1]);
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
