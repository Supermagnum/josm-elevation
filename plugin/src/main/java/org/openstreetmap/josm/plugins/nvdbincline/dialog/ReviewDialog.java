package org.openstreetmap.josm.plugins.nvdbincline.dialog;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import no.nvdbincline.core.review.ReviewModel;

/**
 * Review UI: every proposed change is listed with an accept checkbox.
 * Nothing is applied until the user confirms; only checked rows become Commands.
 */
public final class ReviewDialog {
    private ReviewDialog() {}

    /**
     * @return true if the user confirmed Apply selected
     */
    public static boolean show(Frame parent, ReviewModel model, int matched, int unmatched) {
        JDialog dialog = new JDialog(parent, tr("Review NVDB incline suggestions"), true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        TableModel tableModel = new TableModel(model.rows());
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(420);

        JLabel summary =
                new JLabel(
                        tr(
                                "<html>Matched ways: <b>{0}</b> &nbsp; Unmatched: <b>{1}</b><br/>"
                                        + "Suggestions are machine estimates (<code>incline:source=nvdb_estimate</code>). "
                                        + "Accept only what you can verify. This plugin never uploads.</html>",
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
        dialog.setPreferredSize(new Dimension(900, 480));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return confirmed[0];
    }

    private static final class TableModel extends AbstractTableModel {
        private final List<ReviewModel.Row> rows;
        private final String[] cols = {
            tr("Accept"), tr("Kind"), tr("Confidence"), tr("Summary")
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
            return switch (columnIndex) {
                case 0 -> r.accepted;
                case 1 -> r.kind.name();
                case 2 -> r.confidence == null ? "" : r.confidence.name().toLowerCase();
                case 3 -> "way/node " + r.osmId + ": " + r.summary;
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
    }
}
