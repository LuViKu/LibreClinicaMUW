import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  AddSubjectValidationError,
  useSubjectsStore,
  validateAddSubject,
  type AddSubjectInput,
} from '../subjects'
import type { Subject } from '@/types/subject'

// Mock the http client so the store tests run without a live backend.
// We mock `apiGet` to return a fixture that mirrors the seed-data subjects
// (M-001..M-007) — same shape the real adapter emits, same per-event
// status taxonomy, same open-query counts. Keeping it as a top-level
// fixture lets every test get a predictable, ordered baseline.
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
  }
})

import { apiGet } from '@/api/client'

const FIXTURE_SUBJECTS: Subject[] = [
  {
    id: 'M-001', secondaryId: null, siteOid: 'TDS0004', siteLabel: 'München',
    gender: 'F', yearOfBirth: 1962, groupLabel: null, enrolledOn: '2020-10-06',
    signed: false, openQueries: 2,
    events: [
      { eventDefinitionOid: 'SE_V1_INCLUSION', label: 'V1 Inclusion', status: 'complete', openQueries: 1 },
      { eventDefinitionOid: 'SE_V2_DAY30',     label: 'V2 Day 30',    status: 'complete', openQueries: 1 },
      { eventDefinitionOid: 'SE_V3_DAY90',     label: 'V3 Day 90',    status: 'in-progress', openQueries: 0 },
    ],
  },
  {
    id: 'M-002', secondaryId: null, siteOid: 'TDS0004', siteLabel: 'München',
    gender: 'M', yearOfBirth: 1955, groupLabel: null, enrolledOn: '2020-10-09',
    signed: false, openQueries: 2,
    events: [
      { eventDefinitionOid: 'SE_V1_INCLUSION', label: 'V1 Inclusion', status: 'complete', openQueries: 0 },
      { eventDefinitionOid: 'SE_V2_DAY30',     label: 'V2 Day 30',    status: 'in-progress', openQueries: 2 },
      { eventDefinitionOid: 'SE_V3_DAY90',     label: 'V3 Day 90',    status: 'not-scheduled', openQueries: 0 },
    ],
  },
  {
    id: 'M-003', secondaryId: null, siteOid: 'TDS0004', siteLabel: 'München',
    gender: 'F', yearOfBirth: 1949, groupLabel: null, enrolledOn: '2020-10-15',
    signed: true, openQueries: 0,
    events: [
      { eventDefinitionOid: 'SE_V1_INCLUSION', label: 'V1 Inclusion', status: 'complete', openQueries: 0 },
      { eventDefinitionOid: 'SE_V2_DAY30',     label: 'V2 Day 30',    status: 'complete', openQueries: 0 },
      { eventDefinitionOid: 'SE_V3_DAY90',     label: 'V3 Day 90',    status: 'complete', openQueries: 0 },
    ],
  },
  {
    id: 'M-004', secondaryId: '04-MUW', siteOid: 'TDS0004', siteLabel: 'München',
    gender: 'M', yearOfBirth: 1971, groupLabel: null, enrolledOn: '2020-11-02',
    signed: false, openQueries: 3,
    events: [
      { eventDefinitionOid: 'SE_V1_INCLUSION', label: 'V1 Inclusion', status: 'in-progress', openQueries: 3 },
      { eventDefinitionOid: 'SE_V2_DAY30',     label: 'V2 Day 30',    status: 'not-scheduled', openQueries: 0 },
      { eventDefinitionOid: 'SE_V3_DAY90',     label: 'V3 Day 90',    status: 'not-scheduled', openQueries: 0 },
    ],
  },
  {
    id: 'M-005', secondaryId: null, siteOid: 'TDS0004', siteLabel: 'München',
    gender: 'F', yearOfBirth: 1980, groupLabel: null, enrolledOn: '2020-11-12',
    signed: false, openQueries: 0,
    events: [
      { eventDefinitionOid: 'SE_V1_INCLUSION', label: 'V1 Inclusion', status: 'complete', openQueries: 0 },
      { eventDefinitionOid: 'SE_V2_DAY30',     label: 'V2 Day 30',    status: 'complete', openQueries: 0 },
      { eventDefinitionOid: 'SE_V3_DAY90',     label: 'V3 Day 90',    status: 'scheduled', openQueries: 0 },
    ],
  },
  {
    id: 'M-006', secondaryId: null, siteOid: 'TDS0004', siteLabel: 'München',
    gender: 'M', yearOfBirth: 1958, groupLabel: null, enrolledOn: '2020-12-01',
    signed: true, openQueries: 0,
    events: [
      { eventDefinitionOid: 'SE_V1_INCLUSION', label: 'V1 Inclusion', status: 'signed', openQueries: 0 },
      { eventDefinitionOid: 'SE_V2_DAY30',     label: 'V2 Day 30',    status: 'signed', openQueries: 0 },
      { eventDefinitionOid: 'SE_V3_DAY90',     label: 'V3 Day 90',    status: 'signed', openQueries: 0 },
    ],
  },
  {
    id: 'M-007', secondaryId: null, siteOid: 'TDS0004', siteLabel: 'München',
    gender: 'F', yearOfBirth: 1972, groupLabel: null, enrolledOn: '2021-01-15',
    signed: false, openQueries: 1,
    events: [
      { eventDefinitionOid: 'SE_V1_INCLUSION', label: 'V1 Inclusion', status: 'complete', openQueries: 0 },
      { eventDefinitionOid: 'SE_V2_DAY30',     label: 'V2 Day 30',    status: 'in-progress', openQueries: 1 },
      { eventDefinitionOid: 'SE_V3_DAY90',     label: 'V3 Day 90',    status: 'not-scheduled', openQueries: 0 },
    ],
  },
]

const baseInput: AddSubjectInput = {
  id: 'M-101',
  secondaryId: null,
  siteOid: 'TDS0004',
  siteLabel: 'München',
  gender: 'F',
  yearOfBirth: 1990,
  groupLabel: 'Arm A',
  enrolledOn: '2026-05-30',
}

describe('useSubjectsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    // Reset the mock between tests so per-test overrides don't leak.
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiGet).mockResolvedValue(FIXTURE_SUBJECTS)
  })

  it('starts empty and not loading', () => {
    const store = useSubjectsStore()
    expect(store.rows).toEqual([])
    expect(store.isLoading).toBe(false)
    expect(store.totalCount).toBe(0)
    expect(store.visibleCount).toBe(0)
  })

  it('hydrates from the /pages/api/v1/subjects adapter', async () => {
    const store = useSubjectsStore()
    await store.load()
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/subjects')
    expect(store.totalCount).toBeGreaterThan(0)
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('exposes per-event status cells in the same order across all rows', async () => {
    const store = useSubjectsStore()
    await store.load()
    const labels = store.rows[0]!.events.map((e) => e.label)
    for (const subject of store.rows) {
      expect(subject.events.map((e) => e.label)).toEqual(labels)
    }
  })

  it('filters by free-text query against id', async () => {
    const store = useSubjectsStore()
    await store.load()
    store.query = 'M-001'
    expect(store.filtered).toHaveLength(1)
    expect(store.filtered[0]!.id).toBe('M-001')
  })

  it('filters by free-text query against secondaryId', async () => {
    const store = useSubjectsStore()
    await store.load()
    store.query = '04-MUW'
    expect(store.filtered).toHaveLength(1)
    expect(store.filtered[0]!.id).toBe('M-004')
  })

  it('hides subjects without open queries when onlyWithQueries is true', async () => {
    const store = useSubjectsStore()
    await store.load()
    store.onlyWithQueries = true
    for (const subject of store.filtered) {
      expect(subject.openQueries).toBeGreaterThan(0)
    }
    expect(store.filtered.every((s) => s.openQueries > 0)).toBe(true)
  })

  it('shows only signed subjects when statusFilter is "signed"', async () => {
    const store = useSubjectsStore()
    await store.load()
    store.statusFilter = 'signed'
    for (const subject of store.filtered) {
      expect(subject.signed).toBe(true)
    }
  })

  it('shows only subjects with at least one not-complete event when statusFilter is "open-events"', async () => {
    const store = useSubjectsStore()
    await store.load()
    store.statusFilter = 'open-events'
    for (const subject of store.filtered) {
      const hasOpen = subject.events.some(
        (e) => e.status === 'scheduled' || e.status === 'in-progress' || e.status === 'not-scheduled',
      )
      expect(hasOpen).toBe(true)
    }
  })

  it('clearFilters resets query + statusFilter + onlyWithQueries', async () => {
    const store = useSubjectsStore()
    await store.load()
    store.query = 'M-001'
    store.statusFilter = 'signed'
    store.onlyWithQueries = true
    store.clearFilters()
    expect(store.query).toBe('')
    expect(store.statusFilter).toBe('all')
    expect(store.onlyWithQueries).toBe(false)
    expect(store.filtered).toHaveLength(store.rows.length)
  })

  it('hard-fails (no fallback) when the backend returns an error', async () => {
    // No two-source-of-truth fallback: when the adapter fails the store
    // surfaces an error and leaves rows empty, per plan §"Mock removal
    // policy".
    const { ApiError } = await import('@/api/client')
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(400, 'No active study bound'))
    const store = useSubjectsStore()
    await store.load()
    expect(store.rows).toEqual([])
    expect(store.error).toBe('No active study bound')
  })

  it('surfaces a friendly message when the backend is unreachable', async () => {
    const { ApiNetworkError } = await import('@/api/client')
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiNetworkError('refused'))
    const store = useSubjectsStore()
    await store.load()
    expect(store.rows).toEqual([])
    expect(store.error).toMatch(/Backend nicht erreichbar/)
  })
})

describe('validateAddSubject', () => {
  it('returns no errors for a valid input', () => {
    const errors = validateAddSubject(baseInput, [], { today: '2026-05-30' })
    expect(errors).toEqual([])
  })

  it('flags a missing subject id', () => {
    const errors = validateAddSubject({ ...baseInput, id: '   ' }, [], { today: '2026-05-30' })
    expect(errors).toContainEqual(expect.objectContaining({ field: 'id' }))
  })

  it('flags duplicate subject id case-insensitively', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiGet).mockResolvedValue(FIXTURE_SUBJECTS)
    const store = useSubjectsStore()
    await store.load()
    const existingId = store.rows[0]!.id // e.g. 'M-001'
    const errors = validateAddSubject({ ...baseInput, id: existingId.toLowerCase() }, store.rows, { today: '2026-05-30' })
    expect(errors).toContainEqual(expect.objectContaining({ field: 'id' }))
  })

  it('flags an 8+ digit run in the secondary id (PHI risk)', () => {
    const errors = validateAddSubject({ ...baseInput, secondaryId: 'BORN19720115' }, [], { today: '2026-05-30' })
    expect(errors).toContainEqual(expect.objectContaining({ field: 'secondaryId' }))
  })

  it('flags a future enrolment date', () => {
    const errors = validateAddSubject({ ...baseInput, enrolledOn: '2026-12-31' }, [], { today: '2026-05-30' })
    expect(errors).toContainEqual(expect.objectContaining({ field: 'enrolledOn' }))
  })

  it('flags missing gender', () => {
    const errors = validateAddSubject({ ...baseInput, gender: '' as 'F' }, [], { today: '2026-05-30' })
    expect(errors).toContainEqual(expect.objectContaining({ field: 'gender' }))
  })

  it('flags an out-of-range year of birth', () => {
    const tooOld = validateAddSubject({ ...baseInput, yearOfBirth: 1799 }, [], { today: '2026-05-30' })
    const future = validateAddSubject({ ...baseInput, yearOfBirth: 2099 }, [], { today: '2026-05-30' })
    expect(tooOld).toContainEqual(expect.objectContaining({ field: 'yearOfBirth' }))
    expect(future).toContainEqual(expect.objectContaining({ field: 'yearOfBirth' }))
  })
})

describe('useSubjectsStore — add()', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiGet).mockResolvedValue(FIXTURE_SUBJECTS)
  })

  it('appends the subject when validation passes', async () => {
    const store = useSubjectsStore()
    await store.load()
    const before = store.totalCount
    const subject = await store.add(baseInput)
    expect(subject.id).toBe('M-101')
    expect(store.totalCount).toBe(before + 1)
    expect(store.rows.at(-1)?.id).toBe('M-101')
  })

  it('seeds the new subject with no scheduled events (matrix shows empty row)', async () => {
    // After M2, Add Subject only creates the study_subject row — visits
    // are scheduled separately. The new row's events column is empty
    // until the user schedules a visit (legacy /AddNewSubject parity).
    const store = useSubjectsStore()
    await store.load()
    const subject = await store.add(baseInput)
    expect(subject.events).toEqual([])
    expect(subject.signed).toBe(false)
    expect(subject.openQueries).toBe(0)
  })

  it('throws AddSubjectValidationError on a duplicate id and does NOT append', async () => {
    const store = useSubjectsStore()
    await store.load()
    const existingId = store.rows[0]!.id
    const before = store.totalCount
    await expect(store.add({ ...baseInput, id: existingId })).rejects.toBeInstanceOf(AddSubjectValidationError)
    expect(store.totalCount).toBe(before)
  })
})
