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
 * Phase E A8.6 — POST
 * {@code /api/v1/studies/{studyOid}/group-classes} request body.
 *
 * <p>Mirrors {@code CreateSubjectGroupClassServlet}'s submit step:
 * create the parent group-class row + the initial set of child
 * {@code study_group} rows in one transaction (well, two DAO calls —
 * the legacy code doesn't wrap them, but they share the same
 * connection pool and we mirror that).
 *
 * <p>Required: {@code name} (≤30 — matches the legacy column width),
 * {@code groupClassType} (one of {@code Arm} / {@code Family} /
 * {@code Demographic} / {@code Other}), {@code subjectAssignment}
 * (one of {@code REQUIRED} / {@code OPTIONAL} per the legacy form's
 * radio buttons).
 *
 * <p>{@code groups} is the initial child-group list; the legacy form
 * accepts ≥1 group. An empty list is allowed but the SPA discourages
 * it (a group class with no groups can't classify any subject).
 */
@Schema(name = "CreateGroupClassRequest")
public record CreateGroupClassRequest(
        String name,
        String groupClassType,
        String subjectAssignment,
        List<GroupInput> groups
) {

    @Schema(name = "GroupInput")
    public record GroupInput(
            String name,
            String description
    ) {}
}
