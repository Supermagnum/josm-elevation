package org.openstreetmap.josm.plugins.nvdbincline.validator;

import static org.openstreetmap.josm.tools.I18n.tr;

import no.nvdbincline.core.tag.AppliedTags;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.OsmValidator;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;

/**
 * Reminds mappers that source:incline=nvdb_estimate still needs field verification
 * before a final upload.
 */
public class NvdbEstimateValidator extends Test {
    private static final int ERROR_CODE = 91001;

    public NvdbEstimateValidator() {
        super(
                tr("NVDB incline estimates"),
                tr(
                        "Highlights ways tagged with source:incline=nvdb_estimate that still need field verification."));
    }

    public static void register() {
        OsmValidator.addTest(NvdbEstimateValidator.class);
    }

    @Override
    public void visit(Way w) {
        if (!isPrimitiveUsable(w)) {
            return;
        }
        if (!AppliedTags.INCLINE_SOURCE_VALUE.equals(w.get(AppliedTags.SOURCE_INCLINE))) {
            return;
        }
        errors.add(
                TestError.builder(this, Severity.OTHER, ERROR_CODE)
                        .message(
                                tr("NVDB incline estimate"),
                                tr(
                                        "Machine-suggested incline still present — verify in field before upload"
                                                + " (fixme / source:incline=nvdb_estimate)."))
                        .primitives(w)
                        .build());
    }
}
