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
 * Phase E.6 crf-entry-advanced — per-item discrepancy roll-up for the
 * CRF entry view.
 *
 * <p>Returned by {@code GET /pages/api/v1/eventCrfs/{id}/notes}. The
 * SPA wires {@code byItemOid} into the {@code ItemNoteIndicator}
 * component so each item label shows a chip when at least one parent
 * note is attached.
 *
 * @param eventCrfOid path param echoed back
 * @param totalCount  total parent notes attached to this CRF
 * @param openCount   subset of {@code totalCount} whose
 *                    {@code resolutionStatus} is {@code new} /
 *                    {@code updated} / {@code resolution-proposed}
 * @param byItemOid   item OID → summary of the freshest parent note
 *                    on that item. Items without notes are omitted.
 */
@Schema(name = "EventCrfNotesRollupDto")
public record EventCrfNotesRollupDto(
        String eventCrfOid,
        int totalCount,
        int openCount,
        Map<String, ItemNoteSummary> byItemOid
) {

    /**
     * One row per item that has at least one parent note. The SPA's
     * popover lazily loads the full thread via the existing
     * discrepancy endpoints; this roll-up only carries enough to
     * render the badge + tooltip.
     */
    @Schema(name = "ItemNoteSummary")
    public record ItemNoteSummary(
            int totalCount,
            int openCount,
            /** Aggregated status — {@code open} if any open, else
             *  {@code resolved}. */
            String status,
            /** Latest-activity instant across all notes on this item. */
            String lastActivityAt,
            /** Note ids attached to this item — drives the popover
             *  fetch when the user clicks the indicator. */
            List<String> noteIds
    ) {}
}
