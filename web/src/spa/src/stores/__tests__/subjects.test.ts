import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  AddSubjectValidationError,
  useSubjectsStore,
  validateAddSubject,
  type AddSubjectInput,
} from '../subjects'

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
  })

  it('starts empty and not loading', () => {
    const store = useSubjectsStore()
    expect(store.rows).toEqual([])
    expect(store.isLoading).toBe(false)
    expect(store.totalCount).toBe(0)
    expect(store.visibleCount).toBe(0)
  })

  it('hydrates from the mock loader', async () => {
    const store = useSubjectsStore()
    await store.load()
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
  beforeEach(() => setActivePinia(createPinia()))

  it('appends the subject when validation passes', async () => {
    const store = useSubjectsStore()
    await store.load()
    const before = store.totalCount
    const subject = await store.add(baseInput)
    expect(subject.id).toBe('M-101')
    expect(store.totalCount).toBe(before + 1)
    expect(store.rows.at(-1)?.id).toBe('M-101')
  })

  it('seeds the new subject with not-scheduled events in the planned order', async () => {
    const store = useSubjectsStore()
    await store.load()
    const expectedLabels = store.rows[0]!.events.map((e) => e.label)
    const subject = await store.add(baseInput)
    expect(subject.events.map((e) => e.label)).toEqual(expectedLabels)
    expect(subject.events.every((e) => e.status === 'not-scheduled')).toBe(true)
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
