/**
 * Phase E.6 — Audit-log event types.
 *
 * Shape follows the planned `GET /pages/api/v1/audit?...` adapter
 * response per api-surface.md row 8.
 *
 * `data-edit` events carry a `before` + `after` value pair for the
 * Reason-for-Change diff card. The legacy audit_event_log row's
 * `audit_log_event_value_old` + `audit_log_event_value_new` columns
 * already capture this, so the C-category addition listed in the
 * inventory is mostly a serialiser change in the controller.
 */

export type AuditEventVariant =
  | 'signed'
  | 'reason-for-change'
  | 'sdv'
  | 'admin'
  | 'data'
  | 'query'
  /**
   * Phase E.5 #2 — subject moved between treatment-arm groups or
   * added to a group for the first time (audit_log_event_type_id
   * 28 + 29). The audit row carries the before/after group labels
   * in `before`/`after`, so the SPA renders a `DiffCard` similar to
   * `reason-for-change` but without the inline reason text.
   */
  | 'subject-group-change'

export interface AuditEvent {
  id: string
  /** ISO instant. */
  occurredAt: string
  variant: AuditEventVariant
  /** Username of the actor. */
  actor: string
  /** Optional role chip rendered next to the actor. */
  actorRole?: 'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator'
  /** Short title — e.g. "Subject sign-off" / "Data edit · Reason for change". */
  title: string
  /** Subject id when scoped to a subject. */
  subjectId?: string
  /** CRF + item OIDs when scoped to a single CRF item. */
  scope?: string
  /** Optional details paragraph rendered as the event body. */
  details?: string
  /** Reason-for-Change before/after values. */
  before?: string
  after?: string
  /** Optional inline reason text (e.g. RFC justification). */
  reason?: string
}
