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
 * Phase E.6 subject-lifecycle — one row in the per-subject
 * {@code subject_group_map} surface returned on both
 * {@link SubjectListItemDto} (matrix) and {@link SubjectDetailDto}
 * (detail view).
 *
 * <p>Identifiers are the legacy numeric ids — neither
 * {@code study_group_class} nor {@code study_group} carry an OID
 * column. The SPA references both rows by id; see
 * {@link GroupClassesApiController} JavaDoc for the identifier
 * decision.
 *
 * <p>{@code groupId} is nullable: {@code subject_group_map} can carry
 * an {@code OPTIONAL not-now} marker (a row exists for the
 * subject/class pair, but no concrete group was picked). The legacy
 * {@code AddNewSubjectServlet} writes a placeholder map row for
 * REQUIRED classes, never for OPTIONAL — but the GUI's edit modal
 * surfaces "not picked" as a first-class concept, and the wire shape
 * preserves it.
 *
 * <p>{@code subjectAssignment} mirrors the parent class's
 * {@code REQUIRED / OPTIONAL} marker so the SPA can render REQUIRED
 * rows with a stronger affordance ("you must pick a group").
 */
@Schema(name = "GroupAssignmentSnapshot")
public record GroupAssignmentSnapshot(
        int groupClassId,
        String groupClassName,
        Integer groupId,
        String groupName,
        String subjectAssignment
) {}
