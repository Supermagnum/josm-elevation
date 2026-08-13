package org.openstreetmap.josm.plugins.nvdbincline.dialog;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.List;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import no.nvdbincline.core.review.InclineAudit;
import no.nvdbincline.core.review.ReviewModel;

/**
 * Review UI: every proposed change is listed with an accept checkbox.
 * Incline audit fields (match quality, raw estimates, split segments) are
 * shown as columns / tooltips — never written as OSM tags.
 */
public final class ReviewDialog {
    private ReviewDialog() {}

    /**
     * @return true if the user confirmed Apply selected
     */
    public static boolean show(Frame parent, ReviewModel model, int matched, int unmatched) {
        JDialog dialog = new JDialog(parent, tr("Review NVDB suggestions"), true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        TableModel tableModel = new TableModel(model.rows());
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {55, 110, 90, 90, 90, 70, 70, 110, 100, 360};
        for (int i = 0; i < widths.length && i < table.getColumnModel().getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.setDefaultRenderer(Object.class, new AuditTooltipRenderer());

        JLabel summary =
                new JLabel(
                        tr(
                                "<html>Matched ways: <b>{0}</b> &nbsp; Unmatched: <b>{1}</b><br/>"
                                        + "<b>Confirmed by sign</b> rows may suggest <code>hazard=*</code>. "
                                        + "Geometry/accident advisories never get <code>hazard=*</code> "
                                        + "(OSM requires a posted sign). Hover a row for full match/estimate detail. "
                                        + "Never uploads.</html>",
                                matched,
                                unmatched));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton acceptHigh = new JButton(tr("Accept all high-confidence"));
        JButton acceptAll = new JButton(tr("Accept all"));
        JButton rejectAll = new JButton(tr("Reject all"));
        JButton cancel = new JButton(tr("Cancel"));
        JButton apply = new JButton(tr("Apply selected"));
        final boolean[] confirmed = {false};

        acceptHigh.addActionListener(
                e -> {
                    model.acceptAllHighConfidence();
                    tableModel.fireTableDataChanged();
                });
        acceptAll.addActionListener(
                e -> {
                    model.acceptAll();
                    tableModel.fireTableDataChanged();
                });
        rejectAll.addActionListener(
                e -> {
                    model.rejectAll();
                    tableModel.fireTableDataChanged();
                });
        cancel.addActionListener(e -> dialog.dispose());
        apply.addActionListener(
                e -> {
                    confirmed[0] = true;
                    dialog.dispose();
                });

        buttons.add(acceptHigh);
        buttons.add(acceptAll);
        buttons.add(rejectAll);
        buttons.add(cancel);
        buttons.add(apply);

        JPanel north = new JPanel(new BorderLayout());
        north.add(summary, BorderLayout.CENTER);

        dialog.getContentPane().setLayout(new BorderLayout(8, 8));
        dialog.getContentPane().add(north, BorderLayout.NORTH);
        dialog.getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.setPreferredSize(new Dimension(1280, 560));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return confirmed[0];
    }

    private static final class AuditTooltipRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            Component c =
                    super.getTableCellRendererComponent(
                            table, value, isSelected, hasFocus, row, column);
            if (table.getModel() instanceof TableModel tm) {
                ReviewModel.Row r = tm.rows.get(row);
                if (r.inclineAudit != null) {
                    setToolTipText("<html>" + r.inclineAudit.detailTooltip().replace("\n", "<br/>") + "</html>");
                } else {
                    setToolTipText(r.summary);
                }
            }
            return c;
        }
    }

    private static final class TableModel extends AbstractTableModel {
        private final List<ReviewModel.Row> rows;
        private final String[] cols = {
            tr("Accept"),
            tr("Section"),
            tr("Kind"),
            tr("Confidence"),
            tr("Method"),
            tr("H(m)"),
            tr("Proposed"),
            tr("Raw avg/max"),
            tr("Split"),
            tr("Detail")
        };

        TableModel(List<ReviewModel.Row> rows) {
            this.rows = rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int column) {
            return cols[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 && rows.get(rowIndex).kind != ReviewModel.Kind.DISCREPANCY;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ReviewModel.Row r = rows.get(rowIndex);
            InclineAudit a = r.inclineAudit;
            return switch (columnIndex) {
                case 0 -> r.accepted;
                case 1 -> sectionLabel(r.section);
                case 2 -> r.kind.name();
                case 3 ->
                        r.confidence == null
                                ? ""
                                : r.confidence.name().toLowerCase(Locale.ROOT)
                                        + (r.signConfirmed ? " (sign)" : "");
                case 4 -> a == null ? "" : a.matchMethod();
                case 5 ->
                        a == null || a.matchHausdorffM() == null
                                ? ""
                                : String.format(Locale.ROOT, "%.1f", a.matchHausdorffM());
                case 6 -> a == null ? "" : a.suggestedIncline();
                case 7 ->
                        a == null
                                ? ""
                                : String.format(
                                        Locale.ROOT,
                                        "%.1f%% / %.1f%%",
                                        a.estimatedAvgPct(),
                                        a.estimatedMaxSustainedPct());
                case 8 ->
                        r.splitSuggested
                                ? tr("Split suggested")
                                : (a != null && a.splitRecommended() ? tr("Split suggested") : "");
                case 9 ->
                        a != null && a.suggestedSegments() != null && a.splitRecommended()
                                ? "id " + r.osmId + ": segments " + a.suggestedSegments()
                                : "id " + r.osmId + ": " + r.summary;
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 0 && aValue instanceof Boolean b) {
                rows.get(rowIndex).accepted = b;
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }

        private static String sectionLabel(ReviewModel.Section s) {
            return switch (s) {
                case INCLINES -> tr("Inclines");
                case CHAINS -> tr("Snow chains");
                case CURVES_SIGNED -> tr("Curves (confirmed by sign)");
                case CURVES_ADVISORY -> tr("Curves (geometry-only)");
                case ACCIDENTS -> tr("Accident clusters");
            };
        }
    }
}
