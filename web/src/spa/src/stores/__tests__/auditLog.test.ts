import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuditLogStore } from '../auditLog'
import type { AuditEvent } from '@/types/audit'

/**
 * Phase E.4 M10 — the previous suite hydrated from the (now-deleted)
 * mock loader. With the apiGet rewire the store has no built-in
 * fixture data, so tests inject `events` directly to keep filter /
 * grouping behaviour covered without standing up a fetch mock.
 */

const FIXTURE: AuditEvent[] = [
  { id: '1', occurredAt: '2026-05-30T13:42:08Z', variant: 'signed',            actor: 'investigator_demo', actorRole: 'Investigator',  title: 'Subject sign-off',           subjectId: 'M-001' },
  { id: '2', occurredAt: '2026-05-30T12:31:55Z', variant: 'reason-for-change', actor: 'investigator_demo', actorRole: 'Investigator',  title: 'Data edit · Reason for change', subjectId: 'M-001', before: 'Severe', after: 'Moderate', reason: 'Re-graded after consult with PI.' },
  { id: '3', occurredAt: '2026-05-30T09:08:14Z', variant: 'sdv',               actor: 'monitor_demo',     actorRole: 'Monitor',       title: 'SDV verified',               subjectId: 'M-002' },
  { id: '4', occurredAt: '2026-05-29T16:11:09Z', variant: 'data',              actor: 'investigator_demo', actorRole: 'Investigator',  title: 'CRF marked complete',        subjectId: 'M-001' },
  { id: '5', occurredAt: '2026-05-29T14:02:33Z', variant: 'query',             actor: 'monitor_demo',     actorRole: 'Monitor',       title: 'Query opened',               subjectId: 'M-001' },
  { id: '6', occurredAt: '2026-05-28T11:00:00Z', variant: 'admin',             actor: 'dm_demo',           actorRole: 'Data Manager',  title: 'New user provisioned' },
]

function hydrate() {
  const store = useAuditLogStore()
  store.events = JSON.parse(JSON.stringify(FIXTURE))
  return store
}

describe('useAuditLogStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('starts empty', () => {
    const store = useAuditLogStore()
    expect(store.events).toEqual([])
    expect(store.totalCount).toBe(0)
  })

  it('exposes filter-dimension values', () => {
    const store = hydrate()
    expect(store.totalCount).toBe(6)
    expect(store.actors.length).toBeGreaterThan(0)
    expect(store.subjects).toEqual(expect.arrayContaining(['M-001', 'M-002']))
  })

  it('filters by actor', () => {
    const store = hydrate()
    store.actorFilter = 'monitor_demo'
    expect(store.filtered.every((e) => e.actor === 'monitor_demo')).toBe(true)
    expect(store.filtered.length).toBe(2)
  })

  it('filters by variant', () => {
    const store = hydrate()
    store.variantFilter = 'reason-for-change'
    expect(store.filtered.every((e) => e.variant === 'reason-for-change')).toBe(true)
    expect(store.filtered.length).toBe(1)
  })

  it('filters by subject', () => {
    const store = hydrate()
    store.subjectFilter = 'M-001'
    expect(store.filtered.every((e) => e.subjectId === 'M-001')).toBe(true)
    expect(store.filtered.length).toBe(4)
  })

  it('groupedByDate returns descending date buckets containing the filtered events', () => {
    const store = hydrate()
    const groups = store.groupedByDate
    expect(groups.length).toBeGreaterThan(0)
    for (let i = 1; i < groups.length; i++) {
      expect(groups[i - 1]!.date >= groups[i]!.date).toBe(true)
    }
    const sum = groups.reduce((acc, g) => acc + g.events.length, 0)
    expect(sum).toBe(store.visibleCount)
  })

  it('clearFilters restores the full visible count', () => {
    const store = hydrate()
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
