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
 * <p>Phase E.6 ({@code crf-data-types}, 2026-06-05) extended the
 * payload with two top-level fields:
 * <ul>
 *   <li>{@code groups} — one {@link CrfItemGroupDto} per repeating
 *       item group on the form. Carries the saved rows (an ordered
 *       list of value maps) and the group's {@code repeatMax} so the
 *       SPA can grey out the "add row" button once the cap is hit.</li>
 *   <li>{@code maxFileBytes} / {@code fileExtensions} — file-upload
 *       guardrails sourced from {@code crf.file.*} datainfo properties.
 *       The SPA shows the cap in the dropzone helper and pre-validates
 *       client-side before the multipart POST.</li>
 * </ul>
 *
 * @param eventCrfOid     numeric event_crf_id as a string (the SPA
 *                        treats it as opaque)
 * @param subjectId       StudySubject.label (e.g. "M-001")
 * @param eventLabel      friendly event name (e.g. "V1 Inclusion")
 * @param schema          CRF version + sections + items
 * @param values          saved values keyed by item OID (single-row only)
 * @param groups          repeating item groups + saved rows (E.6)
 * @param maxFileBytes    server-side cap on per-file uploads (E.6)
 * @param fileExtensions  comma-joined allowlist for upload items (E.6)
 * @param status          CRF entry workflow status
 * @param lastSavedAt     ISO-8601 of last successful save, or null
 */
@Schema(name = "CrfEntryDto")
public record CrfEntryDto(
        String eventCrfOid,
        String subjectId,
        String eventLabel,
        CrfSchemaDto schema,
        Map<String, Object> values,
        List<CrfItemGroupDto> groups,
        Long maxFileBytes,
        String fileExtensions,
        String status,
        String lastSavedAt
) {

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
     * select-one | select-multi | boolean | file}. {@code options} is
     * populated only for {@code select-one} / {@code select-multi}.
     *
     * <p>Phase E.6: the {@code file} data type is new; renders as a
     * dropzone widget in the SPA. The {@code groupOid} field, when set,
     * indicates the item belongs to a repeating group and the SPA
     * should render it inside the matching {@link CrfItemGroupDto}'s
     * row template rather than as a top-level value.
     *
     * <p>Optional fields ({@code options}, {@code helper}, {@code min},
     * {@code max}, {@code groupOid}) are {@code null} when absent;
     * Jackson serialises them as {@code null} or omits them per the
     * SPA contract (the SPA tolerates both).
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
            Double max,
            String groupOid
    ) {}

    /** Single allowed option for {@code select-one} / {@code select-multi}. */
    @Schema(name = "ResponseOptionDto")
    public record ResponseOptionDto(
            String code,
            String label
    ) {}

    /**
     * A repeating item group (Phase E.6). {@code rows} is the
     * ordered list of saved rows; each row is keyed by item OID
     * just like the top-level {@code values} map. {@code repeatMax}
     * is the cap from {@code item_group_metadata}; the SPA disables
     * "Add row" once {@code rows.size() >= repeatMax}.
     *
     * <p>{@code itemOids} is the list of item OIDs that belong to
     * this group, in display order. The SPA uses it to build the
     * row's input grid without re-querying the schema.
     */
    @Schema(name = "CrfItemGroupDto")
    public record CrfItemGroupDto(
            String oid,
            String label,
            int repeatMax,
            List<String> itemOids,
            List<CrfGroupRowDto> rows
    ) {}

    /**
     * A single saved row inside a repeating item group. {@code ordinal}
     * is 1-based and matches {@code item_data.ordinal}.
     */
    @Schema(name = "CrfGroupRowDto")
    public record CrfGroupRowDto(
            int ordinal,
            Map<String, Object> values
    ) {}
}
