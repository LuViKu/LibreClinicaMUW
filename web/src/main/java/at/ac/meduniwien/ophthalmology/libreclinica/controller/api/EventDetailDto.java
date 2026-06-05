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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.6 — wire shape for {@code GET /pages/api/v1/events/{id}}.
 *
 * <p>Drives the SPA's standalone Event Detail view (replaces the
 * legacy {@code /pages/EnterDataForStudyEvent} JSP redirect). One
 * row per event_definition_crf entry the event definition wires in;
 * each row may or may not have a backing {@code event_crf} row yet.
 *
 * @param eventId               numeric study_event.id (PK)
 * @param eventDefinitionOid    OID of the StudyEventDefinition
 * @param eventDefinitionName   human-readable definition name
 * @param subjectLabel          StudySubject.label (display id)
 * @param subjectOid            StudySubject.oid (path key for the
 *                              {@code /subjects/{label}} SPA view)
 * @param studyOid              owning study (or site) OID
 * @param studyName             owning study (or site) display name
 * @param dateStart             ISO YYYY-MM-DD; empty when unset
 * @param status                mapped subject_event_status, mirrors
 *                              {@link StudyEventDto#status()}
 * @param ordinal               sample_ordinal — 1 for first instance,
 *                              ≥2 for repeating events
 * @param repeating             whether the def has repeating=true
 * @param crfs                  one row per event_definition_crf,
 *                              ordered by the definition's ordinal
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "EventDetailDto")
public record EventDetailDto(
        int eventId,
        String eventDefinitionOid,
        String eventDefinitionName,
        String subjectLabel,
        String subjectOid,
        String studyOid,
        String studyName,
        String dateStart,
        String status,
        int ordinal,
        boolean repeating,
        List<EventCrfRowDto> crfs
) {}
