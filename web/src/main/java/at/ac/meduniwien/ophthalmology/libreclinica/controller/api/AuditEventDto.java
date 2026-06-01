/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Phase E.4 M10 — wire-shape for {@code GET /pages/api/v1/audit}.
 *
 * <p>Mirrors the Vue SPA's {@code AuditEvent} TS interface in
 * {@code web/src/spa/src/types/audit.ts} byte-for-byte. Optional
 * fields are serialised only when non-null so the JSON stays small.
 *
 * @param id            audit_id as a string
 * @param occurredAt    ISO-8601 instant of {@code audit_date}
 * @param variant       one of: {@code signed | reason-for-change |
 *                      sdv | admin | data | query}
 * @param actor         user_name of {@code user_id}; falls back to
 *                      {@code "system"} when null
 * @param actorRole     coarse role chip — best-effort, often null
 * @param title         human-readable event type from
 *                      {@code audit_log_event_type.name}
 * @param subjectId     StudySubject.label when the row resolves to a
 *                      single subject; null otherwise
 * @param scope         item OID when the row scopes to an item;
 *                      "event_crf:&lt;id&gt;" when CRF-scoped;
 *                      null otherwise
 * @param details       extra one-liner derived per audit type
 * @param before        legacy {@code old_value} — null when blank
 * @param after         legacy {@code new_value} — null when blank
 * @param reason        legacy {@code reason_for_change} — null when blank
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEventDto(
        String id,
        String occurredAt,
        String variant,
        String actor,
        String actorRole,
        String title,
        String subjectId,
        String scope,
        String details,
        String before,
        String after,
        String reason
) {}
