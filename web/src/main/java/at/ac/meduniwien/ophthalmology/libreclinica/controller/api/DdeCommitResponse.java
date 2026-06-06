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
 * Phase E.6 dde — wire-shape returned by
 * {@code POST /api/v1/eventCrfs/{id}/dde-commit}.
 *
 * <p>Two terminal shapes:
 * <ul>
 *   <li>{@code mismatchCount == 0}: clean DDE — the EventCRF flips to
 *       {@code dde-complete} and DATE_VALIDATE_COMPLETED is set via
 *       {@link at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO#markComplete}
 *       (ide=false). {@code status} = "dde-complete".</li>
 *   <li>{@code mismatchCount > 0}: mismatches found — N FAILEDVAL
 *       {@code discrepancy_note} rows persisted; EventCRF stays in
 *       {@code dde-conflicts}. SPA redirects the operator to
 *       {@code DdeReconcileView}. {@code status} = "dde-conflicts".</li>
 * </ul>
 *
 * @param eventCrfOid    numeric event_crf_id as a string
 * @param mismatchCount  number of items where pass-2 differs from pass-1
 * @param status         "dde-complete" | "dde-conflicts"
 * @param lastSavedAt    ISO-8601 instant of the commit
 */
@Schema(name = "DdeCommitResponse")
public record DdeCommitResponse(
        String eventCrfOid,
        int mismatchCount,
        String status,
        String lastSavedAt
) {}
