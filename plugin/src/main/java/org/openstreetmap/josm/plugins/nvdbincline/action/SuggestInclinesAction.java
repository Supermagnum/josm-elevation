package org.openstreetmap.josm.plugins.nvdbincline.action;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import no.nvdbincline.core.osm.PbfHighwayExtractor;
import no.nvdbincline.core.ProgressCallback;
import no.nvdbincline.core.SuggestionEngine;
import no.nvdbincline.core.area.AreaSelection;
import no.nvdbincline.core.match.WayMatcher;
import no.nvdbincline.core.completion.KommuneCompletionRecord;
import no.nvdbincline.core.completion.KommuneCompletionStore;
import no.nvdbincline.core.completion.ReviewSessionStats;
import no.nvdbincline.core.geo.Bbox;
import no.nvdbincline.core.kommune.KommuneCatalog;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.NvdbPointFeature;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.SafetyFinding;
import no.nvdbincline.core.review.ReviewModel;
import no.nvdbincline.core.safety.SafetyAnalyzer;
import no.nvdbincline.core.tag.ExistingTagCoverage;
import no.nvdbincline.core.tag.ExistingTagPolicy;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.io.BoundingBoxDownloader;
import org.openstreetmap.josm.io.OsmApiException;
import org.openstreetmap.josm.io.OsmTransferException;
import org.openstreetmap.josm.plugins.nvdbincline.NvdbInclinePreferences;
import org.openstreetmap.josm.plugins.nvdbincline.command.SuggestionApplier;
import org.openstreetmap.josm.plugins.nvdbincline.dialog.AreaSelectionDialog;
import org.openstreetmap.josm.plugins.nvdbincline.dialog.ReviewDialog;
import org.openstreetmap.josm.plugins.nvdbincline.io.GeofabrikNorwayExtract;
import org.openstreetmap.josm.plugins.nvdbincline.io.LayerAdapter;
import org.openstreetmap.josm.plugins.nvdbincline.io.LocalDataPaths;
import org.openstreetmap.josm.plugins.nvdbincline.io.NvdbClient;
import org.openstreetmap.josm.plugins.nvdbincline.io.OsmDatasetFromExtract;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;
import org.xml.sax.SAXException;

/**
 * Menu action: choose area (layer / bbox / kommune), optionally download OSM
 * via the OSM API (not Overpass), fetch NVDB, review, apply undoable Commands.
 *
 * <p>Long network work runs in {@link PleaseWaitRunnable} so the user sees a
 * cancellable progress dialog instead of a frozen UI.
 */
public class SuggestInclinesAction extends JosmAction {

    public SuggestInclinesAction() {
        super(
                tr("Suggest inclines from NVDB…"),
                "dialogs/nvdb_incline",
                tr(
                        "Choose area (current layer, bbox, or kommune), suggest incline=* from NVDB"
                                + " (review before apply; never uploads)"),
                Shortcut.registerShortcut(
                        "tools:nvdb_incline_suggest",
                        tr("Tools: Suggest inclines from NVDB"),
                        KeyEvent.VK_N,
                        Shortcut.ALT_SHIFT),
                true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        KommuneCatalog catalog;
        KommuneCompletionStore store;
        try {
            catalog = KommuneCatalog.loadDefault();
            store = KommuneCompletionStore.load(LocalDataPaths.completionFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Failed to load kommune catalog / completion store: {0}", ex.getMessage()),
                    tr("NVDB incline"),
                    JOptionPane.ERROR_MESSAGE);
            Logging.error(ex);
            return;
        }

        OsmDataLayer existing = MainApplication.getLayerManager().getActiveDataLayer();
        boolean preferKommune = existing == null || existing.getDataSet() == null;

        AreaSelectionDialog.Result areaResult =
                AreaSelectionDialog.show(
                        MainApplication.getMainFrame(), catalog, store, preferKommune);
        if (areaResult == null) {
            return;
        }
        persistStore(areaResult.store());

        MainApplication.worker.submit(new SuggestPipeline(areaResult));
    }

    /**
     * Background pipeline with a Please Wait dialog. Uses the OSM API
     * ({@link BoundingBoxDownloader}) — not Overpass — for map downloads.
     */
    private static final class SuggestPipeline extends PleaseWaitRunnable {
        private final AreaSelectionDialog.Result areaResult;
        private final AreaSelection area;

        private DataSet editDataSet;
        private OsmDataLayer newLayerToAdd;
        private ReviewModel reviewModel;
        private ExistingTagCoverage lastCoverage;
        private int matched;
        private int unmatched;
        private String infoMessage;
        private String errorMessage;
        private boolean cancelledByUser;
        private KommuneCompletionStore store;

        SuggestPipeline(AreaSelectionDialog.Result areaResult) {
            super(tr("NVDB incline"));
            this.areaResult = areaResult;
            this.area = areaResult.selection();
            this.store = areaResult.store();
        }

        @Override
        protected void realRun() throws SAXException, IOException, OsmTransferException {
            try {
                runPipeline();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                cancelledByUser = true;
            }
        }

        private void runPipeline() throws IOException, InterruptedException, OsmTransferException {
            ProgressMonitor progress = progressMonitor;
            progress.setTicksCount(100);
            if (stopIfCancelled(progress)) {
                return;
            }

            NvdbClient client = new NvdbClient();
            List<NvdbLink> links;
            List<NvdbPointFeature> signs = List.of();
            List<NvdbPointFeature> accidents = List.of();
            double[] filterBbox = null;
            String layerNameHint = "NVDB incline";

            progress.indeterminateSubTask(tr("Contacting NVDB (Statens vegvesen)…"));
            progress.setTicks(5);
            if (stopIfCancelled(progress)) {
                return;
            }

            switch (area.mode()) {
                case CURRENT_LAYER -> {
                    OsmDataLayer layer = MainApplication.getLayerManager().getActiveDataLayer();
                    if (layer == null || layer.getDataSet() == null) {
                        infoMessage =
                                tr(
                                        "Current-layer mode needs an OSM edit layer.\n"
                                                + "Choose “By kommune” to download one, or use File → Download data… first.");
                        return;
                    }
                    editDataSet = layer.getDataSet();
                    double[] bbox = LayerAdapter.bboxLonLat(editDataSet);
                    progress.subTask(tr("Downloading NVDB road links for layer bbox…"));
                    links = client.fetchSegmentedLinks(bbox[0], bbox[1], bbox[2], bbox[3]);
                    progress.setTicks(40);
                    if (stopIfCancelled(progress)) {
                        return;
                    }
                    progress.subTask(tr("Downloading NVDB warning signs…"));
                    signs = fetchSigns(client, bbox);
                    progress.setTicks(55);
                    if (stopIfCancelled(progress)) {
                        return;
                    }
                    progress.subTask(tr("Downloading NVDB accident points…"));
                    accidents = fetchAccidents(client, bbox);
                    progress.setTicks(65);
                }
                case BBOX -> {
                    filterBbox = area.bboxLonLat();
                    layerNameHint = "NVDB bbox";
                    progress.subTask(tr("Downloading NVDB road links for bbox…"));
                    links =
                            client.fetchSegmentedLinks(
                                    filterBbox[0], filterBbox[1], filterBbox[2], filterBbox[3]);
                    progress.setTicks(35);
                    if (stopIfCancelled(progress)) {
                        return;
                    }
                    progress.subTask(tr("Downloading NVDB warning signs…"));
                    signs = fetchSigns(client, filterBbox);
                    progress.setTicks(45);
                    if (stopIfCancelled(progress)) {
                        return;
                    }
                    progress.subTask(tr("Downloading NVDB accident points…"));
                    accidents = fetchAccidents(client, filterBbox);
                    progress.setTicks(50);
                }
                case KOMMUNE -> {
                    int kn = area.kommuneNummer();
                    layerNameHint = "NVDB " + area.kommuneNavn() + " (" + kn + ")";

                    var extractStatus =
                            GeofabrikNorwayExtract.status(java.time.Clock.systemUTC());
                    if (extractStatus.kind()
                            == no.nvdbincline.core.osm.LocalNorwayExtractStatus.Kind.MISSING) {
                        infoMessage =
                                tr(
                                        "Kommune mode needs a local Geofabrik Norway extract.\n\n"
                                                + "In the area dialog, click “Set up / refresh local Norway data…”"
                                                + " (~1.3 GB from download.geofabrik.de). This plugin does not"
                                                + " fall back to the OSM API for kommune mode (that caused 509"
                                                + " bandwidth errors and bbox border leakage).\n\n"
                                                + "{0}",
                                        extractStatus.detail());
                        return;
                    }
                    if (extractStatus.kind()
                                    == no.nvdbincline.core.osm.LocalNorwayExtractStatus.Kind.STALE
                            && !areaResult.allowStaleNorwayExtract()) {
                        infoMessage =
                                tr(
                                        "Local Norway OSM extract is stale ({0}).\n\n"
                                                + "Refresh it from the area dialog, or enable"
                                                + " “Allow stale local Norway extract”.",
                                        extractStatus.detail());
                        return;
                    }

                    no.nvdbincline.core.kommune.KommuneBoundary boundary;
                    try {
                        boundary =
                                no.nvdbincline.core.kommune.KommuneBoundaryCatalog.loadDefault()
                                        .require(kn);
                    } catch (IllegalArgumentException missingPoly) {
                        infoMessage = missingPoly.getMessage();
                        return;
                    }

                    progress.subTask(
                            tr(
                                    "Extracting OSM highways for {0} from local Norway PBF…",
                                    area.kommuneNavn()));
                    PbfHighwayExtractor.Result extracted;
                    try {
                        extracted =
                                PbfHighwayExtractor.extract(
                                        GeofabrikNorwayExtract.pbfFile(),
                                        boundary.polygon(),
                                        (phase, done, total) -> {
                                            if (progress.isCanceled()) {
                                                return false;
                                            }
                                            progress.subTask(
                                                    total > 0
                                                            ? tr(
                                                                    "{0} ({1}/{2})",
                                                                    phase, done, total)
                                                            : phase);
                                            return true;
                                        });
                    } catch (PbfHighwayExtractor.CancelledException cancelled) {
                        cancelledByUser = true;
                        return;
                    }
                    if (extracted.highways.isEmpty()) {
                        infoMessage =
                                tr(
                                        "No highway=* ways found inside Kartverket boundary for kommune {0}.",
                                        kn);
                        return;
                    }
                    editDataSet = OsmDatasetFromExtract.fromHighways(extracted.highways);
                    newLayerToAdd = new OsmDataLayer(editDataSet, layerNameHint, null);
                    progress.setTicks(30);
                    if (stopIfCancelled(progress)) {
                        return;
                    }

                    progress.subTask(
                            tr("Downloading NVDB road links for kommune {0}…", kn));
                    links = client.fetchSegmentedLinksByKommune(kn);
                    progress.setTicks(45);
                    if (stopIfCancelled(progress)) {
                        return;
                    }
                    if (links.isEmpty()) {
                        infoMessage = tr("NVDB returned no road links for kommune {0}.", kn);
                        return;
                    }
                    // Keep envelope for logging only — do not bbox-filter clipped ways.
                    filterBbox = boundary.polygon().envelope();
                    progress.subTask(tr("Downloading NVDB warning signs…"));
                    try {
                        signs =
                                client.fetchVegobjektPointsByKommune(
                                        SafetyAnalyzer.TYPE_SKILTPLATE, kn);
                    } catch (Exception signEx) {
                        Logging.warn(
                                "NVDB sign fetch failed (continuing without signs): "
                                        + signEx.getMessage());
                    }
                    progress.setTicks(55);
                    if (stopIfCancelled(progress)) {
                        return;
                    }
                    progress.subTask(tr("Downloading NVDB accident points…"));
                    try {
                        accidents =
                                client.fetchVegobjektPointsByKommune(
                                        SafetyAnalyzer.TYPE_TRAFIKKULYKKE, kn);
                    } catch (Exception accidentEx) {
                        Logging.warn(
                                "NVDB accident fetch failed (continuing without accidents): "
                                        + accidentEx.getMessage());
                    }
                    progress.setTicks(65);
                }
                default -> throw new IllegalStateException("unknown area mode");
            }

            if (stopIfCancelled(progress)) {
                return;
            }
            if (area.mode() != AreaSelection.Mode.KOMMUNE) {
                progress.subTask(tr("Preparing OSM edit layer…"));
                editDataSet =
                        ensureOsmData(
                                area,
                                filterBbox,
                                areaResult.downloadOsm(),
                                layerNameHint,
                                progress);
                progress.setTicks(80);
                if (editDataSet == null) {
                    if (infoMessage == null && errorMessage == null) {
                        infoMessage = tr("No OSM data available for this area.");
                    }
                    return;
                }
            } else {
                progress.setTicks(80);
            }
            if (stopIfCancelled(progress)) {
                return;
            }

            List<OsmWayGeom> ways = LayerAdapter.extractWays(editDataSet);
            // Kommune ways are already Kartverket-polygon clipped — do not re-filter by envelope.
            if (filterBbox != null && area.mode() != AreaSelection.Mode.KOMMUNE) {
                ways =
                        Bbox.filterWaysInBboxLonLat(
                                ways,
                                filterBbox[0],
                                filterBbox[1],
                                filterBbox[2],
                                filterBbox[3]);
            }
            if (ways.isEmpty()) {
                infoMessage =
                        filterBbox == null
                                ? tr("No highway=* ways found in the active layer.")
                                : tr(
                                        "No highway=* ways in the area bbox ({0},{1} – {2},{3}).",
                                        String.format(Locale.ROOT, "%.5f", filterBbox[0]),
                                        String.format(Locale.ROOT, "%.5f", filterBbox[1]),
                                        String.format(Locale.ROOT, "%.5f", filterBbox[2]),
                                        String.format(Locale.ROOT, "%.5f", filterBbox[3]));
                return;
            }

            progress.subTask(
                    tr(
                            "Matching {0} OSM ways to NVDB and computing inclines…",
                            ways.size()));
            ProgressCallback computeProgress =
                    (phase, done, total) -> {
                        if (progress.isCanceled()) {
                            return false;
                        }
                        if (total > 0) {
                            // Keep overall bar in the 80–90% band while matching/computing.
                            int mapped = 80 + Math.min(10, (10 * done) / Math.max(total, 1));
                            progress.setTicks(mapped);
                            progress.subTask(
                                    tr("{0} ({1}/{2})", phase, done, total));
                        } else {
                            progress.subTask(phase);
                        }
                        return true;
                    };
            SuggestionEngine.Output out;
            try {
                out =
                        SuggestionEngine.run(
                                ways, links, new SuggestionEngine.Config(), computeProgress);
            } catch (WayMatcher.CancelledException cancelled) {
                cancelledByUser = true;
                return;
            }
            progress.setTicks(90);
            if (stopIfCancelled(progress)) {
                return;
            }

            ExistingTagCoverage coverage =
                    ExistingTagCoverage.scanWays(
                            out.matchResult.matches.stream()
                                    .map(m -> m.way())
                                    .toList());
            if (editDataSet != null) {
                coverage = mergeNodeTagCoverage(editDataSet, coverage);
            }
            lastCoverage = coverage;
            if (area.isKommune()) {
                KommuneCompletionRecord withCov =
                        store.getOrEmpty(area.kommuneNummer()).withCoverage(coverage);
                store.put(withCov);
            }

            progress.subTask(tr("Analysing signs, curves and accidents…"));
            List<SafetyFinding> safety =
                    SafetyAnalyzer.analyze(ways, signs, accidents, new SafetyAnalyzer.Settings());
            reviewModel = ReviewModel.fromEngine(out.suggestions, out.chainPoints, safety);
            matched = out.matchResult.matches.size();
            unmatched = out.matchResult.unmatchedOsm.size();
            progress.setTicks(100);

            if (reviewModel.rows().isEmpty()) {
                infoMessage = tr("No suggestions for this area.");
            }
        }

        @Override
        protected void finish() {
            if (cancelledByUser) {
                return;
            }
            if (newLayerToAdd != null) {
                MainApplication.getLayerManager().addLayer(newLayerToAdd);
                editDataSet = newLayerToAdd.getDataSet();
                newLayerToAdd = null;
            }
            if (errorMessage != null) {
                JOptionPane.showMessageDialog(
                        MainApplication.getMainFrame(),
                        errorMessage,
                        tr("NVDB incline"),
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (area.isKommune() && lastCoverage != null) {
                // Persist coverage even when there are no review rows.
                persistStore(store);
            }
            if (infoMessage != null) {
                JOptionPane.showMessageDialog(
                        MainApplication.getMainFrame(),
                        infoMessage,
                        tr("NVDB incline"),
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (reviewModel == null || editDataSet == null) {
                return;
            }

            if (area.isKommune() && lastCoverage != null) {
                maybeOfferCoverageReviewPrompt(store, area.kommuneNummer(), lastCoverage);
            }

            boolean ok =
                    ReviewDialog.show(
                            MainApplication.getMainFrame(),
                            reviewModel,
                            matched,
                            unmatched,
                            editDataSet,
                            NvdbInclinePreferences.autoSplitVariableGradient());
            if (area.isKommune()) {
                updateCompletion(
                        store,
                        area.kommuneNummer(),
                        reviewModel,
                        matched,
                        unmatched,
                        ok,
                        areaResult.dismissUnmatched());
                persistStore(store);
            }
            if (!ok) {
                return;
            }
            boolean autoSplit = NvdbInclinePreferences.autoSplitVariableGradient();
            int applied =
                    SuggestionApplier.applyAccepted(
                            editDataSet, reviewModel.acceptedRows(), autoSplit);
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr(
                            "Applied {0} suggestion(s) as undoable edits.\n"
                                    + "Check source:incline=nvdb_estimate / source:hazard=nvdb_sign / advisories.\n"
                                    + "hazard=* is only applied when an NVDB warning sign matched.\n"
                                    + "{1}"
                                    + "Upload manually from JOSM if you choose to. This plugin never uploads.",
                            applied,
                            autoSplit
                                    ? "Split-recommended inclines were split into sub-ways where possible.\n"
                                    : "Split suggestions are review-UI only — split ways yourself in JOSM if needed.\n"),
                    tr("NVDB incline"),
                    JOptionPane.INFORMATION_MESSAGE);
        }

        @Override
        protected void cancel() {
            cancelledByUser = true;
            Logging.info("nvdb_incline: cancelled by user");
        }

        private boolean stopIfCancelled(ProgressMonitor progress) {
            if (progress.isCanceled()) {
                cancelledByUser = true;
                return true;
            }
            return false;
        }

        /**
         * Download OSM via the official OSM API (same path as File→Download), never Overpass.
         * Runs on the Please Wait worker thread with live progress text.
         */
        private DataSet ensureOsmData(
                AreaSelection areaSel,
                double[] filterBbox,
                boolean downloadOsm,
                String layerName,
                ProgressMonitor progress)
                throws OsmTransferException {
            OsmDataLayer layer = MainApplication.getLayerManager().getActiveDataLayer();
            if (areaSel.mode() == AreaSelection.Mode.CURRENT_LAYER) {
                return layer == null ? null : layer.getDataSet();
            }
            if (filterBbox == null) {
                return layer == null ? null : layer.getDataSet();
            }

            List<OsmWayGeom> existingWays =
                    layer == null || layer.getDataSet() == null
                            ? List.of()
                            : LayerAdapter.extractWays(layer.getDataSet());
            List<OsmWayGeom> inBbox =
                    Bbox.filterWaysInBboxLonLat(
                            existingWays, filterBbox[0], filterBbox[1], filterBbox[2], filterBbox[3]);

            boolean needDownload = downloadOsm && inBbox.isEmpty();
            if (!needDownload) {
                if (layer == null || layer.getDataSet() == null) {
                    infoMessage =
                            tr(
                                    "No OSM roads in this area. Enable “Download missing OSM roads” "
                                            + "or download the area with File → Download data… first.");
                    return null;
                }
                return layer.getDataSet();
            }

            List<double[]> tiles;
            try {
                tiles = Bbox.tilesForOsmApi(filterBbox);
            } catch (IllegalArgumentException tooBig) {
                infoMessage =
                        tr(
                                "This kommune’s road extent is too large for automatic OSM API download "
                                        + "({0}).\n\n"
                                        + "Download a smaller area with File → Download data…, "
                                        + "or import an extract from Geofabrik / OSM community export, "
                                        + "then run Suggest again with “Current edit layer”.\n\n"
                                        + "Detail: {1}",
                                String.format(
                                        Locale.ROOT,
                                        "%.2f sq deg",
                                        Bbox.areaSquareDegrees(filterBbox)),
                                tooBig.getMessage());
                return null;
            }

            progress.appendLogMessage(
                    tr(
                            "OSM bbox {0},{1} – {2},{3} ({4} sq deg, starting with {5} tile(s); "
                                    + "splits if “too many nodes”; paces requests and retries on 509)",
                            String.format(Locale.ROOT, "%.5f", filterBbox[0]),
                            String.format(Locale.ROOT, "%.5f", filterBbox[1]),
                            String.format(Locale.ROOT, "%.5f", filterBbox[2]),
                            String.format(Locale.ROOT, "%.5f", filterBbox[3]),
                            String.format(Locale.ROOT, "%.3f", Bbox.areaSquareDegrees(filterBbox)),
                            tiles.size()));

            DataSet downloaded = new DataSet();
            int[] counters = {0, tiles.size()}; // done-ish progress, planned
            OsmDownloadPace pace = new OsmDownloadPace();
            for (double[] t : tiles) {
                if (stopIfCancelled(progress)) {
                    return null;
                }
                downloadOsmTileAdaptive(t, downloaded, progress, counters, pace, 0);
            }
            if (stopIfCancelled(progress)) {
                return null;
            }
            if (downloaded.getWays().isEmpty()) {
                infoMessage = tr("OSM download returned no ways for this area.");
                return null;
            }

            boolean newLayer = layer == null;
            if (newLayer) {
                newLayerToAdd = new OsmDataLayer(downloaded, layerName, null);
                return downloaded;
            }
            final DataSet target = layer.getDataSet();
            final DataSet src = downloaded;
            try {
                SwingUtilities.invokeAndWait(
                        () -> target.mergeFrom(src, progress.createSubTaskMonitor(1, false)));
            } catch (Exception ex) {
                throw new OsmTransferException(ex);
            }
            return target;
        }

        /**
         * Download one tile; if OSM returns 400 “too many nodes” / area too large,
         * split into quadrants and retry (dense Norwegian road networks hit the
         * 50k-node cap before the 0.25°² area cap). On HTTP 509 bandwidth limit,
         * wait and retry (message often includes “try again in N seconds”).
         */
        private void downloadOsmTileAdaptive(
                double[] tile,
                DataSet into,
                ProgressMonitor progress,
                int[] counters,
                OsmDownloadPace pace,
                int depth)
                throws OsmTransferException {
            if (stopIfCancelled(progress)) {
                return;
            }
            counters[0]++;
            progress.subTask(
                    tr(
                            "Downloading OSM tile #{0} (depth {1}, {2} sq deg) from openstreetmap.org…",
                            counters[0],
                            depth,
                            String.format(Locale.ROOT, "%.4f", Bbox.areaSquareDegrees(tile))));
            Bounds tileBounds =
                    new Bounds(new LatLon(tile[1], tile[0]), new LatLon(tile[3], tile[2]));

            int bandwidthAttempts = 0;
            while (true) {
                if (stopIfCancelled(progress)) {
                    return;
                }
                waitOsmApiPace(pace, progress);
                if (stopIfCancelled(progress)) {
                    return;
                }
                try {
                    pace.markRequest();
                    BoundingBoxDownloader downloader = new BoundingBoxDownloader(tileBounds);
                    DataSet part =
                            downloader.parseOsm(progress.createSubTaskMonitor(1, false));
                    if (part != null && !part.getWays().isEmpty()) {
                        into.mergeFrom(part);
                    }
                    return;
                } catch (OsmApiException ex) {
                    if (isOsmBboxTooHeavy(ex) && depth < 8) {
                        Logging.info(
                                "nvdb_incline: OSM tile too dense ("
                                        + ex.getErrorHeader()
                                        + "); splitting (depth "
                                        + depth
                                        + ")");
                        progress.appendLogMessage(
                                tr("Tile too dense — splitting into 4 smaller downloads…"));
                        for (double[] q : Bbox.quadrants(tile)) {
                            if (stopIfCancelled(progress)) {
                                return;
                            }
                            if (Bbox.areaSquareDegrees(q) < 1e-10) {
                                continue;
                            }
                            downloadOsmTileAdaptive(
                                    q, into, progress, counters, pace, depth + 1);
                        }
                        return;
                    }
                    if (isOsmBandwidthLimit(ex)
                            && bandwidthAttempts < OsmDownloadPace.MAX_BANDWIDTH_RETRIES) {
                        bandwidthAttempts++;
                        int waitSec = parseOsmRetrySeconds(ex, OsmDownloadPace.DEFAULT_509_WAIT_SEC);
                        // Extra second after stated wait; grow slightly on repeated 509s.
                        waitSec += 1 + (bandwidthAttempts - 1) * 5;
                        Logging.info(
                                "nvdb_incline: OSM 509 bandwidth limit; waiting "
                                        + waitSec
                                        + "s (attempt "
                                        + bandwidthAttempts
                                        + ")");
                        progress.subTask(
                                tr(
                                        "OSM bandwidth limit (509) — waiting {0}s, then retry"
                                                + " ({1}/{2})…",
                                        waitSec,
                                        bandwidthAttempts,
                                        OsmDownloadPace.MAX_BANDWIDTH_RETRIES));
                        progress.appendLogMessage(
                                tr(
                                        "openstreetmap.org asked us to wait {0}s (downloaded too much).",
                                        waitSec));
                        if (!sleepCancelable(waitSec * 1000L, progress)) {
                            return;
                        }
                        progress.subTask(
                                tr(
                                        "Retrying OSM tile #{0} after bandwidth wait…",
                                        counters[0]));
                        continue;
                    }
                    if (isOsmBandwidthLimit(ex)) {
                        throw new OsmTransferException(
                                tr(
                                        "OpenStreetMap API bandwidth limit (HTTP 509) after {0}"
                                                + " retries.\n\n"
                                                + "Wait a few minutes, then either:\n"
                                                + "• Run Suggest again (downloads resume with pacing), or\n"
                                                + "• Download a smaller area with File → Download data…,"
                                                + " or import a Geofabrik extract, then use"
                                                + " “Current edit layer”.\n\n"
                                                + "Server said: {1}",
                                        OsmDownloadPace.MAX_BANDWIDTH_RETRIES,
                                        ex.getErrorHeader() == null
                                                ? ex.getMessage()
                                                : ex.getErrorHeader()),
                                ex);
                    }
                    throw ex;
                }
            }
        }

        /** Shared pacing state for one kommune/bbox OSM download run. */
        private static final class OsmDownloadPace {
            /** Minimum gap between OSM map API calls (editing API is not for bulk dumps). */
            static final long MIN_GAP_MS = 2000L;
            static final int MAX_BANDWIDTH_RETRIES = 6;
            static final int DEFAULT_509_WAIT_SEC = 30;

            private long lastRequestMs;

            void markRequest() {
                lastRequestMs = System.currentTimeMillis();
            }

            long millisUntilNextAllowed() {
                if (lastRequestMs <= 0) {
                    return 0;
                }
                long elapsed = System.currentTimeMillis() - lastRequestMs;
                return Math.max(0L, MIN_GAP_MS - elapsed);
            }
        }

        private void waitOsmApiPace(OsmDownloadPace pace, ProgressMonitor progress) {
            long wait = pace.millisUntilNextAllowed();
            if (wait <= 0) {
                return;
            }
            progress.subTask(tr("Pacing OSM API requests ({0} ms)…", wait));
            if (!sleepCancelable(wait, progress)) {
                cancelledByUser = true;
            }
        }

        /** @return false if cancelled while sleeping */
        private boolean sleepCancelable(long totalMs, ProgressMonitor progress) {
            long deadline = System.currentTimeMillis() + Math.max(0L, totalMs);
            while (true) {
                if (progress.isCanceled()) {
                    cancelledByUser = true;
                    return false;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return true;
                }
                try {
                    Thread.sleep(Math.min(left, 250L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    cancelledByUser = true;
                    return false;
                }
            }
        }

        private static boolean isOsmBandwidthLimit(OsmApiException ex) {
            if (ex.getResponseCode() == 509) {
                return true;
            }
            String all = osmErrorText(ex).toLowerCase(Locale.ROOT);
            return all.contains("bandwidth")
                    || all.contains("downloaded too much")
                    || all.contains("too much data");
        }

        private static int parseOsmRetrySeconds(OsmApiException ex, int fallbackSec) {
            String all = osmErrorText(ex);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile(
                                    "(?i)(?:in|om)\\s+(\\d+)\\s*(?:seconds?|sekunder?)")
                            .matcher(all);
            if (m.find()) {
                try {
                    int sec = Integer.parseInt(m.group(1));
                    if (sec > 0 && sec < 600) {
                        return sec;
                    }
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            return fallbackSec;
        }

        private static String osmErrorText(OsmApiException ex) {
            String h = ex.getErrorHeader() == null ? "" : ex.getErrorHeader();
            String m = ex.getMessage() == null ? "" : ex.getMessage();
            String body = ex.getErrorBody() == null ? "" : ex.getErrorBody();
            return h + " " + m + " " + body;
        }

        private static boolean isOsmBboxTooHeavy(OsmApiException ex) {
            if (ex.getResponseCode() != 400) {
                return false;
            }
            String all = osmErrorText(ex).toLowerCase(Locale.ROOT);
            return all.contains("too many nodes")
                    || all.contains("too large")
                    || all.contains("for stort")
                    || all.contains("ødelagt forespørsel");
        }
    }

    private static List<NvdbPointFeature> fetchSigns(NvdbClient client, double[] bbox) {
        try {
            return client.fetchVegobjektPoints(
                    SafetyAnalyzer.TYPE_SKILTPLATE, bbox[0], bbox[1], bbox[2], bbox[3]);
        } catch (Exception signEx) {
            Logging.warn("NVDB sign fetch failed (continuing without signs): " + signEx.getMessage());
            return List.of();
        }
    }

    private static List<NvdbPointFeature> fetchAccidents(NvdbClient client, double[] bbox) {
        try {
            return client.fetchVegobjektPoints(
                    SafetyAnalyzer.TYPE_TRAFIKKULYKKE, bbox[0], bbox[1], bbox[2], bbox[3]);
        } catch (Exception accidentEx) {
            Logging.warn(
                    "NVDB accident fetch failed (continuing without accidents): "
                            + accidentEx.getMessage());
            return List.of();
        }
    }

    private static ExistingTagCoverage mergeNodeTagCoverage(
            DataSet ds, ExistingTagCoverage base) {
        int hazard = 0;
        int pluginH = 0;
        int otherH = 0;
        int chain = 0;
        for (org.openstreetmap.josm.data.osm.Node n : ds.getNodes()) {
            if (!n.isUsable()) {
                continue;
            }
            String hz = n.get("hazard");
            String src = n.get("source:hazard");
            var origin = ExistingTagPolicy.classifyHazard(hz, src);
            if (origin == ExistingTagPolicy.InclineOrigin.PLUGIN_NVDB_ESTIMATE) {
                hazard++;
                pluginH++;
            } else if (origin == ExistingTagPolicy.InclineOrigin.OTHER) {
                hazard++;
                otherH++;
            }
            if (n.get("chain_advisory") != null && !n.get("chain_advisory").isBlank()) {
                chain++;
            }
        }
        return base.withNodeTags(hazard, pluginH, otherH, chain);
    }

    /**
     * Substantial non-plugin incline coverage on OSM is a different signal from
     * the personal completion tracker — offer to mark reviewed, never auto-mark.
     */
    private static void maybeOfferCoverageReviewPrompt(
            KommuneCompletionStore store, int kommuneNummer, ExistingTagCoverage coverage) {
        if (!coverage.suggestsSubstantialOtherCoverage()) {
            return;
        }
        KommuneCompletionRecord rec = store.getOrEmpty(kommuneNummer);
        if (rec.isDone()) {
            return;
        }
        int choice =
                JOptionPane.showConfirmDialog(
                        MainApplication.getMainFrame(),
                        tr(
                                "{0}% of matched ways in this kommune already have incline data"
                                        + " outside this tool (other/surveyed).\n\n"
                                        + "Mark this kommune as reviewed in the local completion"
                                        + " tracker? (You can reopen it later.)",
                                coverage.otherInclinePercent()),
                        tr("NVDB incline — existing OSM coverage"),
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            store.put(rec.withManualOverride(true));
            persistStore(store);
        }
    }

    private static void updateCompletion(
            KommuneCompletionStore store,
            int kommuneNummer,
            ReviewModel model,
            int matched,
            int unmatched,
            boolean applied,
            boolean dismissUnmatched) {
        ReviewSessionStats stats =
                ReviewSessionStats.fromReview(model, matched, unmatched, applied);
        KommuneCompletionRecord prev = store.getOrEmpty(kommuneNummer);
        KommuneCompletionRecord next =
                prev.withSession(
                        stats.matchedWays,
                        stats.accepted,
                        stats.rejected,
                        stats.pending,
                        stats.unmatched,
                        dismissUnmatched || unmatched == 0,
                        Instant.now());
        store.put(next);
    }

    private static void persistStore(KommuneCompletionStore store) {
        try {
            store.save(LocalDataPaths.completionFile());
        } catch (Exception ex) {
            Logging.warn("Could not save kommune completion file: " + ex.getMessage());
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(true);
    }
}
