/**
 * Phase E.6 — Discrepancy-note types.
 *
 * Shape follows the planned `GET /pages/api/v1/discrepancies?...`
 * adapter response per api-surface.md row 7. Per-role powers
 * (Monitor closes, Investigator responds, DM has full visibility)
 * are gated server-side; the SPA enforces the same matrix client-side
 * via `canCloseNote(role, status)` etc. so the buttons match the
 * legacy thread-panel UI.
 */

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

export interface DiscrepancyNote {
  id: string
  type: NoteType
  status: NoteStatus
  subjectId: string
  itemOid: string
  description: string
  /** Assigned user id, or null when nobody is assigned. */
  assignedTo: string | null
  daysOpen: number
  /** ISO instant of the most recent thread entry. */
  lastActivityAt: string
}
