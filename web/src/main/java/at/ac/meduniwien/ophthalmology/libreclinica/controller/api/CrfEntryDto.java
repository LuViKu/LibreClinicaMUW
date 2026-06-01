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
import java.util.Map;

/**
 * Phase E.4 M5 — wire-shape for {@code GET /pages/api/v1/eventCrfs/{id}}.
 *
 * <p>Mirrors the Vue SPA's {@code CrfEntry} TS interface in
 * {@code web/src/spa/src/types/crf.ts} byte-for-byte. The store
 * (<code>crfEntry.ts</code>) hydrates from this DTO and binds form
 * inputs to the {@code values} map keyed by item OID. The
 * {@code schema} is render-driven — the SPA does not call any
 * additional metadata endpoints after this one.
 *
 * @param eventCrfOid  numeric event_crf_id as a string (the SPA
 *                     treats it as opaque)
 * @param subjectId    StudySubject.label (e.g. "M-001")
 * @param eventLabel   friendly event name (e.g. "V1 Inclusion")
 * @param schema       CRF version + sections + items
 * @param values       saved values keyed by item OID
 * @param status       CRF entry workflow status
 * @param lastSavedAt  ISO-8601 of last successful save, or null
 */
public record CrfEntryDto(
        String eventCrfOid,
        String subjectId,
        String eventLabel,
        CrfSchemaDto schema,
        Map<String, Object> values,
        String status,
        String lastSavedAt
) {

    /** Schema of a single CRF version (1:1 with the SPA's {@code CrfSchema}). */
    public record CrfSchemaDto(
            String oid,
            String name,
            String version,
            List<CrfSectionDto> sections
    ) {}

    /** Section header + items list (1:1 with the SPA's {@code CrfSection}). */
    public record CrfSectionDto(
            String oid,
            String title,
            String instructions,
            List<CrfItemDto> items
    ) {}

    /**
     * Single item (1:1 with the SPA's {@code CrfItem}). {@code dataType}
     * is one of: {@code string | integer | real | date | partial-date |
     * select-one | select-multi | boolean}. {@code options} is populated
     * only for {@code select-one} / {@code select-multi}.
     *
     * <p>Optional fields ({@code options}, {@code helper}, {@code min},
     * {@code max}) are {@code null} when absent; Jackson serialises them
     * as {@code null} or omits them per the SPA contract (the SPA
     * tolerates both).
     */
    public record CrfItemDto(
            String oid,
            String label,
            String dataType,
            boolean required,
            List<ResponseOptionDto> options,
            String helper,
            Double min,
            Double max
    ) {}

    /** Single allowed option for {@code select-one} / {@code select-multi}. */
    public record ResponseOptionDto(
            String code,
            String label
    ) {}
}
