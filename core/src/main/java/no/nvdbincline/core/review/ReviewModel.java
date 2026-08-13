package no.nvdbincline.core.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import no.nvdbincline.core.model.ChainPoint;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.SafetyFinding;
import no.nvdbincline.core.model.WaySuggestion;
import no.nvdbincline.core.tag.AppliedTags;
import no.nvdbincline.core.tag.InclineTags;

/**
 * Review-dialog row model and accept/reject filtering — pure JVM, no Swing/JOSM.
 * Only accepted rows should be turned into edit commands by the plugin adapter.
 *
 * <p>{@link Row#tags} holds only applyable OSM keys. Match quality / estimates /
 * split segment lists live on {@link Row#inclineAudit} for the UI.
 */
public final class ReviewModel {
    public enum Kind {
        WAY_TAGS,
        CHAIN_NODE,
        DISCREPANCY,
        CURVE_SIGNED,
        CURVE_ADVISORY,
        JUNCTION_SIGNED,
        ACCIDENT_CLUSTER
    }

    public enum Section {
        INCLINES,
        CHAINS,
        CURVES_SIGNED,
        CURVES_ADVISORY,
        ACCIDENTS
    }

    public static final class Row {
        public final Kind kind;
        public final Section section;
        public final long osmId;
        public final String summary;
        public final MatchConfidence confidence;
        /** Tags that may be applied to the OSM layer (allowlisted keys only). */
        public final Map<String, String> tags;
        public final ChainPoint chainPoint;
        public final SafetyFinding safetyFinding;
        public final boolean signConfirmed;
        public final Double x;
        public final Double y;
        /** True when gradient spread suggests the mapper should split the way in JOSM. */
        public final boolean splitSuggested;
        /**
         * Structured review audit for incline rows (former incline:match_* /
         * incline:estimated_* / split bookkeeping). Null for non-incline rows.
         */
        public final InclineAudit inclineAudit;
        public boolean accepted;

        public Row(
                Kind kind,
                Section section,
                long osmId,
                String summary,
                MatchConfidence confidence,
                Map<String, String> tags,
                ChainPoint chainPoint,
                SafetyFinding safetyFinding,
                boolean signConfirmed,
                Double x,
                Double y,
                boolean splitSuggested,
                InclineAudit inclineAudit,
                boolean accepted) {
            this.kind = kind;
            this.section = section;
            this.osmId = osmId;
            this.summary = summary;
            this.confidence = confidence;
            this.tags = tags == null ? Map.of() : Map.copyOf(tags);
            this.chainPoint = chainPoint;
            this.safetyFinding = safetyFinding;
            this.signConfirmed = signConfirmed;
            this.x = x;
            this.y = y;
            this.splitSuggested = splitSuggested;
            this.inclineAudit = inclineAudit;
            this.accepted = accepted;
            if (this.tags.containsKey("hazard") && !signConfirmed) {
                throw new IllegalArgumentException(
                        "Review row must not carry hazard=* without sign confirmation");
            }
            for (String key : this.tags.keySet()) {
                if (AppliedTags.FORBIDDEN_LEGACY_KEYS.contains(key)) {
                    throw new IllegalArgumentException(
                            "Review row must not carry bookkeeping/legacy tag: " + key);
                }
            }
        }

        /** Convenience for non-incline rows (no audit payload). */
        public Row(
                Kind kind,
                Section section,
                long osmId,
                String summary,
                MatchConfidence confidence,
                Map<String, String> tags,
                ChainPoint chainPoint,
                SafetyFinding safetyFinding,
                boolean signConfirmed,
                Double x,
                Double y,
                boolean splitSuggested,
                boolean accepted) {
            this(
                    kind,
                    section,
                    osmId,
                    summary,
                    confidence,
                    tags,
                    chainPoint,
                    safetyFinding,
                    signConfirmed,
                    x,
                    y,
                    splitSuggested,
                    null,
                    accepted);
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public List<Row> rows() {
        return rows;
    }

    public static ReviewModel fromEngine(
            List<WaySuggestion> suggestions,
            List<ChainPoint> chainPoints,
            List<SafetyFinding> safetyFindings) {
        ReviewModel model = new ReviewModel();
        for (WaySuggestion s : suggestions) {
            if (!s.isApplicable()) {
                String existing = s.match().way().existingIncline().orElse("?");
                String proposed =
                        s.segments().isEmpty() ? "?" : s.segments().get(0).inclineTag();
                InclineAudit audit = InclineAudit.from(s);
                model.rows.add(
                        new Row(
                                Kind.DISCREPANCY,
                                Section.INCLINES,
                                s.match().way().id(),
                                "existing "
                                        + existing
                                        + " vs suggested "
                                        + proposed
                                        + " (not overwritten)",
                                s.match().confidence(),
                                Map.of(),
                                null,
                                null,
                                false,
                                null,
                                null,
                                false,
                                audit,
                                false));
                continue;
            }
            Map<String, String> tags = s.tagsToAdd();
            InclineAudit audit = InclineAudit.from(s);
            String incline = tags.getOrDefault(AppliedTags.INCLINE, "?");
            String summary =
                    "incline="
                            + incline
                            + " ("
                            + s.match().confidence().name().toLowerCase(Locale.ROOT)
                            + ")"
                            + (s.split() ? " — split suggested" : "")
                            + "; "
                            + (audit == null ? "" : audit.summaryLine());
            model.rows.add(
                    new Row(
                            Kind.WAY_TAGS,
                            Section.INCLINES,
                            s.match().way().id(),
                            summary,
                            s.match().confidence(),
                            tags,
                            null,
                            null,
                            false,
                            null,
                            null,
                            s.split(),
                            audit,
                            s.match().confidence() == MatchConfidence.HIGH));
        }
        for (ChainPoint cp : chainPoints) {
            model.rows.add(
                    new Row(
                            Kind.CHAIN_NODE,
                            Section.CHAINS,
                            cp.wayId() == null ? 0 : cp.wayId(),
                            "chain_advisory="
                                    + cp.kind().tagValue()
                                    + " — "
                                    + cp.reason(),
                            MatchConfidence.MEDIUM,
                            chainTags(cp),
                            cp,
                            null,
                            false,
                            cp.x(),
                            cp.y(),
                            false,
                            false));
        }
        if (safetyFindings != null) {
            for (SafetyFinding f : safetyFindings) {
                model.rows.add(fromSafety(f));
            }
        }
        return model;
    }

    /** Backwards-compatible overload. */
    public static ReviewModel fromEngine(
            List<WaySuggestion> suggestions, List<ChainPoint> chainPoints) {
        return fromEngine(suggestions, chainPoints, List.of());
    }

    private static Row fromSafety(SafetyFinding f) {
        Kind kind;
        Section section;
        switch (f.kind()) {
            case CURVE_SIGNED -> {
                kind = Kind.CURVE_SIGNED;
                section = Section.CURVES_SIGNED;
            }
            case CURVE_ADVISORY -> {
                kind = Kind.CURVE_ADVISORY;
                section = Section.CURVES_ADVISORY;
            }
            case JUNCTION_SIGNED -> {
                kind = Kind.JUNCTION_SIGNED;
                section = Section.CURVES_SIGNED;
            }
            case ACCIDENT_CLUSTER -> {
                kind = Kind.ACCIDENT_CLUSTER;
                section =
                        f.signConfirmed()
                                ? Section.CURVES_SIGNED
                                : Section.ACCIDENTS;
            }
            default -> throw new IllegalStateException("unknown kind");
        }
        return new Row(
                kind,
                section,
                f.wayId() == null ? 0 : f.wayId(),
                f.summary(),
                f.confidence(),
                f.tags(),
                null,
                f,
                f.signConfirmed(),
                f.x(),
                f.y(),
                false,
                f.signConfirmed() && f.confidence() == MatchConfidence.HIGH);
    }

    private static Map<String, String> chainTags(ChainPoint cp) {
        String note =
                cp.kind() == no.nvdbincline.core.model.ChainKind.REMOVE
                        ? InclineTags.CHAIN_NOTE_REMOVE
                        : InclineTags.CHAIN_NOTE_FIT;
        return AppliedTags.chain(cp.kind().tagValue(), note);
    }

    public void acceptAllHighConfidence() {
        for (Row r : rows) {
            if (r.confidence == MatchConfidence.HIGH
                    && (r.kind == Kind.WAY_TAGS
                            || r.kind == Kind.CURVE_SIGNED
                            || r.kind == Kind.JUNCTION_SIGNED
                            || (r.kind == Kind.ACCIDENT_CLUSTER && r.signConfirmed))) {
                r.accepted = true;
            }
        }
    }

    public void acceptAll() {
        for (Row r : rows) {
            if (r.kind != Kind.DISCREPANCY) {
                r.accepted = true;
            }
        }
    }

    public void rejectAll() {
        for (Row r : rows) {
            r.accepted = false;
        }
    }

    /** Rows that should become JOSM Commands. */
    public List<Row> acceptedRows() {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.accepted && r.kind != Kind.DISCREPANCY) {
                out.add(r);
            }
        }
        return out;
    }
}
