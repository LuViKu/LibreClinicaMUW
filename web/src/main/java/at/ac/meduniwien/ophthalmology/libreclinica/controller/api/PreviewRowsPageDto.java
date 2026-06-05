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
 * Phase E.6 {@code bulk-import} — explicit wrapper for the paginated
 * preview-rows endpoint.
 *
 * <p>Reviewer flag (playbook §4 DTO table): rather than returning a
 * Spring {@code PageImpl<PreviewRowDto>} — which serialises to a
 * partly-undocumented {@code pageable / sort / numberOfElements / …}
 * shape that drifts across Spring minor versions — we declare an
 * explicit wrapper. The SPA TS mirror in
 * {@code web/src/spa/src/types/importCrf.ts} is then unambiguous.
 *
 * <p>The endpoint is {@code GET /pages/api/v1/import/{token}/rows
 * ?offset=&limit=} and is keyed by the same preview token the upload
 * returns. Auth is the same as {@code POST /import}: sysadmin OR
 * Director / Coordinator / Investigator / RA bound to the active
 * study. Listing rows from a stale token returns 410.
 */
public record PreviewRowsPageDto(
        @Schema(description = "Total row count across the entire preview (matches ImportCrfPreviewDto.rowCount).")
        int total,
        @Schema(description = "0-based offset of the first row in this page.") int offset,
        @Schema(description = "Number of rows requested for this page (≤ total - offset).") int limit,
        @Schema(description = "Page payload, ordered by subject_oid → event_oid → crf_oid → item_oid.")
        List<ImportCrfPreviewDto.PreviewRowDto> rows) {}
