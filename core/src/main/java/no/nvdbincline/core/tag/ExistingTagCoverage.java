package no.nvdbincline.core.tag;

import java.util.List;
import java.util.Locale;
import no.nvdbincline.core.model.OsmWayGeom;

/**
 * Counts of tags already present on extracted OSM data (any source).
 *
 * <p>Distinct from the personal completion tracker: this reflects what is on
 * OSM right now, including other mappers' surveys.
 */
public final class ExistingTagCoverage {
    public final int totalWays;
    public final int withIncline;
    public final int withPluginIncline;
    public final int withOtherIncline;
    public final int withHazard;
    public final int withPluginHazard;
    public final int withOtherHazard;
    public final int withChainAdvisory;

    public ExistingTagCoverage(
            int totalWays,
            int withIncline,
            int withPluginIncline,
            int withOtherIncline,
            int withHazard,
            int withPluginHazard,
            int withOtherHazard,
            int withChainAdvisory) {
        this.totalWays = totalWays;
        this.withIncline = withIncline;
        this.withPluginIncline = withPluginIncline;
        this.withOtherIncline = withOtherIncline;
        this.withHazard = withHazard;
        this.withPluginHazard = withPluginHazard;
        this.withOtherHazard = withOtherHazard;
        this.withChainAdvisory = withChainAdvisory;
    }

    public static ExistingTagCoverage empty() {
        return new ExistingTagCoverage(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static ExistingTagCoverage scanWays(List<OsmWayGeom> ways) {
        int incline = 0;
        int plugin = 0;
        int other = 0;
        int hazard = 0;
        int pluginH = 0;
        int otherH = 0;
        int chain = 0;
        for (OsmWayGeom w : ways) {
            var origin =
                    ExistingTagPolicy.classifyIncline(
                            w.existingIncline().orElse(null),
                            w.existingSourceIncline().orElse(null));
            if (origin == ExistingTagPolicy.InclineOrigin.PLUGIN_NVDB_ESTIMATE) {
                incline++;
                plugin++;
            } else if (origin == ExistingTagPolicy.InclineOrigin.OTHER) {
                incline++;
                other++;
            }
            var hOrigin =
                    ExistingTagPolicy.classifyHazard(
                            w.existingHazard().orElse(null),
                            w.existingSourceHazard().orElse(null));
            if (hOrigin == ExistingTagPolicy.InclineOrigin.PLUGIN_NVDB_ESTIMATE) {
                hazard++;
                pluginH++;
            } else if (hOrigin == ExistingTagPolicy.InclineOrigin.OTHER) {
                hazard++;
                otherH++;
            }
            if (w.existingChainAdvisory().isPresent()) {
                chain++;
            }
        }
        return new ExistingTagCoverage(
                ways.size(), incline, plugin, other, hazard, pluginH, otherH, chain);
    }

    /** Merge node-level hazard/chain counts into a way scan (additive for hazards/chains). */
    public ExistingTagCoverage withNodeTags(
            int hazardNodes, int pluginHazardNodes, int otherHazardNodes, int chainNodes) {
        return new ExistingTagCoverage(
                totalWays,
                withIncline,
                withPluginIncline,
                withOtherIncline,
                withHazard + hazardNodes,
                withPluginHazard + pluginHazardNodes,
                withOtherHazard + otherHazardNodes,
                withChainAdvisory + chainNodes);
    }

    public int inclineCoveragePercent() {
        return percent(withIncline, totalWays);
    }

    public int pluginInclinePercent() {
        return percent(withPluginIncline, totalWays);
    }

    public int otherInclinePercent() {
        return percent(withOtherIncline, totalWays);
    }

    public int hazardCoveragePercent() {
        // Hazard is often on nodes; still report vs way count as a rough density signal,
        // or use max(totalWays,1) — document as "per matched way denominator".
        return percent(withHazard, Math.max(totalWays, 1));
    }

    /** True when a large share of ways already have non-plugin incline data. */
    public boolean suggestsSubstantialOtherCoverage() {
        return totalWays >= 20 && otherInclinePercent() >= 25;
    }

    public String formatInclineLine() {
        if (totalWays <= 0) {
            return "Existing incline coverage: n/a (no ways)";
        }
        return String.format(
                Locale.ROOT,
                "Existing incline coverage: %d%% (%d%% previously suggested by this tool, %d%% other/surveyed)",
                inclineCoveragePercent(),
                pluginInclinePercent(),
                otherInclinePercent());
    }

    public String formatHazardLine() {
        if (withHazard <= 0) {
            return "Existing hazard tags: none scanned";
        }
        return String.format(
                Locale.ROOT,
                "Existing hazard tags: %d (%d from this tool, %d other)",
                withHazard,
                withPluginHazard,
                withOtherHazard);
    }

    private static int percent(int part, int whole) {
        if (whole <= 0) {
            return 0;
        }
        return (int) Math.round(100.0 * part / whole);
    }
}
