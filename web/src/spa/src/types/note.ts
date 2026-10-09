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
 * Phase E.5 follow-up (2026-06-02): {@link DiscrepancyNote}
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

/**
 * Phase E.6 {@code discrepancy-full} — single thread-entry shape.
 * Mirrors the backend's {@link DiscrepancyThreadEntryDto}.
 */
export interface ThreadEntry {
  id: string
  status: NoteStatus
  description: string
  /** Username of the note owner; empty string when owner was deleted. */
  author: string
  /** ISO-8601 of date_created. */
  createdAt: string
}

/** What a note is on, as the server names it (DiscrepancyNoteDto.entityType). */
export type NoteEntityType = 'itemData' | 'subject' | 'studySub' | 'studyEvent' | 'eventCrf'

/**
 * A field a note can be on instead of item data, as legacy notes them:
 * the subject's sex, date of birth, person ID; the enrolment date; a
 * visit's location, start and end; a CRF's interview date and interviewer.
 */
export interface NoteField {
  entityType: Exclude<NoteEntityType, 'itemData'>
  /** gender, date_of_birth, unique_identifier, enrollment_date, location, start_date, … */
  column: string
  /** The visit, for a studyEvent field. */
  eventId?: string
  /** The CRF, for an eventCrf field. */
  eventCrfOid?: string
}

export type DiscrepancyNote =
  Omit<Required<components['schemas']['DiscrepancyNoteDto']>,
    'type' | 'status' | 'assignedTo' | 'thread'
    | 'itemLabel' | 'itemValue' | 'eventCrfOid' | 'eventName'
    | 'entityType' | 'column' | 'entityId'>
  & {
    /*
     * What the note is on. Typed here until types/api.ts is regenerated
     * from the spec that carries them; optional because notes built in the
     * SPA before a round trip do not know them.
     */
    entityType?: NoteEntityType | null
    /** The field for a field note; 'value' for item data. */
    column?: string | null
    /** Id of the row the note is on (subject, study subject, visit, event CRF, item data). */
    entityId?: string | null
    type: NoteType
    status: NoteStatus
    /** Assigned user id, or null when nobody is assigned. */
    assignedTo: string | null
    /**
     * Parent + child thread entries. Empty when the row came from the
     * list endpoint; populated after a successful {@code loadThread}.
     */
    thread: ThreadEntry[]
    /**
     * notes-deeplink (2026-06-11) — context the row needs to render
     * "what value is this query about" without leaving the list. Null
     * when the note isn't anchored to an item_data row (or the walk
     * fails on orphaned legacy data).
     */
    itemLabel: string | null
    itemValue: string | null
    eventCrfOid: string | null
    eventName: string | null
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
 * May the role move a note from `from` to `to`? A port of
 * `NoteTransitionMatrix.check`, whose table is authoritative. SPA roles
 * stand for legacy ones: Investigator for investigator (ra and ra2 too),
 * CRC for coordinator, Data Manager for director, Administrator for admin.
 *
 * The Monitor's rows follow legacy `ViewDiscrepancyNoteServlet`: a Monitor
 * may update (re-query, reply, reassign) and close any open thread, and
 * re-open a closed one. The Data Manager and Administrator re-open a closed
 * thread too, as legacy stores their reply to one as Updated.
 */
export function canTransitionNote(role: UserRole, from: NoteStatus, to: NoteStatus): boolean {
  const investigator = role === 'Investigator' || role === 'CRC'
  const manager = role === 'Data Manager' || role === 'Administrator'
  const monitor = role === 'Monitor'
  // Same status: only another reply in an Updated thread.
  if (from === to) return from === 'updated'
  switch (from) {
    case 'new':
      if (to === 'updated') return investigator || manager || monitor
      if (to === 'not-applicable') return manager
      if (to === 'closed') return monitor
      return false
    case 'updated':
      if (to === 'resolution-proposed') return investigator
      if (to === 'not-applicable') return manager
      if (to === 'closed') return monitor
      return false
    case 'resolution-proposed':
      return (to === 'closed' || to === 'updated') && (monitor || manager)
    case 'closed':
      return to === 'updated' && (monitor || manager)
    default:
      return false
  }
}

/**
 * The user can answer an open thread and leave it Updated: a reply, or,
 * for the Monitor, a re-query. Renders "Respond".
 */
export function canRespondToNote(role: UserRole, status: NoteStatus): boolean {
  return status !== 'closed' && canTransitionNote(role, status, 'updated')
}

/** The user can re-open a closed thread (back to Updated). Renders "Re-open". */
export function canReopenNote(role: UserRole, status: NoteStatus): boolean {
  return status === 'closed' && canTransitionNote(role, status, 'updated')
}

/**
 * The user can mark a query as resolution-proposed. Investigator + CRC
 * roles propose resolution from an `updated` note (the parent must
 * have at least one Investigator response first).
 */
export function canResolveNote(role: UserRole, status: NoteStatus): boolean {
  return canTransitionNote(role, status, 'resolution-proposed')
}

/**
 * The user can close the note. A Monitor may close any open thread; the
 * Data Manager and Administrator close a proposed resolution. The
 * Investigator never closes their own resolution.
 */
export function canCloseNote(role: UserRole, status: NoteStatus): boolean {
  return canTransitionNote(role, status, 'closed')
}

/**
 * Phase E.6 {@code discrepancy-full} — role gate for the type field on
 * the NEW parent-note dialog. Mirrors
 * {@code NoteTransitionMatrix.canCreateType} on the backend so the
 * SPA can hide the {@code reason-for-change} option for non-DM/Admin
 * roles before they 403.
 */
export function canCreateNoteType(role: UserRole, type: NoteType): boolean {
  if (type === 'reason-for-change') {
    return role === 'Data Manager' || role === 'Administrator'
  }
  return true
}
