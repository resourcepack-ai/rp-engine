package ai.resourcepack.engine.core.version;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.ItemEra;
import ai.resourcepack.engine.api.McVersion;
import ai.resourcepack.engine.core.pack.PackFormats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityTest {

    @Test
    void theTableHasNoGapAcrossTheOneDotTwentyOneLine() {
        // A gap is the failure mode of a hand-kept table, and it does not
        // announce itself: a version that falls in one builds a pack claiming
        // the newest format there is, which every player on it sees a warning
        // about. Walking the line release by release is the only thing that
        // catches an entry somebody forgot to widen.
        for (int patch = 0; patch <= 11; patch++) {
            McVersion version = McVersion.of(1, 21, patch);
            assertTrue(PackFormats.forVersion(version).isPresent(),
                    "no pack format covers " + version);
        }
        for (int patch = 0; patch <= 6; patch++) {
            McVersion version = McVersion.of(1, 20, patch);
            assertTrue(PackFormats.forVersion(version).isPresent(),
                    "no pack format covers " + version);
        }
    }

    @Test
    void theRangesAreOrderedAndDoNotOverlap() {
        List<PackFormats.Range> ranges = PackFormats.ranges();
        for (int i = 1; i < ranges.size(); i++) {
            PackFormats.Range previous = ranges.get(i - 1);
            PackFormats.Range current = ranges.get(i);
            assertTrue(current.from().atLeast(previous.to()),
                    current.label() + " starts before " + previous.label() + " ends");
            assertFalse(current.from().equals(previous.to()),
                    current.label() + " and " + previous.label() + " share a version");
            assertTrue(current.format() > previous.format(),
                    current.label() + " does not increase the format number");
        }
    }

    @Test
    void theOldestRangeIsTheOldestSupportedVersion() {
        // If these drift apart there is a supported version with no format,
        // or a format for a version the engine refuses to start on.
        assertEquals(McVersion.OLDEST_SUPPORTED, PackFormats.ranges().get(0).from());
    }

    @Test
    void knownVersionsGetTheirDocumentedFormat() {
        assertEquals(13, PackFormats.forVersion(McVersion.of(1, 19, 4)).orElseThrow());
        assertEquals(22, PackFormats.forVersion(McVersion.of(1, 20, 4)).orElseThrow());
        assertEquals(32, PackFormats.forVersion(McVersion.of(1, 20, 5)).orElseThrow());
        assertEquals(46, PackFormats.forVersion(McVersion.of(1, 21, 4)).orElseThrow());
        assertEquals(64, PackFormats.forVersion(McVersion.of(1, 21, 7)).orElseThrow());
    }

    @Test
    void aReleaseNewerThanTheTableIsAGuessAndSaysSo() {
        Compatibility later = Compatibility.of(McVersion.of(99, 9));
        assertTrue(later.formatGuessed());
        assertEquals(PackFormats.newestKnown(), later.packFormat());
        assertTrue(later.report().stream().anyMatch(line -> line.contains("newer than this build")),
                "a guessed format must be reported");
    }

    @Test
    void aConfiguredFormatThatAgreesWithTheTableIsQuiet() {
        // The upgrade path: every existing config.yml has this key set to the
        // right number for the one version the engine used to support.
        // On a current server, where nothing else has anything to report
        // either — 1.21.4 would still print its missing liquid tinting.
        Compatibility agreeing = Compatibility.of(McVersion.of(26, 2), 88);
        assertEquals(88, agreeing.packFormat());
        assertFalse(agreeing.formatOverridden());
        assertTrue(agreeing.report().isEmpty());
    }

    @Test
    void aConfiguredFormatThatDisagreesWinsAndIsReported() {
        // The same config.yml after the owner upgrades Minecraft. Their pinned
        // number still wins, because they wrote it, but they are told.
        Compatibility stale = Compatibility.of(McVersion.of(1, 21, 5), 46);
        assertEquals(46, stale.packFormat());
        assertTrue(stale.formatOverridden());
        assertTrue(stale.report().stream().anyMatch(line -> line.contains("overriding")));
    }

    @Test
    void itemEraForksWhereTheApiForks() {
        assertEquals(ItemEra.NBT, ItemEra.on(McVersion.of(1, 19, 4)));
        assertEquals(ItemEra.NBT, ItemEra.on(McVersion.of(1, 20, 4)));
        assertEquals(ItemEra.COMPONENTS, ItemEra.on(McVersion.of(1, 20, 5)));
        assertEquals(ItemEra.COMPONENTS, ItemEra.on(McVersion.of(1, 21, 3)));
        assertEquals(ItemEra.DEFINITIONS, ItemEra.on(McVersion.of(1, 21, 4)));
        assertEquals(ItemEra.DEFINITIONS, ItemEra.on(McVersion.of(26, 2)));
    }

    @Test
    void onlyTheDefinitionsEraNeedsNoNumbers() {
        assertFalse(ItemEra.DEFINITIONS.needsNumbers());
        assertTrue(ItemEra.COMPONENTS.needsNumbers());
        assertTrue(ItemEra.NBT.needsNumbers());
    }

    @Test
    void theOldestSupportedServerIsMissingTheFeaturesItShouldBe() {
        Compatibility oldest = Compatibility.of(McVersion.OLDEST_SUPPORTED);
        assertTrue(oldest.supported());
        assertFalse(oldest.has(Feature.PACK_STACKING));
        assertFalse(oldest.has(Feature.ITEM_COMPONENTS));
        assertFalse(oldest.has(Feature.ITEM_DEFINITIONS));
        assertFalse(oldest.has(Feature.ARMOUR_ART));
    }

    @Test
    void aCurrentServerIsMissingNothingAndSaysNothing() {
        // The report is silent when there is nothing to say. A plugin that
        // prints a paragraph every start teaches people to skip its output.
        Compatibility current = Compatibility.of(McVersion.of(26, 2));
        assertEquals(88, current.packFormat());
        assertTrue(current.missing().isEmpty());
        assertTrue(current.report().isEmpty());
    }

    @Test
    void aVersionBelowTheFloorIsNotSupported() {
        assertFalse(Compatibility.of(McVersion.of(1, 19, 2)).supported());
    }

    @Test
    void theReportLeavesOutTheDifferencesItHandledItself() {
        // A report that lists something the engine dealt with completely has a
        // line on it that does not matter, and one of those teaches a reader
        // that the rest do not either. Both of these are real forks with real
        // floors; neither costs the server owner anything.
        Compatibility old = Compatibility.of(McVersion.OLDEST_SUPPORTED);
        assertTrue(old.missing().contains(Feature.MODERN_PASSENGER_OFFSET));
        assertTrue(old.missing().contains(Feature.ITEM_STRING_TAGS));
        assertFalse(old.reportable().contains(Feature.MODERN_PASSENGER_OFFSET));
        assertFalse(old.reportable().contains(Feature.ITEM_STRING_TAGS));

        String report = String.join(" ", old.report());
        assertFalse(report.contains(Feature.MODERN_PASSENGER_OFFSET.label()), report);
        assertFalse(report.contains(Feature.ITEM_STRING_TAGS.label()), report);
    }

    @Test
    void everyVisibleFeatureIsInTheReportWhenItIsMissing() {
        // The docs page at /rp-engine/versions is this same list by hand, and
        // nothing spans the two. At least make the code side self-consistent.
        Compatibility old = Compatibility.of(McVersion.OLDEST_SUPPORTED);
        String report = String.join(" ", old.report());
        for (Feature feature : Feature.values()) {
            if (feature.visible()) {
                assertTrue(report.contains(feature.label()),
                        feature + " is visible and missing but is not in the report");
            }
        }
    }

    @Test
    void everyFeatureCanExplainItsOwnAbsence() {
        // The contract of the enum, and the reason it is worth having.
        for (Feature feature : Feature.values()) {
            assertNotNull(feature.without(), feature + " has no consequence");
            assertFalse(feature.without().isEmpty(), feature + " has an empty consequence");
            assertFalse(feature.label().isEmpty(), feature + " has an empty label");
            assertTrue(feature.since().atLeast(McVersion.OLDEST_SUPPORTED),
                    feature + " has a floor below the oldest supported version, so it is "
                            + "always present and does not belong here");
        }
    }
}
