/**
 * Phase E.6 — Discrepancy-note types.
 *
 * Shape follows the planned `GET /pages/api/v1/discrepancies?...`
 * adapter response per api-surface.md row 7. Per-role powers
 * (Monitor closes, Investigator responds, DM has full visibility)
 * are gated server-side; the SPA enforces the same matrix client-side
 * via `canCloseNote(role, status)` etc. so the buttons match the
 * legacy thread-panel UI.
 *
 * Phase E.5 follow-up (2026-06-02, TODO #7): {@link DiscrepancyNote}
 * is derived from the openapi-typescript-generated
 * {@code components['schemas']['DiscrepancyNoteDto']} so SPA call
 * sites track the backend record shape. Narrow {@link NoteType} /
 * {@link NoteStatus} literal unions stay hand-typed.
 */

import type { components } from './api'

export type NoteType =
  | 'query'
  | 'failed-validation'
  | 'annotation'
  | 'reason-for-change'

export type NoteStatus =
  | 'new'
  | 'updated'
  | 'resolution-proposed'
  | 'closed'
  | 'not-applicable'

export type DiscrepancyNote =
  Omit<Required<components['schemas']['DiscrepancyNoteDto']>, 'type' | 'status' | 'assignedTo'>
  & {
    type: NoteType
    status: NoteStatus
    /** Assigned user id, or null when nobody is assigned. */
    assignedTo: string | null
  }

/* ------------------------------------------------------------------ */
/* Phase E A1 — role-aware transition helpers.                        */
/*                                                                    */
/* Source-of-truth lives backend-side in NoteTransitionMatrix.java.   */
/* These helpers mirror the same matrix so the SPA hides buttons the  */
/* backend would 403 — defence in depth, not the only check.          */
/* ------------------------------------------------------------------ */

import type { UserRole } from './auth'

/**
 * The user can append a reply to (or restart) a query that's awaiting
 * Investigator/CRC attention. Used to render the "Respond" button on
 * `new` / `updated` / `resolution-proposed` notes.
 */
export function canRespondToNote(role: UserRole, status: NoteStatus): boolean {
  if (role === 'Monitor') return false
  if (status === 'closed' || status === 'not-applicable') return false
  return (
    role === 'Investigator' ||
    role === 'CRC' ||
    role === 'Data Manager' ||
    role === 'Administrator'
  )
}

/**
 * The user can mark a query as resolution-proposed. Investigator + CRC
 * roles propose resolution from an `updated` note (the parent must
 * have at least one Investigator response first).
 */
export function canResolveNote(role: UserRole, status: NoteStatus): boolean {
  return (role === 'Investigator' || role === 'CRC') && status === 'updated'
}

/**
 * The user can close a `resolution-proposed` note. Closing is the
 * Monitor's prerogative (or DM/Admin override) — Investigators
 * cannot close their own resolutions, per GCP separation of
 * concerns.
 */
export function canCloseNote(role: UserRole, status: NoteStatus): boolean {
  return (
    (role === 'Monitor' || role === 'Data Manager' || role === 'Administrator') &&
    status === 'resolution-proposed'
  )
}
