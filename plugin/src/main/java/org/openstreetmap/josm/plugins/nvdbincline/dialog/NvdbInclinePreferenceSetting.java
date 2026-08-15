package org.openstreetmap.josm.plugins.nvdbincline.dialog;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.plugins.nvdbincline.NvdbInclinePreferences;
import org.openstreetmap.josm.tools.GBC;

/**
 * Preferences tab for nvdb_incline. Currently one opt-in: automatic splitting
 * of ways whose gradient varies too much for a single {@code incline=*} tag.
 */
public final class NvdbInclinePreferenceSetting extends DefaultTabPreferenceSetting {
    private final JCheckBox autoSplit =
            new JCheckBox(tr("Automatically split ways with highly variable gradient"));

    public NvdbInclinePreferenceSetting() {
        super(
                "preferences/plugin",
                tr("NVDB incline"),
                tr("Settings for NVDB incline suggestions (review-only; never uploads)."));
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        autoSplit.setSelected(NvdbInclinePreferences.autoSplitVariableGradient());
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(autoSplit, GBC.eol());
        panel.add(
                new JLabel(
                        tr(
                                "<html><p>Off by default because this changes <b>way structure</b>,"
                                        + " not just tags: nodes may be inserted at gradient boundaries,"
                                        + " then the way is split with JOSM Split Way"
                                        + " (relation membership is updated).</p>"
                                        + "<p>Each resulting sub-way gets its own <code>incline=*</code>"
                                        + " from that segment gradient. One Ctrl+Z undoes the whole Apply,"
                                        + " including the split.</p>"
                                        + "<p>When a way is a member of a relation, the review dialog warns"
                                        + " before Apply. Hazard, curve, and chain-advisory suggestions are"
                                        + " never auto-split.</p></html>")),
                GBC.eol().insets(20, 4, 0, 0).fill(GBC.HORIZONTAL));
        createPreferenceTabWithScrollPane(gui, panel);
    }

    @Override
    public boolean ok() {
        NvdbInclinePreferences.setAutoSplitVariableGradient(autoSplit.isSelected());
        return false;
    }
}
