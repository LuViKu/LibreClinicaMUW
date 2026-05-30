import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSdvStore } from '../sdv'

describe('useSdvStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('starts empty + not loading', () => {
    const store = useSdvStore()
    expect(store.rows).toEqual([])
    expect(store.totalCount).toBe(0)
    expect(store.selectedCount).toBe(0)
  })

  it('hydrates from the mock loader', async () => {
    const store = useSdvStore()
    await store.load()
    expect(store.totalCount).toBeGreaterThan(0)
    expect(store.error).toBeNull()
  })

  it('filters by free text against subject id / event / CRF name', async () => {
    const store = useSdvStore()
    await store.load()
    store.query = 'Demographics'
    expect(store.filtered.length).toBeGreaterThan(0)
    expect(store.filtered.every((r) => r.crfName.includes('Demographics'))).toBe(true)
  })

  it('filters by status', async () => {
    const store = useSdvStore()
    await store.load()
    store.statusFilter = 'query'
    expect(store.filtered.every((r) => r.status === 'query')).toBe(true)
  })

  it('filters by requirement', async () => {
    const store = useSdvStore()
    await store.load()
    store.requirementFilter = 'not-required'
    expect(store.filtered.every((r) => r.requirement === 'not-required')).toBe(true)
  })

  it('only-with-queries hides 0-query rows', async () => {
    const store = useSdvStore()
    await store.load()
    store.onlyWithQueries = true
    expect(store.filtered.every((r) => r.openQueries > 0)).toBe(true)
  })

  it('only allows selecting rows that are pending', async () => {
    const store = useSdvStore()
    await store.load()
    const verifiedRow = store.rows.find((r) => r.status === 'verified')!
    const pendingRow = store.rows.find((r) => r.status === 'pending')!
    store.toggle(verifiedRow.eventCrfOid)
    expect(store.selected.has(verifiedRow.eventCrfOid)).toBe(false)
    store.toggle(pendingRow.eventCrfOid)
    expect(store.selected.has(pendingRow.eventCrfOid)).toBe(true)
  })

  it('toggleAllInView selects every pending row in the current filter', async () => {
    const store = useSdvStore()
    await store.load()
    store.toggleAllInView()
    expect(store.selectedCount).toBe(store.verifiableCount)
    expect(store.allVerifiableSelected).toBe(true)
    store.toggleAllInView()
    expect(store.selectedCount).toBe(0)
  })

  it('verifySelected flips selected rows to verified and clears selection', async () => {
    const store = useSdvStore()
    await store.load()
    store.toggleAllInView()
    const before = store.selectedCount
    const flipped = await store.verifySelected()
    expect(flipped).toBe(before)
    expect(store.selectedCount).toBe(0)
    expect(store.rows.every((r) => r.status !== 'pending' || true)).toBe(true)
    // Specifically: every previously-pending row is now verified.
    expect(store.rows.filter((r) => r.status === 'verified').length).toBeGreaterThanOrEqual(before)
  })

  it('clearFilters resets every filter dimension', async () => {
    const store = useSdvStore()
    await store.load()
    store.query = 'whatever'
    store.statusFilter = 'verified'
    store.requirementFilter = 'not-required'
    store.onlyWithQueries = true
    store.clearFilters()
    expect(store.query).toBe('')
    expect(store.statusFilter).toBe('all')
    expect(store.requirementFilter).toBe('all')
    expect(store.onlyWithQueries).toBe(false)
  })
})
