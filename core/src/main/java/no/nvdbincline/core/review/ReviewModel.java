package no.nvdbincline.core.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.nvdbincline.core.model.ChainPoint;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.WaySuggestion;
import no.nvdbincline.core.tag.InclineTags;

/**
 * Review-dialog row model and accept/reject filtering — pure JVM, no Swing/JOSM.
 * Only accepted rows should be turned into edit commands by the plugin adapter.
 */
public final class ReviewModel {
    public enum Kind {
        WAY_TAGS,
        CHAIN_NODE,
        DISCREPANCY
    }

    public static final class Row {
        public final Kind kind;
        public final long osmId;
        public final String summary;
        public final MatchConfidence confidence;
        public final Map<String, String> tags;
        public final ChainPoint chainPoint;
        public boolean accepted;

        public Row(
                Kind kind,
                long osmId,
                String summary,
                MatchConfidence confidence,
                Map<String, String> tags,
                ChainPoint chainPoint,
                boolean accepted) {
            this.kind = kind;
            this.osmId = osmId;
            this.summary = summary;
            this.confidence = confidence;
            this.tags = tags == null ? Map.of() : Map.copyOf(tags);
            this.chainPoint = chainPoint;
            this.accepted = accepted;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public List<Row> rows() {
        return rows;
    }

    public static ReviewModel fromEngine(
            List<WaySuggestion> suggestions, List<ChainPoint> chainPoints) {
        ReviewModel model = new ReviewModel();
        for (WaySuggestion s : suggestions) {
            if (!s.isApplicable()) {
                String existing = s.match().way().existingIncline().orElse("?");
                String proposed =
                        s.segments().isEmpty() ? "?" : s.segments().get(0).inclineTag();
                model.rows.add(
                        new Row(
                                Kind.DISCREPANCY,
                                s.match().way().id(),
                                "existing "
                                        + existing
                                        + " vs suggested "
                                        + proposed
                                        + " (not overwritten)",
                                s.match().confidence(),
                                Map.of(),
                                null,
                                false));
                continue;
            }
            String incline = s.tagsToAdd().getOrDefault("incline", s.tagsToAdd().get("incline:suggested"));
            model.rows.add(
                    new Row(
                            Kind.WAY_TAGS,
                            s.match().way().id(),
                            "incline="
                                    + incline
                                    + " ("
                                    + s.match().confidence().name().toLowerCase()
                                    + ")",
                            s.match().confidence(),
                            s.tagsToAdd(),
                            null,
                            s.match().confidence() == MatchConfidence.HIGH));
        }
        for (ChainPoint cp : chainPoints) {
            model.rows.add(
                    new Row(
                            Kind.CHAIN_NODE,
                            cp.wayId() == null ? 0 : cp.wayId(),
                            "chain_advisory=" + cp.kind().tagValue() + " — " + cp.reason(),
                            MatchConfidence.MEDIUM,
                            chainTags(cp),
                            cp,
                            false));
        }
        return model;
    }

    private static Map<String, String> chainTags(ChainPoint cp) {
        String note =
                cp.kind() == no.nvdbincline.core.model.ChainKind.REMOVE
                        ? InclineTags.CHAIN_NOTE_REMOVE
                        : InclineTags.CHAIN_NOTE_FIT;
        return Map.of(
                "note",
                note,
                "chain_advisory",
                cp.kind().tagValue(),
                "chain_advisory:source",
                InclineTags.SOURCE,
                "chain_advisory:reason",
                cp.reason().length() > 250 ? cp.reason().substring(0, 250) : cp.reason());
    }

    public void acceptAllHighConfidence() {
        for (Row r : rows) {
            if (r.kind == Kind.WAY_TAGS && r.confidence == MatchConfidence.HIGH) {
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
