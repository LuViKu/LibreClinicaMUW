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
 * Response of {@code GET /api/v1/eventCrfs/{id}/removal-impact}: what removing
 * the CRF would take out of the subject's data, for the SPA's confirm dialog.
 *
 * <p>Removal marks the CRF's values auto-removed and closes the open
 * discrepancy-note threads on them; restoring the CRF brings the values
 * back, not the threads.
 */
@Schema(name = "EventCrfRemovalImpact",
        description = "What removing an event CRF would take out of the subject's data.")
public record EventCrfRemovalImpactDto(
        @Schema(description = "Values entered on the CRF (item_data rows with a value, not removed on their own).")
        int values,

        @Schema(description = "Open discrepancy-note threads on those values; the removal closes them.")
        int openNoteThreads
) { }
