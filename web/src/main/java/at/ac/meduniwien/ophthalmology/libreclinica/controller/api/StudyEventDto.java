/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Phase E.4 M11 — wire-shape for {@code GET /pages/api/v1/events}
 * and the response body of {@code POST /pages/api/v1/events}.
 *
 * @param id                stringified study_event_id (opaque to SPA)
 * @param subjectId         StudySubject.label
 * @param eventDefinitionOid OID of the StudyEventDefinition this
 *                          row instantiates
 * @param eventLabel        human-readable event name (def.name)
 * @param ordinal           sample_ordinal (1 for first instance,
 *                          ≥2 for repeating events)
 * @param dateStarted       ISO YYYY-MM-DD; empty when unscheduled
 * @param dateEnded         ISO YYYY-MM-DD; null when open
 * @param location          free-text location, null when blank
 * @param status            mapped subject_event_status:
 *                          {@code scheduled | not-scheduled |
 *                          data-entry-started | completed | stopped |
 *                          skipped | locked | signed}
 * @param repeating         whether the def has repeating=true
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StudyEventDto(
        String id,
        String subjectId,
        String eventDefinitionOid,
        String eventLabel,
        int ordinal,
        String dateStarted,
        String dateEnded,
        String location,
        String status,
        boolean repeating
) {}
