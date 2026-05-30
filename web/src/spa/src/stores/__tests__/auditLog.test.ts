import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuditLogStore } from '../auditLog'

describe('useAuditLogStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('starts empty', () => {
    const store = useAuditLogStore()
    expect(store.events).toEqual([])
    expect(store.totalCount).toBe(0)
  })

  it('hydrates from the mock loader + exposes filter dimension values', async () => {
    const store = useAuditLogStore()
    await store.load()
    expect(store.totalCount).toBeGreaterThan(0)
    expect(store.actors.length).toBeGreaterThan(0)
    expect(store.subjects.length).toBeGreaterThan(0)
  })

  it('filters by actor', async () => {
    const store = useAuditLogStore()
    await store.load()
    store.actorFilter = 'monitor_demo'
    expect(store.filtered.every((e) => e.actor === 'monitor_demo')).toBe(true)
  })

  it('filters by variant', async () => {
    const store = useAuditLogStore()
    await store.load()
    store.variantFilter = 'reason-for-change'
    expect(store.filtered.every((e) => e.variant === 'reason-for-change')).toBe(true)
  })

  it('filters by subject', async () => {
    const store = useAuditLogStore()
    await store.load()
    store.subjectFilter = 'M-001'
    expect(store.filtered.every((e) => e.subjectId === 'M-001')).toBe(true)
  })

  it('groupedByDate returns descending date buckets containing the filtered events', async () => {
    const store = useAuditLogStore()
    await store.load()
    const groups = store.groupedByDate
    expect(groups.length).toBeGreaterThan(0)

    // Dates are descending.
    for (let i = 1; i < groups.length; i++) {
      expect(groups[i - 1]!.date >= groups[i]!.date).toBe(true)
    }

    // Sum of events across groups equals the filtered total.
    const sum = groups.reduce((acc, g) => acc + g.events.length, 0)
    expect(sum).toBe(store.visibleCount)
  })

  it('clearFilters restores the full visible count', async () => {
    const store = useAuditLogStore()
    await store.load()
    store.actorFilter = 'monitor_demo'
    store.variantFilter = 'sdv'
    store.subjectFilter = 'M-002'
    store.clearFilters()
    expect(store.actorFilter).toBe('')
    expect(store.variantFilter).toBe('all')
    expect(store.subjectFilter).toBe('')
    expect(store.visibleCount).toBe(store.totalCount)
  })
})
