package no.nvdbincline.core.safety;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import no.nvdbincline.core.model.NvdbPointFeature;

/** Cluster accident points spatially; filter by lookback years. */
public final class AccidentClusterer {
    public static final class Settings {
        public double clusterRadiusM = 50.0;
        public int minCount = 3;
        public int lookbackYears = 10;
    }

    public static final class Cluster {
        public final double x;
        public final double y;
        public final int count;
        public final String dateFrom;
        public final String dateTo;
        public final List<NvdbPointFeature> members;

        public Cluster(
                double x,
                double y,
                int count,
                String dateFrom,
                String dateTo,
                List<NvdbPointFeature> members) {
            this.x = x;
            this.y = y;
            this.count = count;
            this.dateFrom = dateFrom;
            this.dateTo = dateTo;
            this.members = List.copyOf(members);
        }
    }

    private AccidentClusterer() {}

    public static List<Cluster> cluster(List<NvdbPointFeature> accidents, Settings settings) {
        LocalDate cutoff = LocalDate.now().minusYears(settings.lookbackYears);
        List<NvdbPointFeature> filtered = new ArrayList<>();
        for (NvdbPointFeature a : accidents) {
            if (a.dateIso() == null || a.dateIso().isBlank()) {
                filtered.add(a);
                continue;
            }
            try {
                LocalDate d = LocalDate.parse(a.dateIso().substring(0, Math.min(10, a.dateIso().length())));
                if (!d.isBefore(cutoff)) {
                    filtered.add(a);
                }
            } catch (DateTimeParseException e) {
                filtered.add(a);
            }
        }
        boolean[] used = new boolean[filtered.size()];
        List<Cluster> out = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            if (used[i]) {
                continue;
            }
            List<NvdbPointFeature> group = new ArrayList<>();
            group.add(filtered.get(i));
            used[i] = true;
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int j = 0; j < filtered.size(); j++) {
                    if (used[j]) {
                        continue;
                    }
                    for (NvdbPointFeature g : group) {
                        if (dist(g, filtered.get(j)) <= settings.clusterRadiusM) {
                            group.add(filtered.get(j));
                            used[j] = true;
                            changed = true;
                            break;
                        }
                    }
                }
            }
            if (group.size() < settings.minCount) {
                continue;
            }
            double sx = 0;
            double sy = 0;
            String minD = null;
            String maxD = null;
            for (NvdbPointFeature p : group) {
                sx += p.x();
                sy += p.y();
                String d = p.dateIso();
                if (d != null && !d.isBlank()) {
                    if (minD == null || d.compareTo(minD) < 0) {
                        minD = d;
                    }
                    if (maxD == null || d.compareTo(maxD) > 0) {
                        maxD = d;
                    }
                }
            }
            out.add(
                    new Cluster(
                            sx / group.size(),
                            sy / group.size(),
                            group.size(),
                            minD == null ? "?" : minD.substring(0, Math.min(10, minD.length())),
                            maxD == null ? "?" : maxD.substring(0, Math.min(10, maxD.length())),
                            group));
        }
        out.sort(Comparator.comparingInt((Cluster c) -> c.count).reversed());
        return out;
    }

    private static double dist(NvdbPointFeature a, NvdbPointFeature b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }
}
