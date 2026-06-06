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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.6 {@code bulk-import} — preview shape returned by
 * {@code POST /pages/api/v1/import}.
 *
 * <p>Wraps the parsed ODM container's high-level counts + a flat
 * issues list + a window of inline preview rows so the SPA can render
 * the "review-then-commit" UX in one round-trip for typical imports.
 * Imports with >{@link #INLINE_ROW_CAP} rows page the remainder via
 * {@code GET /pages/api/v1/import/{token}/rows}.
 *
 * <p>{@code previewToken} is a server-issued UUID the operator passes
 * back to {@code POST /pages/api/v1/import/commit} to retrieve the
 * parked ODM container from the session-scoped cache. Tokens expire
 * 15 minutes after issue — beyond that the commit endpoint returns
 * 410 Gone. See {@link ImportApiController#PREVIEW_TTL_SECONDS}.
 *
 * <p>The DTO mirrors the RX.2 {@code RulesImportPreviewDto} shape so
 * SPA store + view code can use the same discriminated-union plumbing.
 */
public record ImportCrfPreviewDto(
        @Schema(description = "Opaque token returned by /import; pass it back to /import/commit within 15 minutes.")
        String previewToken,
        @Schema(description = "OID of the study the upload targets (echoed back from the ODM payload).")
        String studyOid,
        @Schema(description = "Original filename of the uploaded XML, for display.")
        String filename,
        @Schema(description = "Distinct subjects referenced in the ODM payload.")
        int subjectCount,
        @Schema(description = "Distinct study events referenced across all subjects.")
        int eventCount,
        @Schema(description = "Distinct CRFs (form_oid) referenced across all events.")
        int crfCount,
        @Schema(description = "Total item_data rows the commit would touch (insert + overwrite + skip).")
        int rowCount,
        @Schema(description = "Rows that would be inserted (no existing item_data row for the triple).")
        int insertCount,
        @Schema(description = "Rows that would overwrite an existing item_data value.")
        int overwriteCount,
        @Schema(description = "Rows the validator rejected; commit skips them.")
        int errorCount,
        @Schema(description = "Rows the validator flagged as soft warnings (out-of-range etc.); commit imports them and files a discrepancy.")
        int warningCount,
        @Schema(description = "Inline first page of preview rows (up to 200). Page the rest via /import/{token}/rows.")
        List<PreviewRowDto> rows,
        @Schema(description = "Validator findings keyed by scope/identifier; surface to operators before committing.")
        List<ImportIssue> issues) {

    /**
     * Cap on rows returned inline in the preview response. Beyond
     * this the SPA pages via {@code /import/{token}/rows?offset=&limit=}.
     * 200 matches the playbook spec and is comfortable in a single
     * fetch for the common case.
     */
    public static final int INLINE_ROW_CAP = 200;

    /**
     * One ODM row mapped against the study's item_data table. The SPA
     * renders one entry per row in the preview table; the {@code status}
     * + {@code action} pair drives the row's chip + diff display.
     *
     * @param status     wire-shape status — one of
     *                   {@code "ready"} / {@code "overwrite"} /
     *                   {@code "warning"} / {@code "error"}
     * @param action     what the commit will do — one of
     *                   {@code "insert"} / {@code "overwrite"} /
     *                   {@code "skip"} / {@code "flag"}
     * @param subjectOid subject_oid from the ODM SubjectData element
     * @param eventOid   event_oid + ordinal (e.g. {@code SE_V1}) from
     *                   the StudyEventData element
     * @param crfOid     form_oid from the FormData element
     * @param itemOid    item_oid + group from the ItemData element
     * @param before     existing value in the database (null when
     *                   {@code action == insert})
     * @param after      incoming value from the ODM payload (null when
     *                   {@code action == skip})
     * @param detail     human-readable diagnostic message for warnings
     *                   + errors (null for ready/overwrite)
     */
    public record PreviewRowDto(
            @Schema(description = "Row status: ready | overwrite | warning | error.") String status,
            @Schema(description = "Commit action: insert | overwrite | skip | flag.") String action,
            @Schema(description = "Subject OID from the ODM SubjectData element.") String subjectOid,
            @Schema(description = "Study event OID + ordinal.") String eventOid,
            @Schema(description = "CRF / form OID.") String crfOid,
            @Schema(description = "Item OID + group identifier.") String itemOid,
            @Schema(description = "Existing database value (null when the row will be inserted).") String before,
            @Schema(description = "Incoming value from the ODM payload (null when the row will be skipped).") String after,
            @Schema(description = "Diagnostic message for warnings + errors (null for ready / overwrite rows).") String detail) {}

    /**
     * One validator finding scoped to a single row or referenced
     * metadata. Mirrors the RX.2 {@code RulesImportPreviewDto.ImportIssue}
     * shape so SPA error-rendering code is unified.
     *
     * @param scope      {@code "metadata"} (study/event/CRF OID
     *                   missing) or {@code "row"} (item-level validator
     *                   message)
     * @param identifier subject + event + item triple for row scope, or
     *                   the offending OID for metadata scope
     * @param severity   {@code "ERROR"} (commit skips) or
     *                   {@code "WARNING"} (commit imports + files a
     *                   discrepancy note)
     * @param message    human-readable description; OCRERR_* messages
     *                   are resolved server-side before serialisation
     */
    public record ImportIssue(
            @Schema(description = "\"metadata\" or \"row\".") String scope,
            @Schema(description = "Subject/event/item triple or offending OID.") String identifier,
            @Schema(description = "\"ERROR\" (skip on commit) or \"WARNING\" (import + flag).") String severity,
            @Schema(description = "Validator message; typically an OCRERR_* code with substituted parameters.") String message) {}
}
