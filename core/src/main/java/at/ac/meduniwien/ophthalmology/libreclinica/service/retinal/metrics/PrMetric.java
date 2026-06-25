/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics;

import java.nio.file.Path;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;

/**
 * DR-022 — PR (photoreceptor layer) thickness in µm.
 *
 * <p>Upper boundary: {@code 001-BMEIS.csv}; lower boundary:
 * {@code 002-OB-OPR.csv}. Same shape as {@link OnlMetric} but with
 * different surface conventions — BMEIS is the upper bound for PR
 * whereas it is the lower bound for ONL.
 */
final class PrMetric {

    // The cluster's PR runner emits filenames like
    // "002-Outer boundary of OPR (OB_OPR).csv" — substring tokens cover both
    // short and long forms.
    private static final String UPPER_TOKEN = "BMEIS";
    private static final String LOWER_TOKEN = "OPR";

    private PrMetric() { }

    static ComputedMetrics compute(Path segDir, PixelGeometry geom, String laterality) {
        return OnlMetric.ThicknessMetric.compute(segDir, geom, laterality,
                UPPER_TOKEN, LOWER_TOKEN, "PR");
    }
}
