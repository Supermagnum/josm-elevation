package no.nvdbincline.core.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.Polyline;

/**
 * Uniform grid over projected metres so OSM↔NVDB geometry scoring only considers nearby
 * candidates instead of every link in the kommune.
 */
final class LinkSpatialIndex {
    private final double cellSizeM;
    private final Map<Long, List<NvdbLink>> cells = new HashMap<>();

    LinkSpatialIndex(List<NvdbLink> links, double cellSizeM) {
        this.cellSizeM = Math.max(cellSizeM, 1.0);
        for (NvdbLink lk : links) {
            insert(lk);
        }
    }

    List<NvdbLink> queryNear(Polyline way, double padM) {
        double[] e = way.envelope();
        int i0 = cell(e[0] - padM);
        int j0 = cell(e[1] - padM);
        int i1 = cell(e[2] + padM);
        int j1 = cell(e[3] + padM);
        // Identity set: several segments can share veglenkesekvensId / similar keys.
        Set<NvdbLink> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                List<NvdbLink> bucket = cells.get(key(i, j));
                if (bucket == null) {
                    continue;
                }
                unique.addAll(bucket);
            }
        }
        return new ArrayList<>(unique);
    }

    private void insert(NvdbLink lk) {
        double[] e = lk.line().envelope();
        int i0 = cell(e[0]);
        int j0 = cell(e[1]);
        int i1 = cell(e[2]);
        int j1 = cell(e[3]);
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                cells.computeIfAbsent(key(i, j), k -> new ArrayList<>()).add(lk);
            }
        }
    }

    private int cell(double metres) {
        return (int) Math.floor(metres / cellSizeM);
    }

    private static long key(int i, int j) {
        return (((long) i) << 32) ^ (j & 0xffffffffL);
    }
}
