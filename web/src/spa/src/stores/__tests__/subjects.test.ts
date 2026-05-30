import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSubjectsStore } from '../subjects'

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
