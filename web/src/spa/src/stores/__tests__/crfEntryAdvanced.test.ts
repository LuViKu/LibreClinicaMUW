import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCrfEntryAdvancedStore } from '../crfEntryAdvanced'
import type { LockProbe, NotesRollup, SectionStatus } from '@/types/crf'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
  }
})

// eslint-disable-next-line import/first
import { apiGet, apiPost } from '@/api/client'

const SECTION_STATUSES: SectionStatus[] = [
  { sectionOid: 'S_IDENT',  title: 'Identification', requiredCount: 2, filledCount: 1, errorCount: 0, openQueries: 1 },
  { sectionOid: 'S_VITALS', title: 'Vitals',         requiredCount: 2, filledCount: 0, errorCount: 0, openQueries: 0 },
]

const LOCK_PROBE_OTHER: LockProbe = {
  eventCrfOid: '42',
  sameUser: false,
  lastEditorName: 'alice',
  lastSeenAt: '2026-06-05T10:00:00Z',
  ttlSeconds: 60,
}

const LOCK_PROBE_SELF: LockProbe = {
  eventCrfOid: '42',
  sameUser: true,
  lastEditorName: 'bob',
  lastSeenAt: '2026-06-05T10:00:30Z',
  ttlSeconds: 60,
}

const NOTES_ROLLUP: NotesRollup = {
  eventCrfOid: '42',
  totalCount: 3,
  openCount: 2,
  byItemOid: {
    I_CONSENT_DATE: { totalCount: 2, openCount: 1, status: 'open', lastActivityAt: '2026-06-05T09:00:00Z', noteIds: ['11', '12'] },
    I_HEIGHT_CM:    { totalCount: 1, openCount: 1, status: 'open', lastActivityAt: '2026-06-05T09:30:00Z', noteIds: ['13'] },
  },
}

function routeGet(): void {
  vi.mocked(apiGet).mockImplementation(async (path) => {
    if (path.endsWith('/section-status')) return structuredClone(SECTION_STATUSES) as never
    if (path.endsWith('/lock-status'))   return structuredClone(LOCK_PROBE_OTHER) as never
    if (path.endsWith('/notes'))         return structuredClone(NOTES_ROLLUP) as never
    throw new Error('unexpected GET ' + path)
  })
}

describe('useCrfEntryAdvancedStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiPost).mockImplementation(async () => structuredClone(LOCK_PROBE_SELF) as never)
    routeGet()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('loadSectionStatuses populates sectionStatuses + sectionStatusByOid', async () => {
    const s = useCrfEntryAdvancedStore()
    await s.loadSectionStatuses('42')
    expect(s.sectionStatuses).toHaveLength(2)
    expect(s.sectionStatusByOid.S_IDENT?.openQueries).toBe(1)
    expect(s.sectionStatusByOid.S_VITALS?.requiredCount).toBe(2)
  })

  it('loadLockProbe populates lockProbe + concurrentEditorActive', async () => {
    const s = useCrfEntryAdvancedStore()
    await s.loadLockProbe('42')
    expect(s.lockProbe?.sameUser).toBe(false)
    expect(s.concurrentEditorActive).toBe(true)
  })

  it('concurrentEditorActive is false when probe is same-user', async () => {
    vi.mocked(apiGet).mockImplementation(async () => structuredClone(LOCK_PROBE_SELF) as never)
    const s = useCrfEntryAdvancedStore()
    await s.loadLockProbe('42')
    expect(s.concurrentEditorActive).toBe(false)
  })

  it('loadNotesRollup populates notesRollup + noteSummaryByItemOid', async () => {
    const s = useCrfEntryAdvancedStore()
    await s.loadNotesRollup('42')
    expect(s.notesRollup?.totalCount).toBe(3)
    expect(s.noteSummaryByItemOid.I_CONSENT_DATE?.openCount).toBe(1)
    expect(s.noteSummaryByItemOid.I_CONSENT_DATE?.noteIds).toEqual(['11', '12'])
  })

  it('loadAll triggers all three GETs', async () => {
    const s = useCrfEntryAdvancedStore()
    await s.loadAll('42')
    expect(s.sectionStatuses).toHaveLength(2)
    expect(s.lockProbe).not.toBeNull()
    expect(s.notesRollup).not.toBeNull()
    expect(apiGet).toHaveBeenCalledTimes(3)
  })

  it('captures errors per probe without coupling them', async () => {
    vi.mocked(apiGet).mockImplementation(async (path) => {
      if (path.endsWith('/section-status')) throw new Error('boom-statuses')
      if (path.endsWith('/lock-status'))   return structuredClone(LOCK_PROBE_OTHER) as never
      if (path.endsWith('/notes'))         throw new Error('boom-notes')
      return [] as never
    })
    const s = useCrfEntryAdvancedStore()
    await s.loadAll('42')
    expect(s.sectionStatuses).toEqual([])
    expect(s.sectionStatusesError).toMatch(/boom-statuses/)
    expect(s.lockProbe).not.toBeNull()
    expect(s.lockProbeError).toBeNull()
    expect(s.notesRollup).toBeNull()
    expect(s.notesRollupError).toMatch(/boom-notes/)
  })

  it('startHeartbeat fires an immediate POST + schedules periodic POSTs', async () => {
    vi.useFakeTimers()
    const s = useCrfEntryAdvancedStore()
    await s.loadLockProbe('42') // sets ttlSeconds = 60 → interval = 30s
    expect(apiPost).not.toHaveBeenCalled()
    s.startHeartbeat('42')
    // Immediate fire is scheduled via void; flush microtasks.
    await Promise.resolve()
    expect(apiPost).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(apiPost).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(apiPost).toHaveBeenCalledTimes(3)
    s.stopHeartbeat()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(apiPost).toHaveBeenCalledTimes(3)
  })

  it('startHeartbeat is idempotent for the same eventCrfOid', async () => {
    vi.useFakeTimers()
    const s = useCrfEntryAdvancedStore()
    s.startHeartbeat('42')
    s.startHeartbeat('42')
    await Promise.resolve()
    // Two start() calls — but the second is short-circuited.
    expect(apiPost).toHaveBeenCalledTimes(1)
    s.stopHeartbeat()
  })

  it('startHeartbeat replaces the timer when switching eventCrfOid', async () => {
    vi.useFakeTimers()
    const s = useCrfEntryAdvancedStore()
    s.startHeartbeat('42')
    await Promise.resolve()
    s.startHeartbeat('43')
    await Promise.resolve()
    expect(apiPost).toHaveBeenCalledTimes(2)
    s.stopHeartbeat()
  })

  it('heartbeat failures are silent', async () => {
    vi.useFakeTimers()
    vi.mocked(apiPost).mockRejectedValue(new Error('network'))
    const s = useCrfEntryAdvancedStore()
    s.startHeartbeat('42')
    await Promise.resolve()
    // Pre-existing lockProbe stays as-is; no thrown error from start.
    expect(s.lockProbeError).toBeNull()
    s.stopHeartbeat()
  })

  it('reset clears all state + stops heartbeat', async () => {
    const s = useCrfEntryAdvancedStore()
    await s.loadAll('42')
    s.startHeartbeat('42')
    s.reset()
    expect(s.sectionStatuses).toEqual([])
    expect(s.lockProbe).toBeNull()
    expect(s.notesRollup).toBeNull()
  })

  it('sectionStatusByOid is a stable map', async () => {
    const s = useCrfEntryAdvancedStore()
    await s.loadSectionStatuses('42')
    expect(Object.keys(s.sectionStatusByOid)).toEqual(['S_IDENT', 'S_VITALS'])
  })

  it('noteSummaryByItemOid empty when no rollup', () => {
    const s = useCrfEntryAdvancedStore()
    expect(s.noteSummaryByItemOid).toEqual({})
  })
})
