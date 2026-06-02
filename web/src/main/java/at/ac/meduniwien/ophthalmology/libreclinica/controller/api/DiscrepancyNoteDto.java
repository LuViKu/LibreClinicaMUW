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

/**
 * Phase E.4 M7 — wire-shape for {@code GET /pages/api/v1/discrepancies}
 * and the response body of {@code POST /pages/api/v1/discrepancies}.
 *
 * <p>Mirrors the Vue SPA's {@code DiscrepancyNote} TS interface in
 * {@code web/src/spa/src/types/note.ts} byte-for-byte.
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
        String lastActivityAt
) {}
