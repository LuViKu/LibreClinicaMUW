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
 * <p>Wire shape for an {@code export_schedule} row. Returned by:
 * <ul>
 *   <li>{@code POST   /api/v1/datasets/{id}/schedules} (201 on create)</li>
 *   <li>{@code GET    /api/v1/datasets/{id}/schedules} (list, active first)</li>
 * </ul>
 *
 * <p>The {@code DELETE} endpoint returns 204 (no body) — it is a soft
 * delete that flips {@code active=false}, so callers re-fetch the list
 * to refresh the UI.
 */
public record ExportScheduleDto(
        long id,
        int datasetId,
        String format,
        String cronExpression,
        boolean active,
        String createdAt,
        String nextRunAt,
        String lastRunAt,
        Long lastRunJobId) {
}
