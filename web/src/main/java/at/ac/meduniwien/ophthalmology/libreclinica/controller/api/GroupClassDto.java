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
 * Phase E A8.6 — subject-group-class wire shape.
 *
 * <p>One row per {@code study_group_class} entry with the active
 * child {@code study_group} rows inlined. {@code id} is the numeric
 * legacy primary key — the schema doesn't carry an OID column on
 * this table, so the SPA references group classes by id.
 */
@Schema(name = "GroupClassDto")
public record GroupClassDto(
        int id,
        String name,
        String groupClassType,
        String subjectAssignment,
        String status,
        List<GroupDto> groups
) {

    @Schema(name = "GroupDto")
    public record GroupDto(
            int id,
            String name,
            String description,
            String status
    ) {}
}
