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
 * 2026-07-02 — response DTO for
 * {@code POST /api/v1/studies/{studyOid}/group-classes/{classId}/randomize}.
 *
 * <p>The SPA round-trips the {@code seed} + {@code source} back through
 * the AddSubject POST body so the enrollment path can persist them
 * onto the freshly-inserted {@code subject_group_map} row (via the
 * {@code randomization_source} / {@code randomization_seed} /
 * {@code randomization_meta} columns from
 * {@code lc-muw-2026-07-02-subject-randomization.xml}).
 */
@Schema(name = "RandomizeGroupClassResponse",
        description = "Result of a randomization pick — the group id + audit-grade metadata.")
public record RandomizeGroupClassResponse(
        @Schema(description = "Chosen study_group id.")
        int groupId,

        @Schema(description = "Chosen study_group name.")
        String groupName,

        @Schema(description = "Hex-encoded seed (32 bytes = 64 hex chars) used for the pick.")
        String seed,

        @Schema(description = "Which picker was invoked — RANDOMIZED_UNIFORM (v1) or RANDOMIZED_WEIGHTED (v2a).")
        String source,

        @Schema(description = "JSON-encoded metadata envelope. Null for RANDOMIZED_UNIFORM; carries "
                            + "{\"ratio\":[w1,w2,...]} for RANDOMIZED_WEIGHTED. The SPA opaquely "
                            + "round-trips this field into the enrollment POST.")
        String meta,

        @Schema(description = "study_group_class_id the pick belongs to (mirrors the URL path variable).")
        int groupClassId,

        @Schema(description = "study_group_class name (mirrors gc.name).")
        String groupClassName
) { }
