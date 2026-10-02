import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn() }
})

// eslint-disable-next-line import/first
import { apiPost } from '@/api/client'
// eslint-disable-next-line import/first
import { useSdvStore } from '../sdv'
// eslint-disable-next-line import/first
import type { SdvRow } from '@/types/sdv'

/**
 * SDV by subject, as legacy's "View By Study Subject ID": per subject the
 * complete CRFs, the verified ones, the ones held back by a query, and a
 * verify-the-subject action. Legacy verifies a subject's complete CRFs that
 * require SDV; a CRF with an open query stays behind, as it does row by row.
 */

function row(oid: string, subjectId: string, status: SdvRow['status'],
             requirement: SdvRow['requirement'] = 'required-100'): SdvRow {
  return {
    eventCrfOid: oid, subjectId, siteLabel: 'Default Study', eventLabel: 'V1',
    eventStartDate: '2020-10-06', crfName: 'Demographics', crfLanguage: 'en',
    status, requirement, openQueries: status === 'query' ? 1 : 0,
    lastUpdatedAt: '2020-10-06T15:42:08Z',
  }
}

const ROWS: SdvRow[] = [
  row('1', 'M-001', 'pending'),
  row('2', 'M-001', 'pending', 'required-partial'),
  row('3', 'M-001', 'query'),
  row('4', 'M-001', 'verified'),
  row('5', 'M-001', 'pending', 'not-required'),
  row('6', 'M-002', 'verified'),
]

describe('useSdvStore — by subject', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiPost).mockReset()
  })

  it('sums each subject: complete, verified, held back by a query, and what verifying the subject verifies', () => {
    const store = useSdvStore()
    store.rows = ROWS.map((r) => ({ ...r }))

    expect(store.subjects).toEqual([
      { subjectId: 'M-001', siteLabel: 'Default Study', complete: 5, verified: 1, withQueries: 1, verifiable: ['1', '2'] },
      { subjectId: 'M-002', siteLabel: 'Default Study', complete: 1, verified: 1, withQueries: 0, verifiable: [] },
    ])
  })

  it('narrows the subjects by the search box', () => {
    const store = useSdvStore()
    store.rows = ROWS.map((r) => ({ ...r }))
    store.query = 'm-002'
    expect(store.subjects.map((s) => s.subjectId)).toEqual(['M-002'])
  })

  it('verifies a subject: posts exactly the verifiable CRFs and marks them verified', async () => {
    const store = useSdvStore()
    store.rows = ROWS.map((r) => ({ ...r }))
    vi.mocked(apiPost).mockResolvedValueOnce({
      verified: ['1', '2'], rejected: [], verifiedCount: 2,
      verifiedAt: '2026-09-30T10:00:00Z', verifiedBy: 'manual_monitor',
    })

    const count = await store.verifySubject('M-001')

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/sdv/verify', { eventCrfOids: ['1', '2'], verified: true })
    expect(count).toBe(2)
    expect(store.rows.filter((r) => r.status === 'verified').map((r) => r.eventCrfOid)).toEqual(['1', '2', '4', '6'])
    expect(store.subjects[0].verifiable).toEqual([])
  })

  it('verifies nothing for a subject without a verifiable CRF', async () => {
    const store = useSdvStore()
    store.rows = ROWS.map((r) => ({ ...r }))
    expect(await store.verifySubject('M-002')).toBe(0)
    expect(apiPost).not.toHaveBeenCalled()
  })
})
