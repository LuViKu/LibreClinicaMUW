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
import java.util.List;

/**
 * Phase E A8.6 — PUT
 * {@code /api/v1/studies/{studyOid}/group-classes/{groupClassId}}
 * request body.
 *
 * <p>Top-level identity fields are optional ({@code null} =
 * unchanged). The {@code groups} list, when present, is treated as
 * a <em>replace</em> of the active child-group set: existing groups
 * matched by id are updated; new entries (id == null or id == 0) are
 * created; existing groups absent from the list are soft-deleted.
 * When {@code groups} is null, the child set is left untouched.
 */
@Schema(name = "UpdateGroupClassRequest")
public record UpdateGroupClassRequest(
        String name,
        String groupClassType,
        String subjectAssignment,
        List<GroupEntry> groups
) {

    @Schema(name = "GroupEntry")
    public record GroupEntry(
            Integer id,
            String name,
            String description
    ) {}
}
