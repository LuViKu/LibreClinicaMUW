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

/**
 * Per-CRF data-entry stage taxonomy surfaced on the detail view.
 *
 * Maps from the legacy `event_crf.completion_status_id` via the M3
 * SubjectsApiController; see `SubjectDetailDto.EventCellDetailDto`
 * JavaDoc for the full mapping table.
 *
 * `null` means the event hasn't started yet (no `event_crf` row).
 */
export type DataEntryStage =
  | 'not-started'
  | 'data-being-entered'
  | 'initial-data-entry-completed'
  | 'validation-completed'
  | 'locked'

/**
 * Detail-view extension of {@link EventCellSnapshot} — adds the
 * scheduling + completion metadata the matrix doesn't carry.
 *
 * Populated by `GET /pages/api/v1/subjects/{oid}` (M3 adapter).
 */
export interface EventCellDetail extends EventCellSnapshot {
  /** Event start date as ISO `YYYY-MM-DD`, or null if unscheduled. */
  dateStart: string | null
  /** Event end date as ISO `YYYY-MM-DD`, often null for in-flight events. */
  dateEnd: string | null
  /** Free-text location captured at scheduling time, when present. */
  location: string | null
  /** Data-entry stage of the primary CRF, or null if not started. */
  dataEntryStage: DataEntryStage | null
}

/**
 * Subject Detail — superset of {@link Subject} for the dedicated
 * single-subject view. Populated by `GET /pages/api/v1/subjects/{oid}`
 * (M3 adapter). The matrix list endpoint does not return these
 * fields; the detail view fetches them separately.
 */
export interface SubjectDetail extends Omit<Subject, 'events'> {
  /** Active study OID — rendered in the breadcrumb in place of siteOid. */
  studyOid: string
  /** Human-readable study name — rendered in the breadcrumb. */
  studyName: string
  /** Richer per-event metadata (dateStart, location, dataEntryStage). */
  events: EventCellDetail[]
}
