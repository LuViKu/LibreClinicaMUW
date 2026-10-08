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
 * Response of {@code GET /api/v1/studies/{studyOid}/event-definitions/{sedOid}/removal-impact}:
 * what removing the event definition would remove with it, for the SPA's
 * confirm dialog.
 *
 * <p>Removal marks every row counted here auto-removed, as legacy
 * {@code RemoveEventDefinitionServlet} does; restoring the definition brings
 * back the auto-removed rows and leaves rows removed on their own removed.
 * Rows already removed are not counted.
 */
@Schema(name = "EventDefinitionRemovalImpact",
        description = "Rows that removing an event definition would remove with it.")
public record EventDefinitionRemovalImpactDto(
        @Schema(description = "CRF assignments of the definition (event_definition_crf).")
        int crfAssignments,

        @Schema(description = "Visits scheduled from the definition (study_event).")
        int visits,

        @Schema(description = "Distinct subjects those visits belong to.")
        int subjects,

        @Schema(description = "CRFs started or completed in those visits (event_crf).")
        int eventCrfs,

        @Schema(description = "Values entered in those CRFs (item_data).")
        int itemValues
) { }
