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
