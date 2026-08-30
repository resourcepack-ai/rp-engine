package ai.resourcepack.engine.core.version;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.ItemEra;
import ai.resourcepack.engine.api.McVersion;
import ai.resourcepack.engine.core.pack.PackFormats;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What this server can do, resolved once at startup and asked thereafter.
 *
 * <p>One object rather than static lookups, and one place that reads the
 * server's version, for two reasons. The first is that it can then be built
 * from a version string in a test, which is the only way a table of floors
 * spanning fifteen releases gets checked against anything. The second is that
 * a version resolved once cannot drift: a check that re-reads the server on
 * every call is a check that can answer differently in two places for reasons
 * nobody will find.
 *
 * <p><b>It also has to be able to say what it decided.</b> The failure this
 * whole package exists to prevent is silent: a server owner on 1.20.2 writes
 * {@code max_stack: 8}, nothing happens, nothing is logged, and the pack looks
 * broken for a reason that is nowhere. So {@link #report()} lists every
 * reduced capability with the sentence {@link Feature#without()} owes them,
 * and the engine says it at startup whether or not anybody asked.
 */
public final class Compatibility {

    private final McVersion version;
    private final Set<Feature> available;
    private final ItemEra itemEra;
    private final int packFormat;
    private final Integer configuredFormat;
    private final boolean formatGuessed;

    private Compatibility(McVersion version, Integer configuredFormat) {
        this.version = version;
        this.itemEra = ItemEra.on(version);

        Set<Feature> present = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) {
            if (feature.on(version)) {
                present.add(feature);
            }
        }
        this.available = present;

        Optional<Integer> known = PackFormats.forVersion(version);
        // A configured format that agrees with the table is not an override
        // of anything, and reporting it as one would greet every server
        // upgrading from a build where this key was mandatory with a line
        // about a decision they did not make. It becomes an override again by
        // itself, and says so, on the day their game version moves past it.
        this.configuredFormat = known.isPresent() && known.get().equals(configuredFormat)
                ? null
                : configuredFormat;
        if (this.configuredFormat != null) {
            this.packFormat = this.configuredFormat;
            this.formatGuessed = false;
        } else if (known.isPresent()) {
            this.packFormat = known.get();
            this.formatGuessed = false;
        } else {
            // A jar outlives the table compiled into it. Guessing the newest
            // known format is right far more often than it is wrong — formats
            // change on a minority of releases — and it degrades to a warning
            // the player sees rather than a pack that fails to build.
            this.packFormat = PackFormats.newestKnown();
            this.formatGuessed = true;
        }
    }

    /** For a known version, with an optional {@code config.yml} override. */
    public static Compatibility of(McVersion version, Integer configuredFormat) {
        return new Compatibility(version, configuredFormat);
    }

    /** For a known version, with no override. */
    public static Compatibility of(McVersion version) {
        return new Compatibility(version, null);
    }

    public McVersion version() {
        return version;
    }

    /** Whether the engine runs here at all; see {@link McVersion#OLDEST_SUPPORTED}. */
    public boolean supported() {
        return version.atLeast(McVersion.OLDEST_SUPPORTED);
    }

    public boolean has(Feature feature) {
        return available.contains(feature);
    }

    public ItemEra itemEra() {
        return itemEra;
    }

    /** The number written into {@code pack.mcmeta}. */
    public int packFormat() {
        return packFormat;
    }

    /** Whether {@link #packFormat()} is a guess because this release is newer than the table. */
    public boolean formatGuessed() {
        return formatGuessed;
    }

    /** Whether {@link #packFormat()} came from {@code config.yml} rather than the table. */
    public boolean formatOverridden() {
        return configuredFormat != null;
    }

    /** Every feature this server does not have, in declaration order. */
    public List<Feature> missing() {
        List<Feature> missing = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (!available.contains(feature)) {
                missing.add(feature);
            }
        }
        return missing;
    }

    /**
     * What to tell the server owner at startup, one line each.
     *
     * <p>Silent on a server that has everything — the overwhelming case, and
     * a plugin that prints a paragraph about capabilities nobody is missing
     * trains people to skip its output, including the day it matters.
     */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        if (formatOverridden()) {
            lines.add("Pack format " + packFormat + " from config.yml, overriding the "
                    + "value for Minecraft " + version + ".");
        } else if (formatGuessed) {
            lines.add("Minecraft " + version + " is newer than this build knows about. Using "
                    + "pack format " + packFormat + ", the format for "
                    + PackFormats.newestKnownVersion() + ". If players see a "
                    + "\"made for a different version\" warning, set pack.format in "
                    + "config.yml and update the plugin.");
        }
        List<Feature> missing = missing();
        if (missing.isEmpty()) {
            return lines;
        }
        lines.add("Running on Minecraft " + version + ", so " + missing.size()
                + (missing.size() == 1 ? " feature is" : " features are") + " reduced:");
        for (Feature feature : missing) {
            lines.add("  - " + feature.label() + " (needs " + feature.since() + "). "
                    + feature.without());
        }
        return lines;
    }

    /**
     * The server's own version string, e.g. {@code 1.21.8}.
     *
     * <p>{@code getBukkitVersion()} returns {@code "1.21.8-R0.1-SNAPSHOT"} and
     * is present on every implementation, unlike Paper's
     * {@code getMinecraftVersion()}. Kept as the one reader of it so the
     * version the engine gates on and the version it reports to studio's
     * distribution manifest can never be two different answers.
     */
    public static String readServerVersion() {
        String raw = org.bukkit.Bukkit.getBukkitVersion();
        int dash = raw.indexOf('-');
        return dash > 0 ? raw.substring(0, dash) : raw;
    }
}
