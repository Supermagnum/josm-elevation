package org.openstreetmap.josm.plugins.nvdbincline;

import static org.openstreetmap.josm.tools.I18n.tr;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.nvdbincline.action.SuggestInclinesAction;
import org.openstreetmap.josm.plugins.nvdbincline.validator.NvdbEstimateValidator;
import org.openstreetmap.josm.tools.Logging;

/**
 * JOSM plugin entry point. Review-only: applies edits as ordinary Commands.
 * Never uploads; the user must use JOSM's own Upload manually after review.
 */
public class NvdbInclinePlugin extends Plugin {

    public NvdbInclinePlugin(PluginInformation info) {
        super(info);
        SuggestInclinesAction action = new SuggestInclinesAction();
        /*
         * moreToolsMenu starts hidden until a plugin successfully adds an item via
         * MainMenu.add. If the action shortcut is marked automatic (key conflict),
         * MainMenu.add returns null and the whole "More tools" menu stays invisible.
         * Fall back to a direct JMenu.add and force visibility.
         */
        JMenu moreTools = MainApplication.getMenu().moreToolsMenu;
        JMenuItem item = MainMenu.add(moreTools, action);
        if (item == null) {
            Logging.warn(
                    "nvdb_incline: MainMenu.add skipped (automatic shortcut); adding menu item directly");
            moreTools.add(action);
        }
        moreTools.setVisible(true);
        // Also under Data — always visible, easier to find than "More tools".
        JMenu data = MainApplication.getMenu().dataMenu;
        JMenuItem dataItem = MainMenu.add(data, action);
        if (dataItem == null) {
            data.add(action);
        }
        data.setVisible(true);
        NvdbEstimateValidator.register();
        Logging.info(tr("nvdb_incline: menu registered under More tools and Data"));
    }
}
