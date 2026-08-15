package org.openstreetmap.josm.plugins.nvdbincline;

import org.openstreetmap.josm.data.preferences.BooleanProperty;

/**
 * JOSM preference keys for this plugin. Defaults are conservative: auto-split
 * is off because it changes way structure, not just tags.
 */
public final class NvdbInclinePreferences {
    /**
     * When true, accepting a split-recommended incline suggestion inserts nodes
     * as needed, runs {@code SplitWayCommand}, and tags each resulting sub-way.
     */
    public static final BooleanProperty AUTO_SPLIT_VARIABLE_GRADIENT =
            new BooleanProperty("nvdb_incline.auto_split_variable_gradient", false);

    private NvdbInclinePreferences() {}

    public static boolean autoSplitVariableGradient() {
        return Boolean.TRUE.equals(AUTO_SPLIT_VARIABLE_GRADIENT.get());
    }

    public static void setAutoSplitVariableGradient(boolean enabled) {
        AUTO_SPLIT_VARIABLE_GRADIENT.put(enabled);
    }
}
