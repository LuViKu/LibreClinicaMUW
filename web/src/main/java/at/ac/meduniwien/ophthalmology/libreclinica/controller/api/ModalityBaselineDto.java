/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.6 study-nurse polish — per-eye modality baseline wire shape.
 *
 * <p>One row per active modality on the subject + eye combination.
 * Each row carries TWO aggregates side-by-side:
 *
 * <ul>
 *   <li><strong>global</strong> — the earliest observation across ALL
 *       of the human's participations on this eye, regardless of study.
 *       Crosses cohort transitions (e.g. iAMD → GA) so the SPA can
 *       render the "first ever" baseline.</li>
 *   <li><strong>perStudy</strong> — the earliest observation restricted
 *       to the operator's currently-active study. The SPA renders this
 *       as the per-protocol baseline next to the global one.</li>
 * </ul>
 *
 * <p>{@code observationCount} on each {@code Aggregate} carries the
 * total count of non-null measurements in that scope so the SPA can
 * render "1 of 8" (per-study) vs "1 of 47" (global) badges next to
 * the baseline date.
 *
 * <p>{@code itemOid} carries the OID actually used for the eye-specific
 * lookup ({@code item_oid_od} when {@code eye=OD}, {@code item_oid_os}
 * when {@code eye=OS}). Surfacing it lets the SPA's "open in casebook"
 * deep link populate without re-walking the modality row.
 */
@Schema(name = "ModalityBaselineDto")
public record ModalityBaselineDto(
        String modalityCode,
        String labelEn,
        String labelDe,
        String itemOid,
        String dataType,
        String unit,
        Aggregate global,
        Aggregate perStudy
) {

    /**
     * Earliest-observation aggregate. {@code date} is ISO {@code YYYY-MM-DD}
     * (the event_crf.date_completed date portion); {@code value} is the
     * raw {@code item_data.value} string for that row. Both are
     * {@code null} when no observations exist in scope.
     */
    @Schema(name = "ModalityBaselineAggregate")
    public record Aggregate(
            String date,
            String value,
            int observationCount
    ) {}
}
