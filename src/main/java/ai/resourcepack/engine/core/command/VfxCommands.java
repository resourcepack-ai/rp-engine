package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VfxInfo;
import ai.resourcepack.engine.core.vfx.VfxCatalogue;
import ai.resourcepack.engine.core.vfx.VfxData;
import ai.resourcepack.engine.core.vfx.VfxEntry;
import ai.resourcepack.engine.core.vfx.VfxRuntime;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * VFX: {@code vfx}, {@code vfx presets}, {@code cast <name> [player]} and
 * {@code cast stop}.
 *
 * <p>A cast plays at the player's feet, facing the way they face, which is the
 * frame every recipe is written in.
 */
public final class VfxCommands implements Area {

    private static final String STOP = "stop";

    private final VfxCatalogue catalogue;
    private final VfxRuntime runtime;

    public VfxCommands(VfxCatalogue catalogue, VfxRuntime runtime) {
        this.catalogue = catalogue;
        this.runtime = runtime;
    }

    @Override
    public String title() {
        return "VFX";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("vfx", "each VFX this server can play"),
                Help.of("vfx", "presets", "the ready-made ones"),
                Help.of("cast", "<name> [player]", "play a VFX where a player stands"),
                Help.of("cast", "stop", "end every VFX playing"));
    }

    @Override
    public List<String> signatures() {
        return List.of("vfx presets", "vfx", "cast stop", "cast");
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if ("vfx".equals(sub)) {
            if (args.length >= 2 && "presets".equalsIgnoreCase(args[1])) {
                return presets(sender);
            }
            return list(sender);
        }
        return cast(sender, args);
    }

    private boolean list(CommandSender sender) {
        List<ContentId> ids = catalogue.ids();
        if (ids.isEmpty()) {
            Reply.to(sender, "No VFX. Make one in Studio, or write one in a content folder's vfx/ "
                    + "(/rp vfx presets lists the ready-made ones).");
            return true;
        }
        Reply.heading(sender, "VFX", Reply.plural(ids.size(), "effect") + ", play one with /rp cast <name>");
        for (ContentId id : ids) {
            Optional<VfxInfo> info = catalogue.info(id);
            if (info.isEmpty()) {
                continue;
            }
            VfxInfo effect = info.get();
            String from = effect.source() == VfxInfo.Source.STUDIO ? "Studio"
                    : effect.preset().map(p -> "preset " + p).orElse("own recipe");
            Reply.row(sender, id.toString(), effect.name() + " · " + seconds(effect.durationTicks()) + " · " + from);
        }
        if (!runtime.supported()) {
            Reply.note(sender, "This server is older than 1.21.4, so none of them can play here.");
        }
        return true;
    }

    private boolean presets(CommandSender sender) {
        List<VfxData.Preset> all = VfxData.get().presets();
        Reply.heading(sender, "Presets", Reply.plural(all.size(), "preset") + ", use one with preset: <key> in vfx/");
        for (VfxData.Preset preset : all) {
            Reply.row(sender, preset.key(), preset.name() + " · " + preset.blurb());
        }
        return true;
    }

    private boolean cast(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rp cast <name> [player], or /rp cast stop. /rp vfx lists them.");
            return true;
        }
        if (STOP.equalsIgnoreCase(args[1])) {
            int stopped = runtime.stopAll();
            Reply.to(sender, stopped == 0 ? "No VFX playing." : "Stopped " + Reply.plural(stopped, "VFX") + ".");
            return true;
        }
        if (!runtime.supported()) {
            Reply.to(sender, "VFX need Minecraft 1.21.4 or newer: a sprite picks its picture through an item model "
                    + "definition, which older servers cannot send.");
            return true;
        }
        Optional<VfxEntry> entry = catalogue.find(args[1]);
        if (entry.isEmpty()) {
            Reply.to(sender, "No VFX called " + args[1] + ". /rp vfx lists them.");
            return true;
        }
        Player target = Targets.of(sender, args.length > 2 ? args[2] : null);
        if (target == null) {
            Reply.to(sender, args.length > 2 ? "No player called " + args[2] + " is online." : "/rp cast <name> <player>, from the console.");
            return true;
        }
        if (!Targets.permitted(sender, "cast", target)) {
            Targets.refuse(sender, "cast", "cast a VFX on");
            return true;
        }
        runtime.play(entry.get(), target.getLocation());
        return true;
    }

    private static String seconds(int ticks) {
        double s = ticks / 20.0;
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.format(Locale.ROOT, "%.1f", s)) + "s";
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if ("vfx".equals(sub) && args.length == 2) {
            return startingWith(List.of("presets"), args[1]);
        }
        if ("cast".equals(sub) && args.length == 2) {
            List<String> names = new ArrayList<>();
            names.add(STOP);
            for (ContentId id : catalogue.ids()) {
                names.add(id.toString());
            }
            return startingWith(names, args[1]);
        }
        if ("cast".equals(sub) && args.length == 3 && !STOP.equalsIgnoreCase(args[1])) {
            return startingWith(Targets.names(sender, "cast"), args[2]);
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
