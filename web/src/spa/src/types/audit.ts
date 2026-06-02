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
 *
 * Phase E.5 follow-up (2026-06-02, TODO #7): {@link AuditEvent} is
 * derived from the openapi-typescript-generated
 * {@code components['schemas']['AuditEventDto']} so SPA call sites
 * track the backend record shape. The narrow {@link AuditEventVariant}
 * literal union + the {@code actorRole} role chip are kept hand-typed
 * since they're SPA-only pattern matches that the generated record
 * loses (it sees plain {@code string}).
 */

import type { components } from './api'

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

export type AuditEvent =
  Omit<components['schemas']['AuditEventDto'],
       'id' | 'occurredAt' | 'variant' | 'actor' | 'title' | 'actorRole'>
  & {
    id: string
    /** ISO instant. */
    occurredAt: string
    variant: AuditEventVariant
    /** Username of the actor. */
    actor: string
    /** Short title — e.g. "Subject sign-off" / "Data edit · Reason for change". */
    title: string
    /** Optional role chip rendered next to the actor. */
    actorRole?: 'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator'
  }
