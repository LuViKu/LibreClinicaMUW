/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

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
 * </ul>
 *
 * <p>{@code groupLabel} stays {@code null} until the
 * {@code study_group_class} / {@code subject_group_map} workflow is
 * wired (deferred to a later milestone).
 */
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
        int openQueries
) {

    /**
     * One cell in the per-event status grid.
     *
     * <p>{@code status} is one of the SPA {@code EventStatus} union values:
     * "scheduled", "not-scheduled", "in-progress", "complete", "locked",
     * "signed". {@code openQueries} counts open discrepancy notes on
     * {@code item_data} rows under this study event.
     */
    public record EventCellDto(
            String eventDefinitionOid,
            String label,
            String status,
            int openQueries
    ) {}
}
