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

import java.util.Map;

/**
 * Phase E.4 M6 + E.6 admin-rfc — body of
 * {@code POST /pages/api/v1/eventCrfs/{id}/items}.
 *
 * <p>Promoted from an inline record on {@link EventCrfsApiController}
 * in Phase E.6 to make room for the {@code reasons} payload that
 * carries the Reason-For-Change capture map (item OID → reason text).
 *
 * <p><strong>Wire shape:</strong>
 * <pre>{@code
 *   {
 *     "values":  { "I_HEIGHT_CM": 172, "I_WEIGHT_KG": 70.5 },
 *     "reasons": { "I_HEIGHT_CM": "Correcting transcription error" }
 *   }
 * }</pre>
 *
 * <p>{@code values} is required (controller returns 400 when null).
 * {@code reasons} is optional pre-completion (no DN written); after a
 * CRF's {@code date_completed} is set every changed item OID in
 * {@code values} MUST appear in {@code reasons} or the controller
 * returns 400 with {@code missingReasonItemOids: [oid…]} so the SPA
 * can re-arm the {@code ReasonForChangeModal}.
 *
 * <p>Each entry in {@code reasons} drives one
 * {@code discrepancy_note} row of {@code type_id = 4} ({@code REASON_FOR_CHANGE})
 * threaded under any prior RFC parent for the same {@code item_data}
 * (see {@link at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO#findLatestRfcParentForItemData}).
 *
 * <p><strong>NOT</strong> a typed {@code SaveItemsResponse} on the
 * return path — that DTO does not exist. The response is an inline
 * {@link java.util.LinkedHashMap} extended with {@code rfcCreatedCount: int}.
 */
@Schema(name = "SaveItemsRequest",
        description = "Bulk save body for POST /api/v1/eventCrfs/{id}/items.")
public record SaveItemsRequest(
        @Schema(description = "Item OID → new value. Required.",
                example = "{\"I_HEIGHT_CM\": 172, \"I_WEIGHT_KG\": 70.5}")
        Map<String, Object> values,
        @Schema(description = "Item OID → reason-for-change text. "
                + "Required for every changed item once the CRF is complete.",
                example = "{\"I_HEIGHT_CM\": \"Correcting transcription error\"}")
        Map<String, String> reasons
) {
    /** Compact convenience constructor for tests + back-compat with pre-RFC callers. */
    public SaveItemsRequest(Map<String, Object> values) {
        this(values, null);
    }
}
