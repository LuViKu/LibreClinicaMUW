import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSdvStore } from '../sdv'
import type { SdvRow } from '@/types/sdv'

/**
 * Phase E.4 M9 — the previous test suite hydrated from the
 * (now-deleted) mock loader. With the apiGet rewire the store has no
 * built-in fixture data, so the tests inject `rows` directly to keep
 * filter / selection / bulk-verify behaviour covered without standing
 * up a fetch mock.
 */

const FIXTURE: SdvRow[] = [
  { eventCrfOid: '1', subjectId: 'M-001', siteLabel: 'München', eventLabel: 'V1', eventStartDate: '2020-10-06', crfName: 'Demographics v1.0', crfLanguage: 'de', status: 'pending',  requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-10-06T15:42:08Z' },
  { eventCrfOid: '2', subjectId: 'M-002', siteLabel: 'München', eventLabel: 'V1', eventStartDate: '2020-10-09', crfName: 'Demographics v1.0', crfLanguage: 'de', status: 'query',    requirement: 'required-100', openQueries: 1, lastUpdatedAt: '2020-10-09T11:08:14Z' },
  { eventCrfOid: '3', subjectId: 'M-003', siteLabel: 'Wien',    eventLabel: 'V1', eventStartDate: '2020-10-15', crfName: 'Demographics v1.0', crfLanguage: 'de', status: 'locked',   requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-11-20T08:12:00Z' },
  { eventCrfOid: '4', subjectId: 'M-004', siteLabel: 'Wien',    eventLabel: 'V1', eventStartDate: '2020-11-02', crfName: 'Demographics v1.0', crfLanguage: 'de', status: 'pending',  requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-11-02T16:30:11Z' },
  { eventCrfOid: '5', subjectId: 'M-005', siteLabel: 'München', eventLabel: 'V2', eventStartDate: '2020-12-12', crfName: 'Vitals v1.0',       crfLanguage: 'de', status: 'pending',  requirement: 'not-required', openQueries: 0, lastUpdatedAt: '2020-12-12T10:01:00Z' },
  { eventCrfOid: '6', subjectId: 'M-005', siteLabel: 'München', eventLabel: 'V2', eventStartDate: '2020-12-12', crfName: 'Adverse Events v1', crfLanguage: 'de', status: 'verified', requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-12-15T14:00:00Z' },
]

function hydrate() {
  const store = useSdvStore()
  store.rows = JSON.parse(JSON.stringify(FIXTURE))
  return store
}

describe('useSdvStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('starts empty + not loading', () => {
    const store = useSdvStore()
    expect(store.rows).toEqual([])
    expect(store.totalCount).toBe(0)
    expect(store.selectedCount).toBe(0)
  })

  it('filters by free text against subject id / event / CRF name', () => {
    const store = hydrate()
    store.query = 'Demographics'
    expect(store.filtered.length).toBe(4)
    expect(store.filtered.every((r) => r.crfName.includes('Demographics'))).toBe(true)
  })

  it('filters by status', () => {
    const store = hydrate()
    store.statusFilter = 'query'
    expect(store.filtered.every((r) => r.status === 'query')).toBe(true)
    expect(store.filtered.length).toBe(1)
  })

  it('filters by requirement', () => {
    const store = hydrate()
    store.requirementFilter = 'not-required'
    expect(store.filtered.every((r) => r.requirement === 'not-required')).toBe(true)
  })

  it('only-with-queries hides 0-query rows', () => {
    const store = hydrate()
    store.onlyWithQueries = true
    expect(store.filtered.every((r) => r.openQueries > 0)).toBe(true)
  })

  it('only allows selecting rows that are pending', () => {
    const store = hydrate()
    const verifiedRow = store.rows.find((r) => r.status === 'verified')!
    const pendingRow = store.rows.find((r) => r.status === 'pending')!
    store.toggle(verifiedRow.eventCrfOid)
    expect(store.selected.has(verifiedRow.eventCrfOid)).toBe(false)
    store.toggle(pendingRow.eventCrfOid)
    expect(store.selected.has(pendingRow.eventCrfOid)).toBe(true)
  })

  it('toggleAllInView selects every pending row in the current filter', () => {
    const store = hydrate()
    store.toggleAllInView()
    expect(store.selectedCount).toBe(store.verifiableCount)
    expect(store.allVerifiableSelected).toBe(true)
    store.toggleAllInView()
    expect(store.selectedCount).toBe(0)
  })

  it('clearFilters resets every filter dimension', () => {
    const store = hydrate()
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

  it('verifySelected optimistically flips verified rows + reports rejections', async () => {
    // Stub fetch — the store uses apiPost which goes through window.fetch.
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({
        verified: ['1', '4'],
        rejected: ['5'],
        verifiedCount: 2,
        verifiedAt: '2026-06-01T12:00:00Z',
        verifiedBy: 'tester',
      }),
      text: async () => '',
    } as Response)
    vi.stubGlobal('fetch', fetchMock)
    try {
      const store = hydrate()
      store.toggle('1')
      store.toggle('4')
      store.toggle('5')
      const flipped = await store.verifySelected()
      expect(flipped).toBe(2)
      expect(store.rows.find((r) => r.eventCrfOid === '1')!.status).toBe('verified')
      expect(store.rows.find((r) => r.eventCrfOid === '4')!.status).toBe('verified')
      expect(store.rows.find((r) => r.eventCrfOid === '5')!.status).toBe('pending')
      expect(store.error).toContain('1 Eintrag')
      expect(store.selectedCount).toBe(0)
      expect(fetchMock).toHaveBeenCalledOnce()
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
