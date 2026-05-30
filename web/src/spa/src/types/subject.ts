/**
 * Phase E.5 — Subject + visit-status TypeScript types.
 *
 * Shape follows the Phase E.4 inventory's planned
 * `GET /pages/api/v1/subjects` response. Until the B-category adapter
 * lands (gated on the E.0 dispatcher fix), the SPA consumes mock data
 * with this exact shape from the Pinia store so the view code is
 * already written against the production contract.
 */

export type Gender = 'F' | 'M' | 'O' | 'U'

/** Per-event CRF completion state. */
export type EventStatus =
  | 'not-scheduled'
  | 'scheduled'
  | 'in-progress'
  | 'complete'
  | 'signed'
  | 'locked'

export interface EventCellSnapshot {
  /** OID of the EventDefinition. */
  eventDefinitionOid: string
  /** Short display label, e.g. "V1 Inclusion". */
  label: string
  status: EventStatus
  /** Number of open queries attached to CRFs in this event. */
  openQueries: number
}

export interface Subject {
  /** Study Subject ID — the human-readable identifier (e.g. M-001). */
  id: string
  /** Optional secondary ID (no PHI per Add Subject contract). */
  secondaryId: string | null
  /** Site OID the subject was enrolled at. */
  siteOid: string
  /** Human site name (e.g. München). */
  siteLabel: string
  gender: Gender
  /** Year of birth (optional per study config). */
  yearOfBirth: number | null
  /** Treatment / randomisation group, when present. */
  groupLabel: string | null
  /** Enrolment date as ISO `YYYY-MM-DD`. */
  enrolledOn: string
  /** Per-scheduled-event row of status cells, in the study's planned order. */
  events: EventCellSnapshot[]
  /** Whether the subject has been signed off (one-way regulatory action). */
  signed: boolean
  /** Total open queries across the subject's CRFs. */
  openQueries: number
}
