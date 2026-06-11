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
 * Phase E.4 M7 — wire-shape for {@code GET /pages/api/v1/discrepancies}
 * and the response body of {@code POST /pages/api/v1/discrepancies}.
 *
 * <p>Mirrors the Vue SPA's {@code DiscrepancyNote} TS interface in
 * {@code web/src/spa/src/types/note.ts} byte-for-byte.
 *
 * <p>Phase E.6 {@code discrepancy-full}: extended with the {@code thread}
 * field — a sibling endpoint
 * ({@code GET /api/v1/discrepancies/{parentId}/thread}) populates it
 * with the parent + every child note in insertion order so the SPA can
 * draw the legacy thread panel without a per-row drilldown. The list
 * endpoint continues to return {@code thread=[]} for backwards
 * compatibility; the SPA only populates this field when the user
 * expands a row.
 *
 * <p>Notes-deeplink (2026-06-11): extended with four context fields the
 * operator needs to triage a query without leaving the list — the human
 * label for the item, the current value of the data point, the event
 * the value belongs to, and the {@code event_crf} id the SPA needs to
 * deep-link straight to the right CRF row. All four are empty / null
 * when the note isn't tied to an {@code itemData} entity or the walk
 * fails (e.g. orphaned note rows from a deleted CRF version).
 *
 * @param id              discrepancy_note_id as a string
 * @param type            one of: {@code query | failed-validation |
 *                        annotation | reason-for-change}
 * @param status          one of: {@code new | updated |
 *                        resolution-proposed | closed | not-applicable}
 * @param subjectId       StudySubject.label resolved via the entity_id
 *                        walk (item_data → event_crf → study_subject);
 *                        empty string when the note's entity_id is 0
 *                        or unresolvable
 * @param itemOid         Item.oid resolved via item_data → item; empty
 *                        string when unresolvable
 * @param description     free-text body
 * @param assignedTo      username of the assignee, or {@code null}
 * @param daysOpen        days since the note was created (computed by
 *                        the SQL query, surfaced via {@code days})
 * @param lastActivityAt  ISO-8601 of the most recent thread entry; for
 *                        parent-level notes without a thread this is
 *                        the {@code date_created} timestamp
 * @param thread          ordered child notes (Phase E.6); empty list
 *                        when caller hit the list endpoint or no
 *                        children exist
 * @param itemLabel       human-readable item label
 *                        ({@code item.description}, falling back to
 *                        {@code item.name} / oid); null when the note
 *                        isn't attached to an item_data row
 * @param itemValue       current {@code item_data.value} at the bound
 *                        (item, event_crf) tuple; null when unset
 * @param eventCrfOid     {@code event_crf.id} as a string — the SPA
 *                        router consumes this as
 *                        {@code /event-crfs/&lt;eventCrfOid&gt;}; null
 *                        when unresolvable
 * @param eventName       {@code study_event_definition.name} (e.g.
 *                        "V1 Inclusion"); null when unresolvable
 */
@Schema(name = "DiscrepancyNoteDto")
public record DiscrepancyNoteDto(
        String id,
        String type,
        String status,
        String subjectId,
        String itemOid,
        String description,
        String assignedTo,
        int daysOpen,
        String lastActivityAt,
        List<DiscrepancyThreadEntryDto> thread,
        String itemLabel,
        String itemValue,
        String eventCrfOid,
        String eventName
) {
    /** Convenience constructor — defaults {@code thread} to an empty list
     *  AND nulls the deeplink context fields. */
    public DiscrepancyNoteDto(
            String id,
            String type,
            String status,
            String subjectId,
            String itemOid,
            String description,
            String assignedTo,
            int daysOpen,
            String lastActivityAt) {
        this(id, type, status, subjectId, itemOid, description,
                assignedTo, daysOpen, lastActivityAt, List.of(),
                null, null, null, null);
    }

    /** Backward-compat 10-arg ctor for callers that already pass a thread
     *  but predate the notes-deeplink fields. */
    public DiscrepancyNoteDto(
            String id,
            String type,
            String status,
            String subjectId,
            String itemOid,
            String description,
            String assignedTo,
            int daysOpen,
            String lastActivityAt,
            List<DiscrepancyThreadEntryDto> thread) {
        this(id, type, status, subjectId, itemOid, description,
                assignedTo, daysOpen, lastActivityAt, thread,
                null, null, null, null);
    }
}
