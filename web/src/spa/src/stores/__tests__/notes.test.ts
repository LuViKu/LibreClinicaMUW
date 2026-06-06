import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useNotesStore } from '../notes'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { DiscrepancyNote } from '@/types/note'

/**
 * Phase E A1 — Vitest coverage for the notes store's `appendThread`
 * action. The existing M7 `load` + `add` paths are exercised
 * indirectly via NotesDiscrepanciesView under the existing E.4 IT
 * coverage; this suite focuses on the new thread-entry behaviour.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
  }
})

import { apiPost } from '@/api/client'

const PARENT: DiscrepancyNote = {
  id: '42',
  type: 'query',
  status: 'new',
  subjectId: 'M-001',
  itemOid: 'I_AGE',
  description: 'Age looks low',
  assignedTo: null,
  daysOpen: 3,
  lastActivityAt: '2026-06-01T08:00:00Z',
}

const REFRESHED_PARENT: DiscrepancyNote = {
  ...PARENT,
  status: 'updated',
  lastActivityAt: '2026-06-02T09:00:00Z',
}

describe('useNotesStore.appendThread', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('POSTs to /pages/api/v1/discrepancies/{parentId}/thread with the right body', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(REFRESHED_PARENT)

    const result = await notes.appendThread(PARENT.id, {
      newStatus: 'updated',
      description: 'Confirmed via source',
    })

    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/discrepancies/42/thread',
      {
        newStatus: 'updated',
        description: 'Confirmed via source',
        assignedTo: null,
      },
    )
    expect(result).toEqual(REFRESHED_PARENT)
  })

  it('replaces the in-memory parent row with the refreshed copy', async () => {
    const notes = useNotesStore()
    const other: DiscrepancyNote = { ...PARENT, id: '99' }
    notes.rows = [other, PARENT]
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(REFRESHED_PARENT)

    await notes.appendThread(PARENT.id, { newStatus: 'updated', description: 'Reply' })

    expect(notes.rows).toHaveLength(2)
    expect(notes.rows.find((n) => n.id === PARENT.id)).toEqual(REFRESHED_PARENT)
    // The unrelated row stays untouched.
    expect(notes.rows.find((n) => n.id === '99')).toEqual(other)
  })

  it('surfaces a network failure via `error` and returns null', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiNetworkError('offline', new Error('ECONNREFUSED')),
    )

    const result = await notes.appendThread(PARENT.id, {
      newStatus: 'updated',
      description: 'Reply',
    })

    expect(result).toBeNull()
    expect(notes.error).toMatch(/Backend nicht erreichbar/)
    // Row stays at its original status.
    expect(notes.rows[0].status).toBe('new')
  })

  it('surfaces the server error message via `error` on 400 responses', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(400, 'Bad request',
        { message: 'Illegal status transition: new → closed' }),
    )

    const result = await notes.appendThread(PARENT.id, {
      newStatus: 'closed',
    })

    expect(result).toBeNull()
    expect(notes.error).toBe('Illegal status transition: new → closed')
  })

  it('rethrows 403 so the caller can navigate back to login if needed', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    const forbidden = new ApiError(403, 'Forbidden',
      { message: 'Your role does not permit this transition' })
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(forbidden)

    await expect(
      notes.appendThread(PARENT.id, { newStatus: 'closed' }),
    ).rejects.toBe(forbidden)
    expect(notes.error).toBe('Your role does not permit this transition')
  })

  it('isSubmitting flips during the call and back on completion', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    let resolveCall!: (n: DiscrepancyNote) => void
    ;(apiPost as ReturnType<typeof vi.fn>).mockReturnValueOnce(
      new Promise<DiscrepancyNote>((res) => { resolveCall = res }),
    )

    const pending = notes.appendThread(PARENT.id, {
      newStatus: 'updated',
      description: 'Reply',
    })

    expect(notes.isSubmitting).toBe(true)
    resolveCall(REFRESHED_PARENT)
    await pending
    expect(notes.isSubmitting).toBe(false)
  })
})

/**
 * Phase E.6 DN — `add` (createNote) action must forward the optional
 * eventCrfOid so the backend can pin the note to the correct repeating
 * event ordinal. When the caller omits it the field is sent as `null`
 * and the backend falls back to its unscoped item-data lookup (M7
 * behaviour).
 */
describe('useNotesStore.add — eventCrfOid forwarding', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  const CREATED: DiscrepancyNote = {
    id: '101',
    type: 'query',
    status: 'new',
    subjectId: 'M-001',
    itemOid: 'I_AGE',
    description: 'Please confirm',
    assignedTo: null,
    daysOpen: 0,
    lastActivityAt: '2026-06-06T10:00:00Z',
  }

  it('forwards eventCrfOid in the POST body when supplied', async () => {
    const notes = useNotesStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(CREATED)

    await notes.add({
      subjectId: 'M-001',
      itemOid: 'I_AGE',
      eventCrfOid: 'EC-7',
      description: 'Please confirm',
    })

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/discrepancies', {
      subjectId: 'M-001',
      itemOid: 'I_AGE',
      eventCrfOid: 'EC-7',
      description: 'Please confirm',
      assignedTo: null,
      type: 'query',
    })
  })

  it('sends eventCrfOid as null when the caller omits it', async () => {
    const notes = useNotesStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(CREATED)

    await notes.add({
      subjectId: 'M-001',
      itemOid: 'I_AGE',
      description: 'Please confirm',
    })

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/discrepancies', {
      subjectId: 'M-001',
      itemOid: 'I_AGE',
      eventCrfOid: null,
      description: 'Please confirm',
      assignedTo: null,
      type: 'query',
    })
  })
})
