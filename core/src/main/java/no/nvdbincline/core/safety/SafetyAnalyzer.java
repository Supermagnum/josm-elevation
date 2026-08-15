package no.nvdbincline.core.safety;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import no.nvdbincline.core.curve.CurveDetector;
import no.nvdbincline.core.model.CurveFeature;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.NvdbPointFeature;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.SafetyFinding;
import no.nvdbincline.core.tag.AppliedTags;
import no.nvdbincline.core.tag.ExistingTagPolicy;

/**
 * Cross-check geometric curves and accident clusters against NVDB warning signs.
 *
 * <p>OSM {@code hazard=*} is only emitted when a matching NVDB sign is present.
 * Applied tags use {@code source:hazard} (not {@code hazard:source}).
 */
public final class SafetyAnalyzer {
    /** NVDB Skiltplate */
    public static final long TYPE_SKILTPLATE = 96;
    /** NVDB Trafikkulykke */
    public static final long TYPE_TRAFIKKULYKKE = 570;

    /** Norwegian warning-sign codes for dangerous curves. */
    public static final Set<String> CURVE_SIGN_CODES =
            Set.of("100.1", "100.2", "102.1", "102.2", "100", "102");
    /** Farlig vegkryss */
    public static final Set<String> JUNCTION_SIGN_CODES = Set.of("124");

    public static final class Settings {
        public double signMatchRadiusM = 80.0;
        public CurveDetector.Settings curve = new CurveDetector.Settings();
        public AccidentClusterer.Settings accidents = new AccidentClusterer.Settings();
    }

    private SafetyAnalyzer() {}

    public static List<SafetyFinding> analyze(
            List<OsmWayGeom> ways,
            List<NvdbPointFeature> signs,
            List<NvdbPointFeature> accidents,
            Settings settings) {
        List<SafetyFinding> out = new ArrayList<>();
        List<CurveFeature> curves = CurveDetector.detect(ways, settings.curve);
        Set<Long> usedSigns = new java.util.HashSet<>();

        for (CurveFeature curve : curves) {
            NvdbPointFeature sign =
                    nearestMatchingSign(
                            curve.x(),
                            curve.y(),
                            signs,
                            CURVE_SIGN_CODES,
                            settings.signMatchRadiusM);
            if (sign != null) {
                usedSigns.add(sign.objectId());
                String note =
                        "NVDB skilt "
                                + sign.skiltnummer()
                                + " (Farlig sving) ved geometrisk skarp kurve R≈"
                                + String.format(Locale.ROOT, "%.0f", curve.radiusM())
                                + " m. Verifiser skilt i felt før behold.";
                String fixme =
                        "NVDB-sign-backed hazard=curve suggestion; verify posted sign on site.";
                Map<String, String> tags = AppliedTags.hazard("curve", note, fixme);
                out.add(
                        new SafetyFinding(
                                SafetyFinding.Kind.CURVE_SIGNED,
                                true,
                                curve.x(),
                                curve.y(),
                                curve.wayId(),
                                "hazard=curve (NVDB skilt "
                                        + sign.skiltnummer()
                                        + ", R≈"
                                        + Math.round(curve.radiusM())
                                        + "m)",
                                MatchConfidence.HIGH,
                                tags,
                                0,
                                null,
                                curve.radiusM()));
            } else {
                String note =
                        "Foreslatt skarp kurve (NVDB/OSM-geometri, R≈"
                                + String.format(Locale.ROOT, "%.0f", curve.radiusM())
                                + " m). Ingen NVDB Farlig-sving-skilt funnet i nærheten — "
                                + "IKKE hazard=* (krever skilt). Verifiser i felt.";
                Map<String, String> tags = AppliedTags.safetyAdvisory("sharp_curve", note);
                out.add(
                        new SafetyFinding(
                                SafetyFinding.Kind.CURVE_ADVISORY,
                                false,
                                curve.x(),
                                curve.y(),
                                curve.wayId(),
                                "advisory sharp_curve R≈"
                                        + Math.round(curve.radiusM())
                                        + "m (no NVDB sign)",
                                MatchConfidence.LOW,
                                tags,
                                0,
                                null,
                                curve.radiusM()));
            }
        }

        for (NvdbPointFeature sign : signs) {
            if (usedSigns.contains(sign.objectId())) {
                continue;
            }
            if (!isCodeMatch(sign.skiltnummer(), JUNCTION_SIGN_CODES)) {
                continue;
            }
            String note =
                    "NVDB skilt "
                            + sign.skiltnummer()
                            + " (Farlig vegkryss). Verifiser skilt i felt før behold.";
            String fixme =
                    "NVDB-sign-backed hazard=dangerous_junction suggestion; verify posted sign on site.";
            Map<String, String> tags =
                    AppliedTags.hazard("dangerous_junction", note, fixme);
            out.add(
                    new SafetyFinding(
                            SafetyFinding.Kind.JUNCTION_SIGNED,
                            true,
                            sign.x(),
                            sign.y(),
                            null,
                            "hazard=dangerous_junction (NVDB skilt " + sign.skiltnummer() + ")",
                            MatchConfidence.HIGH,
                            tags,
                            0,
                            null,
                            0));
        }

        List<AccidentClusterer.Cluster> clusters =
                AccidentClusterer.cluster(accidents, settings.accidents);
        for (AccidentClusterer.Cluster cluster : clusters) {
            NvdbPointFeature junctionSign =
                    nearestMatchingSign(
                            cluster.x,
                            cluster.y,
                            signs,
                            JUNCTION_SIGN_CODES,
                            settings.signMatchRadiusM);
            NvdbPointFeature curveSign =
                    nearestMatchingSign(
                            cluster.x,
                            cluster.y,
                            signs,
                            CURVE_SIGN_CODES,
                            settings.signMatchRadiusM);
            String dateRange = cluster.dateFrom + "–" + cluster.dateTo;
            if (junctionSign != null) {
                String note =
                        "NVDB skilt "
                                + junctionSign.skiltnummer()
                                + " ved ulykkesansamling ("
                                + cluster.count
                                + " Trafikkulykke "
                                + dateRange
                                + ", NVDB type 570). Verifiser skilt i felt.";
                String fixme =
                        "NVDB-sign-backed hazard=dangerous_junction; accident cluster is context only.";
                Map<String, String> tags =
                        AppliedTags.hazard("dangerous_junction", note, fixme);
                out.add(
                        new SafetyFinding(
                                SafetyFinding.Kind.ACCIDENT_CLUSTER,
                                true,
                                cluster.x,
                                cluster.y,
                                null,
                                "hazard=dangerous_junction + "
                                        + cluster.count
                                        + " accidents "
                                        + dateRange,
                                MatchConfidence.HIGH,
                                tags,
                                cluster.count,
                                dateRange,
                                0));
            } else if (curveSign != null) {
                String note =
                        "NVDB skilt "
                                + curveSign.skiltnummer()
                                + " ved ulykkesansamling ("
                                + cluster.count
                                + " Trafikkulykke "
                                + dateRange
                                + "). Verifiser skilt i felt.";
                String fixme =
                        "NVDB-sign-backed hazard=curve; accident cluster is context only.";
                Map<String, String> tags = AppliedTags.hazard("curve", note, fixme);
                out.add(
                        new SafetyFinding(
                                SafetyFinding.Kind.ACCIDENT_CLUSTER,
                                true,
                                cluster.x,
                                cluster.y,
                                null,
                                "hazard=curve + " + cluster.count + " accidents " + dateRange,
                                MatchConfidence.HIGH,
                                tags,
                                cluster.count,
                                dateRange,
                                0));
            } else {
                String note =
                        "NVDB Trafikkulykke-ansamling: "
                                + cluster.count
                                + " ulykker "
                                + dateRange
                                + " (type 570). Ingen matchende fareskilt i nærheten — "
                                + "IKKE hazard=* (krever skilt/offisiell merking). Vurder lokalkunnskap.";
                Map<String, String> tags =
                        AppliedTags.safetyAdvisory("accident_cluster", note);
                out.add(
                        new SafetyFinding(
                                SafetyFinding.Kind.ACCIDENT_CLUSTER,
                                false,
                                cluster.x,
                                cluster.y,
                                null,
                                "advisory accident_cluster n="
                                        + cluster.count
                                        + " "
                                        + dateRange
                                        + " (no sign)",
                                MatchConfidence.LOW,
                                tags,
                                cluster.count,
                                dateRange,
                                0));
            }
        }
        return applyExistingHazardPolicy(out, ways);
    }

    /**
     * Three-way existing-tag policy for hazard=* on ways: fresh / update /
     * discrepancy note (never overwrite human-sourced hazard).
     */
    static List<SafetyFinding> applyExistingHazardPolicy(
            List<SafetyFinding> findings, List<OsmWayGeom> ways) {
        Map<Long, OsmWayGeom> byId = new HashMap<>();
        for (OsmWayGeom w : ways) {
            byId.put(w.id(), w);
        }
        List<SafetyFinding> out = new ArrayList<>(findings.size());
        for (SafetyFinding f : findings) {
            SafetyFinding adj = adjustHazardFinding(f, byId);
            if (adj != null) {
                out.add(adj);
            }
        }
        return out;
    }

    static SafetyFinding adjustHazardFinding(
            SafetyFinding f, Map<Long, OsmWayGeom> byId) {
        if (!f.tags().containsKey("hazard") || f.wayId() == null) {
            return f;
        }
        OsmWayGeom way = byId.get(f.wayId());
        if (way == null) {
            return f;
        }
        String proposed = f.tags().get("hazard");
        String existing = way.existingHazard().orElse(null);
        ExistingTagPolicy.InclineOrigin origin = ExistingTagPolicy.classifyHazard(way);
        ExistingTagPolicy.InclineDisposition disposition =
                ExistingTagPolicy.decideHazard(origin, existing, proposed);
        return switch (disposition) {
            case FRESH -> f;
            case UPDATE ->
                    new SafetyFinding(
                            f.kind(),
                            true,
                            f.x(),
                            f.y(),
                            f.wayId(),
                            "Update: "
                                    + existing
                                    + " → "
                                    + proposed
                                    + " (prior nvdb_sign); "
                                    + f.summary(),
                            f.confidence(),
                            f.tags(),
                            f.accidentCount(),
                            f.dateRange(),
                            f.radiusM());
            case DISCREPANCY_NOTE ->
                    new SafetyFinding(
                            f.kind(),
                            false,
                            f.x(),
                            f.y(),
                            f.wayId(),
                            "discrepancy note (not suggested): existing hazard="
                                    + existing
                                    + " vs NVDB "
                                    + proposed
                                    + " — human/other source kept",
                            MatchConfidence.LOW,
                            Map.of(),
                            f.accidentCount(),
                            f.dateRange(),
                            f.radiusM());
            case UNCHANGED -> null;
        };
    }

    static NvdbPointFeature nearestMatchingSign(
            double x,
            double y,
            List<NvdbPointFeature> signs,
            Set<String> codes,
            double radiusM) {
        NvdbPointFeature best = null;
        double bestD = radiusM;
        for (NvdbPointFeature s : signs) {
            if (!isCodeMatch(s.skiltnummer(), codes)) {
                continue;
            }
            double d = Math.hypot(s.x() - x, s.y() - y);
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    static boolean isCodeMatch(String skiltnummer, Set<String> codes) {
        if (skiltnummer == null || skiltnummer.isBlank()) {
            return false;
        }
        String n = skiltnummer.trim();
        for (String code : codes) {
            if (n.equals(code)
                    || n.startsWith(code + " ")
                    || n.startsWith(code + " -")
                    || n.startsWith(code + "-")) {
                return true;
            }
        }
        return false;
    }
}
