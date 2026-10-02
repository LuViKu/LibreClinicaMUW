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
 * Response of {@code GET /api/v1/studies/{studyOid}/group-classes/{groupClassId}/removal-impact}:
 * what removing the group class would remove with it, for the SPA's confirm
 * dialog.
 *
 * <p>Removal marks the subject assignments counted here auto-removed, as
 * legacy {@code RemoveSubjectGroupClassServlet} does; restoring the class
 * brings them back. The groups themselves have no status and stay as they
 * are, unreachable while their class is removed.
 */
@Schema(name = "GroupClassRemovalImpact",
        description = "Rows that removing a subject group class would remove with it.")
public record GroupClassRemovalImpactDto(
        @Schema(description = "Groups of the class (study_group).")
        int groups,

        @Schema(description = "Subject assignments to the class that are not removed (subject_group_map).")
        int subjectAssignments
) { }
