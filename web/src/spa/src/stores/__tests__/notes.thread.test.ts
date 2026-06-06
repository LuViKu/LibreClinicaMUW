import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useNotesStore } from '../notes'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { DiscrepancyNote, ThreadEntry } from '@/types/note'

/**
 * Phase E.6 discrepancy-full — Vitest coverage for the new
 * loadThread + buildExportUrl + add(type) store actions.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
  }
})

import { apiGet, apiPost } from '@/api/client'

const PARENT: DiscrepancyNote = {
  id: '42',
  type: 'query',
  status: 'updated',
  subjectId: 'M-001',
  itemOid: 'I_AGE',
  description: 'Age looks low',
  assignedTo: null,
  daysOpen: 3,
  lastActivityAt: '2026-06-01T08:00:00Z',
  thread: [],
}

const THREAD: ThreadEntry[] = [
  {
    id: '42',
    status: 'new',
    description: 'Age looks low',
    author: 'monitor_demo',
    createdAt: '2026-06-01T08:00:00Z',
  },
  {
    id: '43',
    status: 'updated',
    description: 'Re-checked source — age is correct.',
    author: 'investigator_demo',
    createdAt: '2026-06-02T09:00:00Z',
  },
]

const HYDRATED_PARENT: DiscrepancyNote = { ...PARENT, thread: THREAD }

describe('useNotesStore.loadThread', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('GETs the thread endpoint and caches the result', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(HYDRATED_PARENT)

    const result = await notes.loadThread('42')

    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/discrepancies/42/thread')
    expect(result).toEqual(HYDRATED_PARENT)
    expect(notes.threadCache['42']).toEqual(THREAD)
  })

  it('replaces the in-memory parent row with the hydrated copy', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(HYDRATED_PARENT)

    await notes.loadThread('42')

    expect(notes.rows[0].thread).toEqual(THREAD)
  })

  it('returns cached entries on second call (no second network hit)', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(HYDRATED_PARENT)

    await notes.loadThread('42')
    await notes.loadThread('42')

    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('surfaces ApiNetworkError via `error` and returns null', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiNetworkError('offline', new Error('ECONNREFUSED')),
    )

    const result = await notes.loadThread('42')

    expect(result).toBeNull()
    expect(notes.error).toMatch(/Backend nicht erreichbar/)
  })

  it('clears thread cache on reset()', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT]
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(HYDRATED_PARENT)

    await notes.loadThread('42')
    notes.reset()

    expect(notes.threadCache).toEqual({})
    expect(notes.loadingThreadId).toBeNull()
  })
})

describe('useNotesStore.buildExportUrl', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('returns the base URL when no narrowable filter is active', () => {
    const notes = useNotesStore()
    notes.statusFilter = 'open'
    expect(notes.buildExportUrl()).toBe('/pages/api/v1/discrepancies/export.csv')
  })

  it('forwards the concrete status filter as a query param', () => {
    const notes = useNotesStore()
    notes.statusFilter = 'closed'
    expect(notes.buildExportUrl()).toBe(
      '/pages/api/v1/discrepancies/export.csv?status=closed',
    )
  })

  it('omits the status param when filter is "all" (server returns everything)', () => {
    const notes = useNotesStore()
    notes.statusFilter = 'all'
    expect(notes.buildExportUrl()).toBe('/pages/api/v1/discrepancies/export.csv')
  })
})

describe('useNotesStore.add — type field', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('defaults type to "query" when not provided', async () => {
    const notes = useNotesStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ ...PARENT })

    await notes.add({
      subjectId: 'M-001',
      itemOid: 'I_AGE',
      description: 'Age looks low',
    })

    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/discrepancies',
      expect.objectContaining({ type: 'query' }),
    )
  })

  it('forwards an explicit type', async () => {
    const notes = useNotesStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ...PARENT,
      type: 'reason-for-change',
    })

    await notes.add({
      subjectId: 'M-001',
      itemOid: 'I_AGE',
      description: 'Investigator typo fix',
      type: 'reason-for-change',
    })

    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/discrepancies',
      expect.objectContaining({ type: 'reason-for-change' }),
    )
  })

  it('surfaces a 403 ApiError without crashing', async () => {
    const notes = useNotesStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(403, 'role-blocked', { message: 'role-blocked' }),
    )

    await expect(
      notes.add({
        subjectId: 'M-001',
        itemOid: 'I_AGE',
        description: 'rfc as monitor',
        type: 'reason-for-change',
      }),
    ).rejects.toBeInstanceOf(ApiError)
  })
})
