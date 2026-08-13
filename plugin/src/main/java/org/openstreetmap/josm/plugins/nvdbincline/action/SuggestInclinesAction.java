package org.openstreetmap.josm.plugins.nvdbincline.action;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.JOptionPane;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.plugins.nvdbincline.command.SuggestionApplier;
import org.openstreetmap.josm.plugins.nvdbincline.dialog.ReviewDialog;
import org.openstreetmap.josm.plugins.nvdbincline.io.LayerAdapter;
import org.openstreetmap.josm.plugins.nvdbincline.io.NvdbClient;
import no.nvdbincline.core.SuggestionEngine;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.review.ReviewModel;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Menu action: fetch NVDB elevation, compute suggestions, show review dialog,
 * apply only accepted rows as undoable Commands.
 */
public class SuggestInclinesAction extends JosmAction {

    public SuggestInclinesAction() {
        super(
                tr("Suggest inclines from NVDB…"),
                "dialogs/nvdb_incline",
                tr("Suggest incline=* tags from NVDB elevation (review before apply; never uploads)"),
                Shortcut.registerShortcut(
                        "tools:nvdbincline",
                        tr("Tools: Suggest inclines from NVDB"),
                        KeyEvent.VK_I,
                        Shortcut.ALT_CTRL),
                true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        OsmDataLayer layer = MainApplication.getLayerManager().getActiveDataLayer();
        if (layer == null || layer.getDataSet() == null || layer.getDataSet().getWays().isEmpty()) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Download some OSM road data into an edit layer first."),
                    tr("NVDB incline"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        DataSet ds = layer.getDataSet();
        try {
            ProgressMonitor progress = NullProgressMonitor.INSTANCE;
            progress.beginTask(tr("Fetching NVDB elevation…"));
            List<OsmWayGeom> ways = LayerAdapter.extractWays(ds);
            if (ways.isEmpty()) {
                JOptionPane.showMessageDialog(
                        MainApplication.getMainFrame(),
                        tr("No highway=* ways found in the active layer."),
                        tr("NVDB incline"),
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            double[] bbox = LayerAdapter.bboxLonLat(ds);
            NvdbClient client = new NvdbClient();
            List<NvdbLink> links = client.fetchSegmentedLinks(bbox[0], bbox[1], bbox[2], bbox[3]);
            SuggestionEngine.Output out =
                    SuggestionEngine.run(ways, links, new SuggestionEngine.Config());
            ReviewModel model = ReviewModel.fromEngine(out.suggestions, out.chainPoints);
            if (model.rows().isEmpty()) {
                JOptionPane.showMessageDialog(
                        MainApplication.getMainFrame(),
                        tr("No suggestions for this layer (no NVDB matches or no elevation)."),
                        tr("NVDB incline"),
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            boolean ok =
                    ReviewDialog.show(
                            MainApplication.getMainFrame(),
                            model,
                            out.matchResult.matches.size(),
                            out.matchResult.unmatchedOsm.size());
            if (!ok) {
                return;
            }
            int applied = SuggestionApplier.applyAccepted(ds, model.acceptedRows());
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr(
                            "Applied {0} suggestion(s) as undoable edits.\n"
                                    + "Review tags (look for incline:source=nvdb_estimate / fixme),\n"
                                    + "then upload manually from JOSM if you choose to.\n"
                                    + "This plugin never uploads.",
                            applied),
                    tr("NVDB incline"),
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Failed: {0}", ex.getMessage()),
                    tr("NVDB incline"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getActiveDataLayer() != null);
    }
}
