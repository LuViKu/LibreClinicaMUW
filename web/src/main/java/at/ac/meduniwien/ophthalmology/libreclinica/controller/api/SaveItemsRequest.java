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
 * Phase E.4 M6 + E.6 — body of {@code POST /pages/api/v1/eventCrfs/{id}/items}.
 *
 * <p>Promoted from an inline record on {@link EventCrfsApiController}
 * in Phase E.6 to make room for (a) the {@code reasons} payload from the
 * admin-rfc cluster (Reason-For-Change capture) and (b) the {@code groups}
 * payload from the crf-data-types cluster (per-row repeating-group saves).
 *
 * <p><strong>Wire shape:</strong>
 * <pre>{@code
 *   {
 *     "values":  { "I_HEIGHT_CM": 172, "I_WEIGHT_KG": 70.5 },
 *     "reasons": { "I_HEIGHT_CM": "Correcting transcription error" },
 *     "groups":  [ { "groupOid": "IG_VS", "rowOrdinal": 1, "values": { "I_BP_SYS": 120 } } ]
 *   }
 * }</pre>
 *
 * <ul>
 *   <li>{@code values} — single-row items keyed by item OID. Required (controller
 *       returns 400 when null). Arrays land for {@code select-multi} items; the
 *       controller comma-joins them at write time.</li>
 *   <li>{@code reasons} — ignored until the CRF has been completed once (no DN
 *       written). From then on, reopened or not, and when the study sets
 *       {@code adminForcedReasonForChange}, every changed value MUST appear in
 *       {@code reasons} — keyed by item OID, or by {@code OID[row]} for a value
 *       in a row of {@code groups} — or the controller returns 400 with
 *       {@code missingReasonItemOids: [key…]} so the SPA can re-arm the
 *       {@code ReasonForChangeModal}. Each entry drives one {@code discrepancy_note}
 *       row of {@code type_id = 4} ({@code REASON_FOR_CHANGE}) threaded under any
 *       prior RFC parent for the same {@code item_data} (see
 *       {@link at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO#findLatestRfcParentForItemData}).</li>
 *   <li>{@code groups} — repeating-group rows. Each {@link GroupRowSavePayload}
 *       carries the group OID, the 1-based row ordinal, and the per-item values
 *       for that row. Empty/null means the SPA only saved top-level items.</li>
 * </ul>
 *
 * <p><strong>NOT</strong> a typed {@code SaveItemsResponse} on the return path —
 * that DTO does not exist. The response is an inline {@link java.util.LinkedHashMap}
 * extended with {@code rfcCreatedCount: int} and {@code groupRowsSaved: int}.
 */
@Schema(name = "SaveItemsRequest",
        description = "Bulk save body for POST /api/v1/eventCrfs/{id}/items.")
public record SaveItemsRequest(
        @Schema(description = "Item OID → new value. Required.",
                example = "{\"I_HEIGHT_CM\": 172, \"I_WEIGHT_KG\": 70.5}")
        Map<String, Object> values,
        @Schema(description = "Item OID, or OID[row] for a repeating-group value, → "
                + "reason-for-change text. Required for every changed value once the CRF "
                + "has been completed, when the study forces a reason for change.",
                example = "{\"I_HEIGHT_CM\": \"Correcting transcription error\"}")
        Map<String, String> reasons,
        @Schema(description = "Repeating-group row payloads (E.6 crf-data-types).")
        List<GroupRowSavePayload> groups
) {
    /** Compact convenience constructor for tests + back-compat with pre-RFC callers. */
    public SaveItemsRequest(Map<String, Object> values) {
        this(values, null, null);
    }

    /** Convenience constructor for callers that have values + reasons but no groups. */
    public SaveItemsRequest(Map<String, Object> values, Map<String, String> reasons) {
        this(values, reasons, null);
    }

    /** Per-row save payload for a repeating item group. */
    @Schema(name = "GroupRowSavePayload")
    public record GroupRowSavePayload(
            String groupOid,
            int rowOrdinal,
            Map<String, Object> values
    ) {}
}
