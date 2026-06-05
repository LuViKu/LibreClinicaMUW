/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

/**
 * Phase E.6 — Data Export Phase 4.
 *
 * <p>Wire shape for an async export job. Returned by:
 * <ul>
 *   <li>{@code POST /api/v1/datasets/{id}/exports} (202, queued)</li>
 *   <li>{@code GET  /api/v1/exports/{jobId}} (current status)</li>
 *   <li>{@code GET  /api/v1/exports} (paged list, per row)</li>
 *   <li>{@code GET  /api/v1/studies/{oid}/export-jobs} (recent jobs)</li>
 * </ul>
 *
 * <p>Timestamps are ISO-8601 strings (UTC) so the SPA can hand them
 * straight to {@code new Date(...)} / Intl.DateTimeFormat without
 * timezone gymnastics. Null fields are emitted as JSON {@code null}
 * (Jackson default) — the SPA's discriminators key off this:
 * <ul>
 *   <li>{@code startedAt} null + {@code status='queued'} ↔ pre-pickup</li>
 *   <li>{@code finishedAt} null + {@code status='running'} ↔ in flight</li>
 *   <li>{@code downloadUrl} non-null ↔ archived file ready to fetch</li>
 *   <li>{@code errorMessage} non-null ↔ {@code status='failed'}</li>
 * </ul>
 *
 * <p>{@code progressPct} is a coarse server-side hint (0 queued, 50
 * running, 100 done/failed) — the underlying extract pipeline doesn't
 * emit per-step progress events, so the SPA renders an indeterminate
 * shimmer when {@code status='running'} rather than relying on the
 * exact percent.
 */
public record ExportJobDto(
        long id,
        int datasetId,
        String format,
        String status,
        int progressPct,
        String submittedAt,
        String startedAt,
        String finishedAt,
        Integer archivedDatasetFileId,
        String errorMessage,
        String downloadUrl) {
}
