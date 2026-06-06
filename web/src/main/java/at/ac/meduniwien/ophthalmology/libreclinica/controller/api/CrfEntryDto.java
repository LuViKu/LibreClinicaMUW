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
 * @param dde          Phase E.6 dde — non-null only when the parent
 *                     event_definition_crf has doubleEntry=true.
 *                     Tells the SPA which DDE pass to render (blind
 *                     pass-2 banner vs. normal entry).
 */
@Schema(name = "CrfEntryDto")
public record CrfEntryDto(
        String eventCrfOid,
        String subjectId,
        String eventLabel,
        CrfSchemaDto schema,
        Map<String, Object> values,
        String status,
        String lastSavedAt,
        DdeBlockDto dde
) {

    /**
     * Phase E.6 dde — small marker block embedded into
     * {@link CrfEntryDto} when the parent event_definition_crf has
     * {@code double_entry=true}. The SPA reads {@code pass} to choose
     * the entry view variant:
     * <ul>
     *   <li>{@code pass=1} — render the normal CRF Entry form, same
     *       as today.</li>
     *   <li>{@code pass=2} — render with the blind-second-pass banner
     *       and an EMPTY values map; on save delegate to
     *       {@code POST /dde-commit}.</li>
     *   <li>{@code pass=reconcile} — redirect to {@code DdeReconcileView}.</li>
     * </ul>
     *
     * @param pass           {@code 1 | 2 | reconcile}
     * @param idePass1ClerkId numeric user id of the pass-1 clerk (0 when unknown)
     */
    @Schema(name = "DdeBlockDto")
    public record DdeBlockDto(
            String pass,
            int idePass1ClerkId
    ) {}


    /** Schema of a single CRF version (1:1 with the SPA's {@code CrfSchema}). */
    @Schema(name = "CrfSchemaDto")
    public record CrfSchemaDto(
            String oid,
            String name,
            String version,
            List<CrfSectionDto> sections
    ) {}

    /** Section header + items list (1:1 with the SPA's {@code CrfSection}). */
    @Schema(name = "CrfSectionDto")
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
    @Schema(name = "CrfItemDto")
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
    @Schema(name = "ResponseOptionDto")
    public record ResponseOptionDto(
            String code,
            String label
    ) {}
}
