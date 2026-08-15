package org.openstreetmap.josm.plugins.nvdbincline.dialog;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import no.nvdbincline.core.area.AreaSelection;
import no.nvdbincline.core.completion.CompletionStatus;
import no.nvdbincline.core.completion.KommuneCompletionRecord;
import no.nvdbincline.core.completion.KommuneCompletionStore;
import no.nvdbincline.core.kommune.Kommune;
import no.nvdbincline.core.kommune.KommuneCatalog;
import no.nvdbincline.core.kommune.KommuneSearch;

/**
 * Area selection before an NVDB suggest run: current layer, custom bbox, or kommune.
 *
 * <p>Kommune combo shows personal completion status (local-only tracker).
 */
public final class AreaSelectionDialog {
    private AreaSelectionDialog() {}

    public record Result(
            AreaSelection selection,
            boolean dismissUnmatched,
            boolean markDoneAnyway,
            boolean reopen,
            boolean downloadOsm,
            boolean allowStaleNorwayExtract,
            KommuneCompletionStore store) {}

    public static Result show(
            Frame parent, KommuneCatalog catalog, KommuneCompletionStore store) {
        return show(parent, catalog, store, false);
    }

    /**
     * @param preferKommune when true, pre-selects "By kommune" (used when no edit layer exists)
     */
    public static Result show(
            Frame parent,
            KommuneCatalog catalog,
            KommuneCompletionStore store,
            boolean preferKommune) {
        JDialog dialog = new JDialog(parent, tr("NVDB incline — choose area"), true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JRadioButton currentLayer = new JRadioButton(tr("Current edit layer"));
        JRadioButton bboxMode = new JRadioButton(tr("Custom bounding box (WGS84)"));
        JRadioButton kommuneMode =
                new JRadioButton(
                        tr(
                                "By kommune (local Geofabrik Norway extract + Kartverket boundary; NVDB kommune=)"));
        if (preferKommune) {
            kommuneMode.setSelected(true);
        } else {
            currentLayer.setSelected(true);
        }
        ButtonGroup group = new ButtonGroup();
        group.add(currentLayer);
        group.add(bboxMode);
        group.add(kommuneMode);

        JTextField minLon = new JTextField(10);
        JTextField minLat = new JTextField(10);
        JTextField maxLon = new JTextField(10);
        JTextField maxLat = new JTextField(10);
        JPanel bboxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bboxPanel.add(new JLabel(tr("minLon")));
        bboxPanel.add(minLon);
        bboxPanel.add(new JLabel(tr("minLat")));
        bboxPanel.add(minLat);
        bboxPanel.add(new JLabel(tr("maxLon")));
        bboxPanel.add(maxLon);
        bboxPanel.add(new JLabel(tr("maxLat")));
        bboxPanel.add(maxLat);

        JTextField search = new JTextField(18);
        DefaultComboBoxModel<KommuneItem> comboModel = new DefaultComboBoxModel<>();
        JComboBox<KommuneItem> combo = new JComboBox<>(comboModel);
        combo.setEditable(false);
        refillCombo(comboModel, catalog.all(), store, "");

        JLabel statusLabel = new JLabel(" ");
        JLabel localOsmLabel = new JLabel(" ");
        JCheckBox downloadOsm =
                new JCheckBox(
                        tr(
                                "Download missing OSM roads via openstreetmap.org API (bbox mode only; not Overpass)"),
                        true);
        JCheckBox allowStaleExtract =
                new JCheckBox(
                        tr("Allow stale local Norway extract (older than 14 days)"), false);
        JButton setupExtract = new JButton(tr("Set up / refresh local Norway data…"));
        JButton checkExtract = new JButton(tr("Check for newer extract…"));
        JCheckBox dismissUnmatched =
                new JCheckBox(tr("Dismiss unmatched triage for completion tracking"));
        JButton markDone = new JButton(tr("Mark selected kommune done anyway"));
        JButton reopen = new JButton(tr("Reopen selected kommune"));

        Runnable refreshLocalOsmLabel =
                () -> {
                    var st =
                            org.openstreetmap.josm.plugins.nvdbincline.io.GeofabrikNorwayExtract
                                    .status(java.time.Clock.systemUTC());
                    String when =
                            st.osmDataUntil()
                                    .map(
                                            t ->
                                                    DateTimeFormatter.ISO_LOCAL_DATE
                                                            .withZone(ZoneId.systemDefault())
                                                            .format(t))
                                    .orElse("unknown date");
                    String size =
                            st.fileSizeBytes() > 0
                                    ? String.format(
                                            Locale.ROOT,
                                            "%.1f GB",
                                            st.fileSizeBytes() / 1_000_000_000.0)
                                    : "—";
                    switch (st.kind()) {
                        case MISSING ->
                                localOsmLabel.setText(
                                        tr(
                                                "Local OSM data: not installed (needed for kommune mode, ~1.3 GB from Geofabrik)"));
                        case STALE ->
                                localOsmLabel.setText(
                                        tr(
                                                "Local OSM data: updated {0} ({1}) — stale. Refresh recommended.",
                                                when, size));
                        case CURRENT ->
                                localOsmLabel.setText(
                                        tr("Local OSM data: updated {0} ({1})", when, size));
                    }
                };
        refreshLocalOsmLabel.run();

        setupExtract.addActionListener(
                e -> {
                    int confirm =
                            JOptionPane.showConfirmDialog(
                                    dialog,
                                    tr(
                                            "Download norway-latest.osm.pbf from Geofabrik?\n\n"
                                                    + "Typical size is about 1.3 GB. This is a one-time"
                                                    + " (or occasional refresh) download into the plugin"
                                                    + " data directory. It is not uploaded anywhere.\n\n"
                                                    + "Source: {0}",
                                            org.openstreetmap.josm.plugins.nvdbincline.io
                                                    .GeofabrikNorwayExtract.DOWNLOAD_PAGE),
                                    tr("NVDB incline — Geofabrik extract"),
                                    JOptionPane.OK_CANCEL_OPTION,
                                    JOptionPane.QUESTION_MESSAGE);
                    if (confirm != JOptionPane.OK_OPTION) {
                        return;
                    }
                    org.openstreetmap.josm.gui.MainApplication.worker.submit(
                            new org.openstreetmap.josm.gui.PleaseWaitRunnable(
                                    tr("Downloading Norway OSM extract")) {
                                private String err;

                                @Override
                                protected void realRun()
                                        throws org.xml.sax.SAXException, java.io.IOException,
                                                org.openstreetmap.josm.io.OsmTransferException {
                                    try {
                                        org.openstreetmap.josm.plugins.nvdbincline.io
                                                .GeofabrikNorwayExtract.download(progressMonitor);
                                    } catch (Exception ex) {
                                        err = ex.getMessage();
                                    }
                                }

                                @Override
                                protected void finish() {
                                    refreshLocalOsmLabel.run();
                                    if (err != null) {
                                        JOptionPane.showMessageDialog(
                                                dialog,
                                                tr("Download failed: {0}", err),
                                                tr("NVDB incline"),
                                                JOptionPane.ERROR_MESSAGE);
                                    } else {
                                        JOptionPane.showMessageDialog(
                                                dialog,
                                                tr("Local Norway extract is ready."),
                                                tr("NVDB incline"),
                                                JOptionPane.INFORMATION_MESSAGE);
                                    }
                                }

                                @Override
                                protected void cancel() {}
                            });
                });
        checkExtract.addActionListener(
                e -> {
                    try {
                        var remote =
                                org.openstreetmap.josm.plugins.nvdbincline.io.GeofabrikNorwayExtract
                                        .probeRemote();
                        var local =
                                org.openstreetmap.josm.plugins.nvdbincline.io.GeofabrikNorwayExtract
                                        .status(java.time.Clock.systemUTC());
                        String remoteUntil =
                                remote.osmDataUntil() == null
                                        ? "unknown"
                                        : remote.osmDataUntil().toString();
                        String localUntil =
                                local.osmDataUntil().map(Instant::toString).orElse("none");
                        boolean newer =
                                remote.osmDataUntil() != null
                                        && local.osmDataUntil()
                                                .map(t -> remote.osmDataUntil().isAfter(t))
                                                .orElse(true);
                        JOptionPane.showMessageDialog(
                                dialog,
                                tr(
                                        "Geofabrik remote OSM data until: {0}\n"
                                                + "Local extract until: {1}\n"
                                                + "Approximate remote size: {2} bytes\n\n"
                                                + "{3}",
                                        remoteUntil,
                                        localUntil,
                                        remote.approximateBytes(),
                                        newer
                                                ? tr("A newer extract appears available — use Set up / refresh.")
                                                : tr("Local extract looks current vs Geofabrik page.")),
                                tr("NVDB incline — check extract"),
                                JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(
                                dialog,
                                tr("Could not check Geofabrik: {0}", ex.getMessage()),
                                tr("NVDB incline"),
                                JOptionPane.ERROR_MESSAGE);
                    }
                });

        Runnable updateStatus =
                () -> {
                    KommuneItem item = (KommuneItem) combo.getSelectedItem();
                    if (item == null) {
                        statusLabel.setText(" ");
                        return;
                    }
                    KommuneCompletionRecord rec = store.getOrEmpty(item.kommune.nummer());
                    statusLabel.setText(formatStatus(rec));
                };
        combo.addActionListener(e -> updateStatus.run());
        search.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent e) {
                                refillCombo(comboModel, catalog.all(), store, search.getText());
                                updateStatus.run();
                            }

                            @Override
                            public void removeUpdate(DocumentEvent e) {
                                refillCombo(comboModel, catalog.all(), store, search.getText());
                                updateStatus.run();
                            }

                            @Override
                            public void changedUpdate(DocumentEvent e) {
                                refillCombo(comboModel, catalog.all(), store, search.getText());
                                updateStatus.run();
                            }
                        });

        final boolean[] markDoneFlag = {false};
        final boolean[] reopenFlag = {false};
        markDone.addActionListener(
                e -> {
                    KommuneItem item = (KommuneItem) combo.getSelectedItem();
                    if (item == null) {
                        return;
                    }
                    store.put(store.getOrEmpty(item.kommune.nummer()).withManualOverride(true));
                    markDoneFlag[0] = true;
                    refillCombo(comboModel, catalog.all(), store, search.getText());
                    selectNummer(comboModel, combo, item.kommune.nummer());
                    updateStatus.run();
                });
        reopen.addActionListener(
                e -> {
                    KommuneItem item = (KommuneItem) combo.getSelectedItem();
                    if (item == null) {
                        return;
                    }
                    store.put(store.getOrEmpty(item.kommune.nummer()).withManualOverride(false));
                    reopenFlag[0] = true;
                    refillCombo(comboModel, catalog.all(), store, search.getText());
                    selectNummer(comboModel, combo, item.kommune.nummer());
                    updateStatus.run();
                });

        JPanel kommunePanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        kommunePanel.add(new JLabel(tr("Search name or number")), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        kommunePanel.add(search, c);
        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        kommunePanel.add(new JLabel(tr("Kommune")), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        kommunePanel.add(combo, c);
        c.gridx = 1;
        c.gridy = 2;
        kommunePanel.add(statusLabel, c);
        c.gridy = 3;
        kommunePanel.add(localOsmLabel, c);
        c.gridy = 4;
        JPanel extractBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        extractBtns.add(setupExtract);
        extractBtns.add(checkExtract);
        kommunePanel.add(extractBtns, c);
        c.gridy = 5;
        kommunePanel.add(allowStaleExtract, c);
        c.gridy = 6;
        kommunePanel.add(dismissUnmatched, c);
        JPanel overrideBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        overrideBtns.add(markDone);
        overrideBtns.add(reopen);
        c.gridy = 7;
        kommunePanel.add(overrideBtns, c);

        JLabel note =
                new JLabel(
                        tr(
                                "<html>This dialog is opened from <b>Data</b> or <b>More tools → Suggest inclines from NVDB…</b>"
                                        + " (not from File → Download).<br/>"
                                        + "<b>By kommune</b> clips OSM from a <b>local Geofabrik Norway .osm.pbf</b>"
                                        + " using <b>Kartverket</b> boundary polygons (no OSM API — avoids 509 limits"
                                        + " and bbox border leakage). NVDB still uses <code>kommune=</code>.<br/>"
                                        + "Bbox / current-layer modes are unchanged. Completion is local-only"
                                        + " (kommune list snapshot {0}).</html>",
                                catalog.effectiveDate()));

        Runnable syncEnabled =
                () -> {
                    boolean b = bboxMode.isSelected();
                    boolean k = kommuneMode.isSelected();
                    for (var comp : bboxPanel.getComponents()) {
                        comp.setEnabled(b);
                    }
                    search.setEnabled(k);
                    combo.setEnabled(k);
                    dismissUnmatched.setEnabled(k);
                    markDone.setEnabled(k);
                    reopen.setEnabled(k);
                    localOsmLabel.setEnabled(k);
                    setupExtract.setEnabled(k);
                    checkExtract.setEnabled(k);
                    allowStaleExtract.setEnabled(k);
                    downloadOsm.setEnabled(b);
                };
        currentLayer.addActionListener(e -> syncEnabled.run());
        bboxMode.addActionListener(e -> syncEnabled.run());
        kommuneMode.addActionListener(e -> syncEnabled.run());
        syncEnabled.run();
        updateStatus.run();

        JPanel center = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(4, 4, 4, 4);
        center.add(kommuneMode, gc);
        gc.gridy++;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        center.add(kommunePanel, gc);
        gc.gridy++;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        center.add(currentLayer, gc);
        gc.gridy++;
        center.add(bboxMode, gc);
        gc.gridy++;
        center.add(bboxPanel, gc);
        gc.gridy++;
        center.add(downloadOsm, gc);
        gc.gridy++;
        center.add(note, gc);

        final Result[] out = {null};
        JButton cancel = new JButton(tr("Cancel"));
        JButton ok = new JButton(tr("Continue"));
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(
                e -> {
                    try {
                        AreaSelection sel;
                        if (currentLayer.isSelected()) {
                            sel = AreaSelection.currentLayer();
                        } else if (bboxMode.isSelected()) {
                            sel =
                                    AreaSelection.bbox(
                                            Double.parseDouble(minLon.getText().trim()),
                                            Double.parseDouble(minLat.getText().trim()),
                                            Double.parseDouble(maxLon.getText().trim()),
                                            Double.parseDouble(maxLat.getText().trim()));
                        } else {
                            KommuneItem item = (KommuneItem) combo.getSelectedItem();
                            if (item == null) {
                                JOptionPane.showMessageDialog(
                                        dialog,
                                        tr("Select a kommune."),
                                        tr("NVDB incline"),
                                        JOptionPane.WARNING_MESSAGE);
                                return;
                            }
                            sel = AreaSelection.kommune(item.kommune.nummer(), item.kommune.navn());
                        }
                        out[0] =
                                new Result(
                                        sel,
                                        dismissUnmatched.isSelected(),
                                        markDoneFlag[0],
                                        reopenFlag[0],
                                        downloadOsm.isSelected() && bboxMode.isSelected(),
                                        allowStaleExtract.isSelected(),
                                        store);
                        dialog.dispose();
                    } catch (RuntimeException ex) {
                        JOptionPane.showMessageDialog(
                                dialog,
                                tr("Invalid area: {0}", ex.getMessage()),
                                tr("NVDB incline"),
                                JOptionPane.ERROR_MESSAGE);
                    }
                });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);

        dialog.getContentPane().setLayout(new BorderLayout(8, 8));
        dialog.getContentPane().add(center, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setMinimumSize(dialog.getPreferredSize());
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return out[0];
    }

    private static void refillCombo(
            DefaultComboBoxModel<KommuneItem> model,
            List<Kommune> all,
            KommuneCompletionStore store,
            String query) {
        KommuneItem prev = null;
        Object selected = model.getSelectedItem();
        if (selected instanceof KommuneItem item) {
            prev = item;
        }
        model.removeAllElements();
        for (Kommune k : KommuneSearch.filter(all, query)) {
            model.addElement(new KommuneItem(k, store.getOrEmpty(k.nummer())));
        }
        if (prev != null) {
            selectNummer(model, null, prev.kommune.nummer());
        }
    }

    private static void selectNummer(
            DefaultComboBoxModel<KommuneItem> model, JComboBox<KommuneItem> combo, int nummer) {
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i).kommune.nummer() == nummer) {
                if (combo != null) {
                    combo.setSelectedIndex(i);
                } else {
                    model.setSelectedItem(model.getElementAt(i));
                }
                return;
            }
        }
    }

    private static String formatStatus(KommuneCompletionRecord rec) {
        CompletionStatus st = rec.status();
        String date = "";
        if (rec.lastRunEpochMilli() > 0) {
            date =
                    " — "
                            + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(rec.lastRunEpochMilli()));
        }
        String label =
                switch (st) {
                    case NOT_STARTED -> tr("not started");
                    case IN_PROGRESS -> tr("in progress");
                    case DONE -> tr("done");
                };
        String status = tr("Status: {0}{1}", label, date);
        if (rec.hasCoverage()) {
            status = status + " — " + rec.formatCoverageLine();
        }
        return "<html><body style='width:420px'>" + escapeHtml(status) + "</body></html>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Combo entry with status prefix. */
    public static final class KommuneItem {
        public final Kommune kommune;
        public final KommuneCompletionRecord record;

        public KommuneItem(Kommune kommune, KommuneCompletionRecord record) {
            this.kommune = kommune;
            this.record = record;
        }

        @Override
        public String toString() {
            String mark =
                    switch (record.status()) {
                        case DONE -> "[done] ";
                        case IN_PROGRESS -> "[…] ";
                        case NOT_STARTED -> "";
                    };
            return mark + kommune.displayLabel();
        }
    }
}
