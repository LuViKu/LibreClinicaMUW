/**
 * Phase E.5 — Subject + visit-status TypeScript types.
 *
 * Shape follows the Phase E.4 inventory's planned
 * `GET /pages/api/v1/subjects` response. Until the B-category adapter
 * lands (gated on the E.0 dispatcher fix), the SPA consumes mock data
 * with this exact shape from the Pinia store so the view code is
 * already written against the production contract.
 *
 * Phase E.5 follow-up (2026-06-02, TODO #7): the wire-level types
 * ({@link Subject}, {@link SubjectDetail}, {@link EventCellSnapshot},
 * {@link EventCellDetail}) are now derived from the
 * openapi-typescript-generated {@code components.schemas} so they
 * stay aligned with the backend record shape. Narrow literal-union
 * enums ({@link Gender}, {@link EventStatus}, {@link DataEntryStage})
 * stay hand-typed and are intersected in to keep the SPA's
 * pattern-matching call sites happy (the generated record loses the
 * union narrowing — it sees these as plain {@code string}).
 */

import type { components } from './api'

export type Gender = 'F' | 'M' | 'O' | 'U'

/** Per-event CRF completion state. */
export type EventStatus =
  | 'not-scheduled'
  | 'scheduled'
  | 'in-progress'
  | 'complete'
  | 'signed'
  | 'locked'

export type EventCellSnapshot =
  Omit<Required<components['schemas']['EventCellDto']>, 'status'>
  & { status: EventStatus }

export type Subject =
  Omit<Required<components['schemas']['SubjectListItemDto']>, 'gender' | 'secondaryId' | 'yearOfBirth' | 'groupLabel' | 'events'>
  & {
    gender: Gender
    secondaryId: string | null
    yearOfBirth: number | null
    groupLabel: string | null
    events: EventCellSnapshot[]
  }

/* ------------------------------------------------------------------ */
/* Phase E A3 — subject lifecycle role helper.                        */
/*                                                                    */
/* Mirrors SubjectLifecycleAuthorization.java backend-side:           */
/*   permitted: Data Manager, Administrator                           */
/*   forbidden: Investigator, CRC, Monitor, RA, RA2                   */
/*                                                                    */
/* No state guard — the existing GET endpoints exclude DELETED rows   */
/* from the matrix, so SubjectDetailView only ever sees AVAILABLE     */
/* subjects. Restore lives backend-side for now (a "show removed"     */
/* filter is a separate slice).                                       */
/* ------------------------------------------------------------------ */

import type { UserRole } from './auth'

export function canManageSubjectLifecycle(role: UserRole): boolean {
  return role === 'Data Manager' || role === 'Administrator'
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
export type EventCellDetail =
  Omit<Required<components['schemas']['EventCellDetailDto']>, 'status' | 'dateStart' | 'dateEnd' | 'location' | 'dataEntryStage'>
  & {
    status: EventStatus
    dateStart: string | null
    dateEnd: string | null
    location: string | null
    dataEntryStage: DataEntryStage | null
  }

/**
 * Subject Detail — superset of {@link Subject} for the dedicated
 * single-subject view. Populated by `GET /pages/api/v1/subjects/{oid}`
 * (M3 adapter). The matrix list endpoint does not return these
 * fields; the detail view fetches them separately.
 */
export type SubjectDetail =
  Omit<Required<components['schemas']['SubjectDetailDto']>, 'gender' | 'secondaryId' | 'yearOfBirth' | 'groupLabel' | 'events'>
  & {
    gender: Gender
    secondaryId: string | null
    yearOfBirth: number | null
    groupLabel: string | null
    events: EventCellDetail[]
  }

/**
 * Phase E.4 M3 + M8 — sign-preflight wire types.
 *
 * Mirrors the backend `SignPreflightDto` byte-for-byte. The five
 * `id`-keyed checks correspond to the regulatory rules the M3
 * controller computes from `study_event` / `event_crf` /
 * `discrepancy_note` state plus the current user's role:
 *
 *  - `events-complete`     — all scheduled events have data
 *  - `crfs-complete`       — all required CRFs marked complete
 *  - `open-queries`        — warn-only, never blocks
 *  - `subject-not-signed`  — subject hasn't been signed yet
 *  - `user-role-can-sign`  — user is Investigator or Study Director
 *
 * `status` is `'pass'` / `'warn'` / `'fail'`. The M8 view collapses
 * these to `'pass'` / `'warn'` / `'blocker'` for the
 * {@link ConfirmationWithPreflight} primitive — see
 * `SignSubjectView.vue` for the mapping.
 */
export type PreflightCheck =
  Omit<Required<components['schemas']['CheckRow']>, 'id' | 'status'>
  & {
    id:
      | 'events-complete'
      | 'crfs-complete'
      | 'open-queries'
      | 'subject-not-signed'
      | 'user-role-can-sign'
    status: 'pass' | 'warn' | 'fail'
  }

export type SignPreflight =
  Omit<Required<components['schemas']['SignPreflightDto']>, 'checks'>
  & { checks: PreflightCheck[] }
