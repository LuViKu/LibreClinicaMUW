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
 * Phase E.6 — top-level body for {@code POST /pages/api/v1/eventCrfs/{id}/items}.
 *
 * <p>Pre-E.6 the controller declared an inline {@code record SaveItemsRequest}
 * carrying only the flat {@code values} map. The crf-data-types cluster
 * surfaces it as a top-level DTO (reviewer flag in the build playbook) so
 * the OpenAPI generator emits a named schema instead of an anonymous
 * inline shape — keeps the SPA's hand-typed copies aligned with the
 * backend record without an extra cast.
 *
 * <ul>
 *   <li>{@code values} — single-row items. Keyed by item OID. Existing
 *       behaviour is unchanged; arrays land for {@code select-multi}
 *       items and the controller comma-joins them at write time.</li>
 *   <li>{@code groups} — repeating-group rows (E.6). Each
 *       {@link GroupRowSavePayload} carries the group OID, the row
 *       ordinal (1-based), and the per-item values for that row.</li>
 * </ul>
 *
 * <p>Both fields are optional: an empty/null {@code groups} list means
 * the SPA only saved top-level items; an empty {@code values} map means
 * the SPA only modified rows inside repeating groups. The controller
 * counts rejected items per field and surfaces a {@code groupRowsSaved}
 * counter in the response.
 */
@Schema(name = "SaveItemsRequest")
public record SaveItemsRequest(
        Map<String, Object> values,
        List<GroupRowSavePayload> groups
) {

    /** Per-row save payload for a repeating item group. */
    @Schema(name = "GroupRowSavePayload")
    public record GroupRowSavePayload(
            String groupOid,
            int rowOrdinal,
            Map<String, Object> values
    ) {}
}
