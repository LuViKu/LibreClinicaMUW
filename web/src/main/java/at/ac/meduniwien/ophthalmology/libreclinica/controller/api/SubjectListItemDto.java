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
 * Phase E.4 — Subject Matrix list-item DTO.
 *
 * <p>Wire shape consumed by the SPA {@code Subject} TypeScript type
 * in {@code web/src/spa/src/types/subject.ts}. Keep the field names
 * and null semantics in sync with that file; deviations break the
 * matrix render.
 *
 * <p>The adapter populates every field from the database:
 * <ul>
 *   <li>Identity columns ({@code id}, {@code secondaryId},
 *       {@code gender}, {@code yearOfBirth}, {@code enrolledOn},
 *       {@code site}) — {@code StudySubjectDAO} + {@code SubjectDAO}.</li>
 *   <li>{@code events[]} — {@code StudyEventDAO#findAllByStudySubject}
 *       joined with {@code StudyEventDefinitionDAO#findByPK}, ordered by
 *       definition ordinal.</li>
 *   <li>{@code signed} — true iff {@code study_subject.status_id = 8}
 *       (SIGNED). The legacy sign-subject transaction updates both
 *       study_subject + every study_event in one shot, so the subject
 *       status is authoritative.</li>
 *   <li>{@code openQueries} — sum of per-event open-query counts;
 *       per-event counts join {@code discrepancy_note} via
 *       {@code dn_item_data_map} with {@code resolution_status_id IN
 *       (1, 2, 3)} and {@code parent_dn_id IS NULL}.</li>
 *   <li>{@code status} (Phase E.6) — coarse string mapping of
 *       {@code study_subject.status_id}. The matrix uses this to render
 *       "Removed" / "Locked" rows differently when the SPA's
 *       {@code includeRemoved=true} filter is in effect.</li>
 *   <li>{@code groupAssignments} (Phase E.6) — flattened active
 *       {@code subject_group_map} rows. Disabled rows are excluded.
 *       {@code null} when the matrix-side N+1 mitigation defers the
 *       per-row fetch.</li>
 * </ul>
 *
 * <p>{@code groupLabel} stays {@code null} — the free-text column was
 * never used in production; {@code groupAssignments} is the structured
 * replacement.
 */
@Schema(name = "SubjectListItemDto")
public record SubjectListItemDto(
        String id,
        String secondaryId,
        String siteOid,
        String siteLabel,
        String gender,
        Integer yearOfBirth,
        String groupLabel,
        String enrolledOn,
        List<EventCellDto> events,
        boolean signed,
        int openQueries,
        /**
         * Phase E.6 Tier 1 — ophthalmology study-eye scope (matrix view).
         * One of {@code "OD" / "OS" / "OU"} or {@code null}. The matrix
         * surfaces this as an at-a-glance column so investigators can
         * filter to one-eye cohorts without opening each subject.
         */
        String studyEye,
        /**
         * Phase E.6 subject-lifecycle — coarse {@code study_subject.status}
         * surface so the SPA can render "Removed" / "Locked" rows
         * differently when {@code includeRemoved=true} is in effect.
         * One of {@code "available" / "removed" / "locked" / "signed" /
         * "auto-removed"}; null in unreachable edge cases.
         */
        String status,
        /**
         * Phase E.6 subject-lifecycle — flattened group-class assignments
         * the subject is enrolled in (Arms / Strata / etc.).
         *
         * <p>One snapshot per ACTIVE {@code subject_group_map} row;
         * disabled assignments are filtered out. {@code null} groupId
         * means the row exists but the user picked the
         * "OPTIONAL not-now" path — only valid for OPTIONAL classes.
         *
         * <p>May be {@code null} (not just empty) when the list-side
         * adapter has not loaded group state — the SPA treats null as
         * "unknown, fetch detail" rather than "empty".
         */
        List<GroupAssignmentSnapshot> groupAssignments
) {

    /**
     * One cell in the per-event status grid.
     *
     * <p>{@code status} is one of the SPA {@code EventStatus} union values:
     * "scheduled", "not-scheduled", "in-progress", "complete", "locked",
     * "signed". {@code openQueries} counts open discrepancy notes on
     * {@code item_data} rows under this study event.
     */
    @Schema(name = "EventCellDto")
    public record EventCellDto(
            String eventDefinitionOid,
            String label,
            String status,
            int openQueries
    ) {}
}
