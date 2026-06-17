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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;

/**
 * DR-022 — task-agnostic entry point that turns persisted retinal
 * inference artifacts (under {@code segDir}) into a single
 * {@link ComputedMetrics} suitable for the NUMERIC primary-value column
 * + JSONB {@code output_payload} on the inference-job row.
 *
 * <p>Dispatches by task name to the per-task helpers
 * ({@link FluidMetric}, {@link OnlMetric}, {@link PrMetric},
 * {@link GaMetric}).
 *
 * <p>Geometry-soft-fail (Wave 1A): if {@code geom == null}, results are
 * emitted in pixel units rather than mm, the payload carries
 * {@code "geometry": "missing"}, and a warning is logged so an operator
 * notices the degradation.
 *
 * <p>If a required artifact is missing the per-task helper throws
 * {@link MetricComputationException}; the Wave 3 controller catches and
 * falls back gracefully (e.g. completes the job with a null primary).
 */
@Component
public class RetinalMetricComputer {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalMetricComputer.class);

    public ComputedMetrics compute(String task,
                                   Path segDir,
                                   PixelGeometry geom,
                                   String laterality) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (segDir == null) {
            throw new IllegalArgumentException("segDir must not be null");
        }
        if (geom == null) {
            LOG.warn("Computing metrics for task '{}' in {} without geometry — "
                    + "falling back to pixel units (DR-022 soft-fail).",
                    task, segDir);
        }
        return switch (task) {
            case "fluid" -> FluidMetric.compute(segDir, geom, laterality);
            case "onl"   -> OnlMetric.compute(segDir, geom, laterality);
            case "pr"    -> PrMetric.compute(segDir, geom, laterality);
            case "ga"    -> GaMetric.compute(segDir, geom, laterality);
            default -> throw new IllegalArgumentException("unknown retinal task: " + task);
        };
    }
}
