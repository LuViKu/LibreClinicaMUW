/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.6 {@code bulk-import} — commit summary returned by
 * {@code POST /pages/api/v1/import/commit}.
 *
 * <p>Counts come from the legacy save pass the commit runs:
 *
 * <ul>
 *   <li><b>{@code rowsInserted}</b> — new item_data rows the commit
 *       added (no pre-existing value), including the blank rows the
 *       legacy import adds to fill a gap in a repeating group</li>
 *   <li><b>{@code rowsOverwritten}</b> — stored values the commit
 *       replaced with a different value. Each has an audit row carrying
 *       {@code reasonForChange} per 21 CFR Part 11</li>
 *   <li><b>{@code rowsSkipped}</b> — values the preview showed as
 *       skipped (the CRF is not open to the import, or the same value is
 *       stored), plus the overwrites left out with
 *       {@code overwriteMode = "skip"}</li>
 *   <li><b>{@code discrepancyNotes}</b> — Failed Validation Check notes
 *       the commit filed on imported values</li>
 *   <li><b>{@code committedAt}</b> — ISO-8601 instant captured
 *       server-side once the save returned</li>
 *   <li><b>{@code ruleWarnings}</b> — what the study's rules reported
 *       when they ran on the imported data, as the legacy import shows
 *       them</li>
 * </ul>
 *
 * <p>{@code auditLogStudyId} echoes the active-study id so the SPA can
 * link the operator straight to the post-import audit-trail view
 * without a second resolution step.
 */
public record ImportCrfCommitResult(
        @Schema(description = "Number of new item_data rows added.") int rowsInserted,
        @Schema(description = "Number of stored values replaced (each has a reason-for-change audit row).")
        int rowsOverwritten,
        @Schema(description = "Number of values not written — not open to the import, unchanged, or left out in skip mode.")
        int rowsSkipped,
        @Schema(description = "Number of Failed Validation Check discrepancy notes filed on imported values.")
        int discrepancyNotes,
        @Schema(description = "ISO-8601 instant the commit completed (server clock).")
        String committedAt,
        @Schema(description = "Active study id at commit time (helper for the SPA audit-trail link).")
        int auditLogStudyId,
        @Schema(description = "Warnings the study's rules reported on the imported data; empty when none ran.")
        List<String> ruleWarnings) {}
