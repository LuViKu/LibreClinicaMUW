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
import java.util.List;

/**
 * Phase E.6 dde — wire-shape for
 * {@code GET /api/v1/eventCrfs/{id}/dde-conflicts}.
 *
 * <p>Side-by-side view of pass-1 / pass-2 values for every item the
 * DDE diff flagged as mismatching. Consumed by {@code DdeReconcileView}.
 *
 * <p>Authorization: DM / Admin / Investigator only (the operators
 * permitted to choose the canonical value). Lower-tier clerks see
 * a 403.
 *
 * @param eventCrfOid numeric event_crf_id as a string
 * @param subjectId   StudySubject.label (e.g. "M-001")
 * @param crfName     human-friendly CRF name for the header
 * @param items       per-item conflict rows
 */
@Schema(name = "DdeConflictsDto")
public record DdeConflictsDto(
        String eventCrfOid,
        String subjectId,
        String crfName,
        List<DdeConflictItemDto> items
) {

    /**
     * Single conflict row.
     *
     * @param itemOid    item OID
     * @param label      display label (item_form_metadata.left_item_text)
     * @param ideValue   pass-1 IDE value (string-serialised)
     * @param ddeValue   pass-2 DDE value (string-serialised)
     * @param resolved   true once a DM/Admin has resolved this item
     * @param winner     null | "ide" | "dde" | "manual" (set when resolved)
     */
    @Schema(name = "DdeConflictItemDto")
    public record DdeConflictItemDto(
            String itemOid,
            String label,
            String ideValue,
            String ddeValue,
            boolean resolved,
            String winner
    ) {}
}
