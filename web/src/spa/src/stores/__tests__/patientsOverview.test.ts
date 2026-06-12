/**
 * Phase E.6 — patientsOverview store spec.
 *
 * Pins:
 *  - loadList builds the right URL (page + pageSize + search) and stores
 *    the server-paginated envelope on the store.
 *  - loadDetail keys on subjectId, hits the per-id detail endpoint.
 *  - loadSeries keys on (subjectId, modalityCode, eye), caches per key,
 *    and the second call is a cache hit (no extra apiGet invocation).
 *  - resetDetail clears detail-only state without touching the list.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
  }
})

import { apiGet } from '@/api/client'
import { usePatientsOverviewStore } from '../patientsOverview'
import type { MeasurementSeries, PatientDetail, PatientsListResponse } from '@/types/patient'

const LIST_RESPONSE: PatientsListResponse = {
  totalCount: 123,
  page: 0,
  pageSize: 50,
  patients: [
    {
      subjectId: 101,
      uniqueIdentifier: 'PERSON-001',
      gender: 'F',
      yearOfBirth: 1962,
      enrolments: [
        { studyOid: 'S_GA', studyName: 'GA Cohort', label: 'M-001', studyEye: 'OU', enrolledOn: '2024-01-15', lastVisitAt: '2024-09-01T10:00:00Z' },
      ],
    },
    {
      subjectId: 102,
      uniqueIdentifier: 'PERSON-002',
      gender: 'M',
      yearOfBirth: 1971,
      enrolments: [
        { studyOid: 'S_IAMD', studyName: 'iAMD', label: 'M-007', studyEye: 'OD', enrolledOn: '2024-02-01', lastVisitAt: null },
      ],
    },
  ],
}

const DETAIL_101: PatientDetail = {
  ...LIST_RESPONSE.patients[0],
  eyeTransitions: [
    {
      transitionId: 1,
      eye: 'OD',
      eventAt: '2025-03-01T09:00:00Z',
      fromStudyOid: 'S_IAMD',
      fromStudyName: 'iAMD',
      fromLabel: 'M-007',
      toStudyOid: 'S_GA',
      toStudyName: 'GA Cohort',
      toLabel: 'M-001',
      reason: 'Progression',
    },
  ],
}

const SERIES_VA_OD: MeasurementSeries = {
  modalityCode: 'va',
  dataType: 'numeric',
  unit: 'logMAR',
  series: [
    { date: '2024-01-15', value: '0.10', numericValue: 0.10, studyOid: 'S_IAMD', studyName: 'iAMD', eventCrfId: 1, eventName: 'V1' },
    { date: '2024-07-15', value: '0.20', numericValue: 0.20, studyOid: 'S_GA', studyName: 'GA Cohort', eventCrfId: 2, eventName: 'V2' },
  ],
}

describe('usePatientsOverviewStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('starts with an empty list + zero count', () => {
    const store = usePatientsOverviewStore()
    expect(store.list).toEqual([])
    expect(store.totalCount).toBe(0)
    expect(store.page).toBe(0)
    expect(store.pageSize).toBe(50)
  })

  it('loadList calls the patients endpoint with page + pageSize + search params', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(LIST_RESPONSE)
    const store = usePatientsOverviewStore()
    await store.loadList(0, 50, 'Meier')
    expect(apiGet).toHaveBeenCalledTimes(1)
    const url = (apiGet as ReturnType<typeof vi.fn>).mock.calls[0][0] as string
    expect(url).toContain('/pages/api/v1/patients?')
    expect(url).toContain('page=0')
    expect(url).toContain('pageSize=50')
    expect(url).toContain('search=Meier')
  })

  it('loadList stores the paginated envelope on the store', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(LIST_RESPONSE)
    const store = usePatientsOverviewStore()
    await store.loadList(0, 50, '')
    expect(store.list).toHaveLength(2)
    expect(store.totalCount).toBe(123)
    expect(store.page).toBe(0)
    expect(store.pageSize).toBe(50)
    expect(store.search).toBe('')
  })

  it('loadList omits search when empty', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(LIST_RESPONSE)
    const store = usePatientsOverviewStore()
    await store.loadList(2, 50, '')
    const url = (apiGet as ReturnType<typeof vi.fn>).mock.calls[0][0] as string
    expect(url).toContain('page=2')
    expect(url).not.toContain('search=')
  })

  it('loadDetail hits the per-id endpoint and stores the detail', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(DETAIL_101)
    const store = usePatientsOverviewStore()
    await store.loadDetail(101)
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/patients/101')
    expect(store.detail).toEqual(DETAIL_101)
  })

  it('loadSeries hits /measurements with modalityCode + eye and caches per key', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue(SERIES_VA_OD)
    const store = usePatientsOverviewStore()
    const a = await store.loadSeries(101, 'va', 'OD')
    expect(apiGet).toHaveBeenCalledTimes(1)
    const url = (apiGet as ReturnType<typeof vi.fn>).mock.calls[0][0] as string
    expect(url).toContain('/pages/api/v1/patients/101/measurements?')
    expect(url).toContain('modalityCode=va')
    expect(url).toContain('eye=OD')
    expect(a).toEqual(SERIES_VA_OD)
    expect(store.seriesByKey.get('101|va|OD')).toEqual(SERIES_VA_OD)
  })

  it('loadSeries returns the cached series on the second call (no extra apiGet)', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(SERIES_VA_OD)
    const store = usePatientsOverviewStore()
    await store.loadSeries(101, 'va', 'OD')
    expect(apiGet).toHaveBeenCalledTimes(1)
    await store.loadSeries(101, 'va', 'OD')
    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('series cache differentiates by eye (OD vs OS triggers a second fetch)', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue(SERIES_VA_OD)
    const store = usePatientsOverviewStore()
    await store.loadSeries(101, 'va', 'OD')
    await store.loadSeries(101, 'va', 'OS')
    expect(apiGet).toHaveBeenCalledTimes(2)
  })

  it('loadList sets listError on 5xx and clears the list', async () => {
    class StubApiError extends Error {
      isUnauthorized = false
      isForbidden = false
      constructor(public status: number, public override message: string) { super(message) }
    }
    const { ApiError } = await vi.importActual<typeof import('@/api/client')>('@/api/client')
    void StubApiError
    ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new ApiError(500, 'boom'))
    const store = usePatientsOverviewStore()
    await store.loadList(0, 50, '')
    expect(store.list).toEqual([])
    expect(store.listError).toBe('boom')
  })

  it('resetDetail clears detail + detail error but leaves the list intact', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(LIST_RESPONSE)
    const store = usePatientsOverviewStore()
    await store.loadList(0, 50, '')
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(DETAIL_101)
    await store.loadDetail(101)
    expect(store.detail).not.toBeNull()
    store.resetDetail()
    expect(store.detail).toBeNull()
    expect(store.list).toHaveLength(2)
  })

  it('reset wipes everything including modalities + series cache', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(LIST_RESPONSE)
    const store = usePatientsOverviewStore()
    await store.loadList(0, 50, '')
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(SERIES_VA_OD)
    await store.loadSeries(101, 'va', 'OD')
    expect(store.list).toHaveLength(2)
    expect(store.seriesByKey.size).toBe(1)
    store.reset()
    expect(store.list).toEqual([])
    expect(store.totalCount).toBe(0)
    expect(store.seriesByKey.size).toBe(0)
  })
})
