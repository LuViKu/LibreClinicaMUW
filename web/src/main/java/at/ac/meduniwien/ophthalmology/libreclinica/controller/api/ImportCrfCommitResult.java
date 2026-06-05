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
 * Phase E.6 {@code bulk-import} — commit summary returned by
 * {@code POST /pages/api/v1/import/commit}.
 *
 * <p>Counts are projected from the parked ODM container + the inflight
 * persistence pass:
 *
 * <ul>
 *   <li><b>{@code rowsInserted}</b> — new item_data rows the commit
 *       added (no pre-existing value)</li>
 *   <li><b>{@code rowsOverwritten}</b> — existing item_data rows the
 *       commit replaced. Each carries a copy of {@code reasonForChange}
 *       in {@code audit_log.new_value} per 21 CFR Part 11</li>
 *   <li><b>{@code rowsSkipped}</b> — rows the validator rejected
 *       (errors) or the operator chose to skip via
 *       {@code overwriteMode = "skip"}</li>
 *   <li><b>{@code discrepancyNotes}</b> — soft-warning rows the commit
 *       imported with an attached open discrepancy note</li>
 *   <li><b>{@code committedAt}</b> — ISO-8601 instant captured
 *       server-side immediately after the transaction returned</li>
 * </ul>
 *
 * <p>{@code auditLogStudyId} echoes the active-study id so the SPA can
 * link the operator straight to the post-import audit-trail view
 * without a second resolution step.
 */
public record ImportCrfCommitResult(
        @Schema(description = "Number of new item_data rows added.") int rowsInserted,
        @Schema(description = "Number of existing item_data rows overwritten (each carries reasonForChange in audit_log).")
        int rowsOverwritten,
        @Schema(description = "Number of rows skipped — validator errors or operator opted into skip mode.")
        int rowsSkipped,
        @Schema(description = "Number of soft-warning rows imported with an attached open discrepancy note.")
        int discrepancyNotes,
        @Schema(description = "ISO-8601 instant the commit transaction completed (server clock).")
        String committedAt,
        @Schema(description = "Active study id at commit time (helper for the SPA audit-trail link).")
        int auditLogStudyId) {}
