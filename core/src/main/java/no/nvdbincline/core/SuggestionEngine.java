package no.nvdbincline.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.nvdbincline.core.chain.ChainAdvisory;
import no.nvdbincline.core.geo.ElevationProfiles;
import no.nvdbincline.core.gradient.GradientCalculator;
import no.nvdbincline.core.match.WayMatcher;
import no.nvdbincline.core.model.ChainPoint;
import no.nvdbincline.core.model.ElevationSample;
import no.nvdbincline.core.model.MatchResult;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.WaySuggestion;
import no.nvdbincline.core.tag.SuggestionTags;

/** Pure JVM suggestion engine — no JOSM dependency. */
public final class SuggestionEngine {
    public static final class Config {
        public double rollingWindowM = 50.0;
        public double splitSpreadPp = 4.0;
        public double minSegmentM = 50.0;
        public WayMatcher.Settings match = new WayMatcher.Settings();
        public ChainAdvisory.Settings chain = new ChainAdvisory.Settings();
    }

    public static final class Output {
        public final List<WaySuggestion> suggestions;
        public final List<WaySuggestion> discrepancies;
        public final List<ChainPoint> chainPoints;
        public final WayMatcher.Result matchResult;

        public Output(
                List<WaySuggestion> suggestions,
                List<WaySuggestion> discrepancies,
                List<ChainPoint> chainPoints,
                WayMatcher.Result matchResult) {
            this.suggestions = List.copyOf(suggestions);
            this.discrepancies = List.copyOf(discrepancies);
            this.chainPoints = List.copyOf(chainPoints);
            this.matchResult = matchResult;
        }
    }

    private SuggestionEngine() {}

    public static Output run(List<OsmWayGeom> ways, List<NvdbLink> links, Config config) {
        WayMatcher.Result matched = WayMatcher.match(ways, links, config.match);
        List<WaySuggestion> suggestions = new ArrayList<>();
        List<WaySuggestion> discrepancies = new ArrayList<>();
        List<ChainPoint> allChain = new ArrayList<>();

        GradientCalculator.SplitConfig splitCfg = new GradientCalculator.SplitConfig();
        splitCfg.windowM = config.rollingWindowM;
        splitCfg.spreadPp = config.splitSpreadPp;
        splitCfg.minSegmentM = config.minSegmentM;

        for (MatchResult m : matched.matches) {
            List<ElevationSample> profile = ElevationProfiles.build(m.way(), m.links());
            if (profile.size() < 2) {
                continue;
            }
            var stats = GradientCalculator.stats(profile, config.rollingWindowM);
            var split = GradientCalculator.suggestSegments(profile, splitCfg);
            String skip = null;
            Map<String, String> tags = Map.of();
            WaySuggestion sug =
                    new WaySuggestion(
                            m, profile, stats, split.segments, split.split, null, Map.of());
            if (m.way().existingIncline().isPresent()) {
                skip = "existing incline=* not overwritten";
                sug =
                        new WaySuggestion(
                                m, profile, stats, split.segments, split.split, skip, Map.of());
                discrepancies.add(sug);
                suggestions.add(sug);
            } else if (!SuggestionTags.isInclineEligible(m, stats)) {
                // Profile still usable for snow-chain heuristics; no incline review row.
            } else {
                tags = SuggestionTags.forWay(sug);
                sug =
                        new WaySuggestion(
                                m, profile, stats, split.segments, split.split, null, tags);
                suggestions.add(sug);
            }
            allChain.addAll(
                    ChainAdvisory.advise(profile, m.way().id(), m.links(), config.chain));
        }

        List<ChainPoint> clustered =
                ChainAdvisory.cluster(allChain, config.chain.clusterDistanceM);
        return new Output(suggestions, discrepancies, clustered, matched);
    }
}
