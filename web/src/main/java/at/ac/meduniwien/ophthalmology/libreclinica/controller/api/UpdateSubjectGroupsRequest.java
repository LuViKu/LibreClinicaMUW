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
 * Phase E.6 subject-lifecycle — body of
 * {@code PUT /api/v1/subjects/{oid}/groups}.
 *
 * <p>One entry per group-class the SPA wants the subject to participate
 * in after the call. The service reconciles this list against the
 * existing active {@code subject_group_map} rows: new entries are
 * inserted, missing entries are soft-deleted (status → DELETED), and
 * changed-group entries are deleted + re-inserted (the legacy schema
 * has no UPDATE-in-place path for {@code study_group_id} that
 * preserves audit semantics).
 *
 * <p>OPTIONAL not-now is expressed with a non-null {@code groupClassId}
 * + null {@code groupId}. REQUIRED classes always need a concrete
 * group; the controller short-circuits with 400 if {@code groupId}
 * is omitted on a REQUIRED row.
 */
@Schema(name = "UpdateSubjectGroupsRequest")
public record UpdateSubjectGroupsRequest(
        List<Assignment> assignments
) {

    @Schema(name = "Assignment")
    public record Assignment(
            int groupClassId,
            Integer groupId
    ) {}
}
