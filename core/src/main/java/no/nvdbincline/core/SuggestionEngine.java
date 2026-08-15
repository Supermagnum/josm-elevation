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
import no.nvdbincline.core.tag.ExistingTagPolicy;
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
        return run(ways, links, config, ProgressCallback.NONE);
    }

    public static Output run(
            List<OsmWayGeom> ways,
            List<NvdbLink> links,
            Config config,
            ProgressCallback progress) {
        ProgressCallback cb = progress == null ? ProgressCallback.NONE : progress;
        WayMatcher.Result matched = WayMatcher.match(ways, links, config.match, cb);
        List<WaySuggestion> suggestions = new ArrayList<>();
        List<WaySuggestion> discrepancies = new ArrayList<>();
        List<ChainPoint> allChain = new ArrayList<>();

        GradientCalculator.SplitConfig splitCfg = new GradientCalculator.SplitConfig();
        splitCfg.windowM = config.rollingWindowM;
        splitCfg.spreadPp = config.splitSpreadPp;
        splitCfg.minSegmentM = config.minSegmentM;

        List<MatchResult> matchList = matched.matches;
        int total = matchList.size();
        for (int i = 0; i < matchList.size(); i++) {
            if (i == 0 || i == total - 1 || i % 25 == 0) {
                if (!cb.onProgress("Computing inclines…", i, total)) {
                    throw new WayMatcher.CancelledException();
                }
            }
            MatchResult m = matchList.get(i);
            List<ElevationSample> profile = ElevationProfiles.build(m.way(), m.links());
            if (profile.size() < 2) {
                continue;
            }
            var stats = GradientCalculator.stats(profile, config.rollingWindowM);
            var split = GradientCalculator.suggestSegments(profile, splitCfg);
            WaySuggestion draft =
                    new WaySuggestion(
                            m, profile, stats, split.segments, split.split, null, Map.of());

            if (!SuggestionTags.isInclineEligible(m, stats)
                    && ExistingTagPolicy.classifyIncline(m.way())
                            == ExistingTagPolicy.InclineOrigin.NONE) {
                // No incline row; profile still usable for snow-chain heuristics.
            } else {
                String proposed =
                        split.segments.isEmpty()
                                ? no.nvdbincline.core.tag.InclineTags.formatIncline(
                                        stats.averagePct())
                                : split.segments.get(0).inclineTag();
                // Prefer whole-way tag from SuggestionTags when eligible.
                Map<String, String> candidateTags = Map.of();
                if (SuggestionTags.isInclineEligible(m, stats)) {
                    candidateTags = SuggestionTags.forWay(draft);
                    if (candidateTags.containsKey("incline")) {
                        proposed = candidateTags.get("incline");
                    }
                }

                ExistingTagPolicy.InclineOrigin origin = ExistingTagPolicy.classifyIncline(m.way());
                ExistingTagPolicy.InclineDisposition disposition =
                        ExistingTagPolicy.decideIncline(
                                origin, m.way().existingIncline().orElse(null), proposed);

                switch (disposition) {
                    case FRESH -> {
                        if (!candidateTags.isEmpty()) {
                            suggestions.add(
                                    new WaySuggestion(
                                            m,
                                            profile,
                                            stats,
                                            split.segments,
                                            split.split,
                                            null,
                                            candidateTags,
                                            ExistingTagPolicy.InclineDisposition.FRESH));
                        }
                    }
                    case UPDATE -> {
                        if (!candidateTags.isEmpty()) {
                            suggestions.add(
                                    new WaySuggestion(
                                            m,
                                            profile,
                                            stats,
                                            split.segments,
                                            split.split,
                                            null,
                                            candidateTags,
                                            ExistingTagPolicy.InclineDisposition.UPDATE));
                        }
                    }
                    case DISCREPANCY_NOTE -> {
                        String skip =
                                "existing incline=* (non-plugin source) not overwritten";
                        WaySuggestion sug =
                                new WaySuggestion(
                                        m,
                                        profile,
                                        stats,
                                        split.segments,
                                        split.split,
                                        skip,
                                        Map.of(),
                                        ExistingTagPolicy.InclineDisposition.DISCREPANCY_NOTE);
                        discrepancies.add(sug);
                        suggestions.add(sug);
                    }
                    case UNCHANGED -> {
                        // No review row.
                    }
                }
            }
            allChain.addAll(
                    ChainAdvisory.advise(profile, m.way().id(), m.links(), config.chain));
        }
        if (!cb.onProgress("Computing inclines…", total, Math.max(total, 1))) {
            throw new WayMatcher.CancelledException();
        }

        List<ChainPoint> clustered =
                ChainAdvisory.cluster(allChain, config.chain.clusterDistanceM);
        return new Output(suggestions, discrepancies, clustered, matched);
    }
}
