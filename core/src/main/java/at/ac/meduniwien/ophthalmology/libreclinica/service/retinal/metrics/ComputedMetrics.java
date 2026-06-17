/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics;

import java.math.BigDecimal;
import java.util.Map;

/**
 * DR-022 — output of {@link RetinalMetricComputer#compute}: the single
 * NUMERIC(12,4) value persisted on the inference-job row plus the JSONB
 * {@code output_payload} blob the SPA renders.
 *
 * <p>{@link #primaryValue()} is a {@link BigDecimal} (always scaled to 4
 * decimal places, {@link java.math.RoundingMode#HALF_UP}) so it can be
 * handed straight to {@code PreparedStatement.setBigDecimal} without a
 * lossy {@code double → BigDecimal} round-trip.
 *
 * <p>{@link #primaryUnit()} carries the human-readable unit string (e.g.
 * {@code "mm³"}, {@code "µm"}); {@link #payload()} is the full task-specific
 * blob (biomarker breakdown, ETDRS rings, per-B-scan arrays etc.).
 */
public record ComputedMetrics(BigDecimal primaryValue,
                              String primaryUnit,
                              Map<String, Object> payload) {
}
