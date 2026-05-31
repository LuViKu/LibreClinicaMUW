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
 * <p>First-cut adapter (E.4 slice #1) populates identity columns
 * (id, secondaryId, gender, yearOfBirth, enrolledOn, site) from
 * {@code StudySubjectDAO} + {@code SubjectDAO}. Per-event status,
 * open-query count, and sign-off state come from the EventCRF /
 * DiscrepancyNote aggregations and ship in subsequent slices. For
 * now {@code events} is always an empty list, {@code openQueries}
 * is always {@code 0}, and {@code signed} is always {@code false}.
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

    /** Stub for the per-event status cell — populated by the next adapter. */
    public record EventCellDto(
            String eventDefinitionOid,
            String label,
            String status,
            int openQueries
    ) {}
}
