package org.openstreetmap.josm.plugins.nvdbincline;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.nvdbincline.action.SuggestInclinesAction;
import org.openstreetmap.josm.plugins.nvdbincline.validator.NvdbEstimateValidator;

/**
 * JOSM plugin entry point. Review-only: applies edits as ordinary Commands.
 * Never uploads; the user must use JOSM's own Upload manually after review.
 */
public class NvdbInclinePlugin extends Plugin {

    public NvdbInclinePlugin(PluginInformation info) {
        super(info);
        MainMenu.add(MainApplication.getMenu().moreToolsMenu, new SuggestInclinesAction());
        NvdbEstimateValidator.register();
    }
}
