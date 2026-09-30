import { describe, expect, it } from 'vitest'

import {
  canCloseNote,
  canReopenNote,
  canResolveNote,
  canRespondToNote,
} from '@/types/note'
import type { NoteStatus } from '@/types/note'

/**
 * The note actions mirror the server's NoteTransitionMatrix, whose Monitor
 * rows follow legacy ViewDiscrepancyNoteServlet: a Monitor updates
 * (re-queries, replies) and closes any open thread and re-opens a closed
 * one. Before, the SPA let a Monitor only close a proposed resolution.
 */
describe('note actions for the Monitor', () => {
  const open: NoteStatus[] = ['new', 'updated', 'resolution-proposed']

  it('re-queries or replies to every open thread', () => {
    for (const s of open) expect(canRespondToNote('Monitor', s), s).toBe(true)
  })

  it('closes every open thread', () => {
    for (const s of open) expect(canCloseNote('Monitor', s), s).toBe(true)
  })

  it('re-opens a closed thread', () => {
    expect(canReopenNote('Monitor', 'closed')).toBe(true)
    expect(canRespondToNote('Monitor', 'closed')).toBe(false)
  })

  it('neither proposes a resolution nor acts on a note that is not applicable', () => {
    expect(canResolveNote('Monitor', 'updated')).toBe(false)
    expect(canRespondToNote('Monitor', 'not-applicable')).toBe(false)
    expect(canCloseNote('Monitor', 'not-applicable')).toBe(false)
    expect(canReopenNote('Monitor', 'not-applicable')).toBe(false)
  })
})

describe('note actions for the other roles follow the server as before', () => {
  it('an Investigator replies, then proposes a resolution, and never closes', () => {
    expect(canRespondToNote('Investigator', 'new')).toBe(true)
    expect(canRespondToNote('Investigator', 'updated')).toBe(true)
    expect(canResolveNote('Investigator', 'updated')).toBe(true)
    expect(canCloseNote('Investigator', 'new')).toBe(false)
    expect(canCloseNote('Investigator', 'resolution-proposed')).toBe(false)
    expect(canReopenNote('Investigator', 'closed')).toBe(false)
  })

  it('an Investigator cannot answer a proposed resolution back to Updated', () => {
    // resolution-proposed → updated is the Monitor's, DM's and admin's
    expect(canRespondToNote('Investigator', 'resolution-proposed')).toBe(false)
    expect(canRespondToNote('CRC', 'resolution-proposed')).toBe(false)
  })

  it('the Data Manager and Administrator close a proposed resolution only', () => {
    for (const role of ['Data Manager', 'Administrator'] as const) {
      expect(canCloseNote(role, 'resolution-proposed'), role).toBe(true)
      expect(canCloseNote(role, 'new'), role).toBe(false)
      expect(canCloseNote(role, 'updated'), role).toBe(false)
      expect(canReopenNote(role, 'closed'), role).toBe(false)
    }
  })
})
