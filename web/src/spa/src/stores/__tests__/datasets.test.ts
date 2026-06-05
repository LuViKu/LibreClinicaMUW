/**
 * Phase E.6 Data Export — Phase 3 (filters) datasets store spec.
 *
 * Pins the {@code testFilter} action's load-bearing behaviour:
 *
 *   - POSTs to {@code /pages/api/v1/datasets/{id}:test-filter} with
 *     the exact {@code { filters }} body the backend's
 *     {@code TestFilterRequest} record expects.
 *   - Debounces rapid successive calls (only the last one fires the
 *     real network probe).
 *   - Cancels in-flight requests when a new call arrives so the
 *     displayed counts never lag the user's edits.
 *
 * The debounce default is 300 ms; tests pass {@code 0} to drive the
 * action synchronously where the timer would otherwise hide bugs.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDatasetsStore } from '../datasets'
import { ApiError } from '@/api/client'
import type { DatasetFilterDto, FilterTestResult } from '@/types/export'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiPost: vi.fn(),
  }
})

import { apiPost } from '@/api/client'

const FILTERS: DatasetFilterDto[] = [
  { itemOid: 'I_AGE', operator: '>=', value: '18' },
]

const RESULT: FilterTestResult = {
  matchingSubjects: 12,
  totalSubjects: 50,
  matchingCrfs: 36,
  totalCrfs: 150,
}

describe('useDatasetsStore.testFilter', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('POSTs to /pages/api/v1/datasets/{id}:test-filter with the filters body', async () => {
    const datasets = useDatasetsStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(RESULT)

    const out = await datasets.testFilter('42', FILTERS, 0)

    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/datasets/42:test-filter',
      { filters: FILTERS },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(out).toEqual(RESULT)
    expect(datasets.preview).toEqual(RESULT)
    expect(datasets.isLoadingPreview).toBe(false)
    expect(datasets.previewError).toBeNull()
  })

  it('debounces — only the latest call hits the network', async () => {
    const datasets = useDatasetsStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue(RESULT)

    vi.useFakeTimers()
    try {
      const p1 = datasets.testFilter('0', [
        { itemOid: 'I_AGE', operator: '=', value: '40' },
      ])
      const p2 = datasets.testFilter('0', [
        { itemOid: 'I_AGE', operator: '=', value: '41' },
      ])
      const p3 = datasets.testFilter('0', FILTERS)

      // The first two should resolve to null (superseded by p3).
      expect(apiPost).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(350)

      const [r1, r2, r3] = await Promise.all([p1, p2, p3])
      expect(r1).toBeNull()
      expect(r2).toBeNull()
      expect(r3).toEqual(RESULT)
      // Only one request — the debounced winner.
      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).toHaveBeenLastCalledWith(
        '/pages/api/v1/datasets/0:test-filter',
        { filters: FILTERS },
        expect.any(Object),
      )
    } finally {
      vi.useRealTimers()
    }
  })

  it('surfaces ApiError into previewError without throwing', async () => {
    const datasets = useDatasetsStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(400, "filters[0].operator 'like' is not supported"),
    )

    const out = await datasets.testFilter('0', FILTERS, 0)

    expect(out).toBeNull()
    expect(datasets.previewError).toContain('not supported')
    expect(datasets.isLoadingPreview).toBe(false)
  })

  it('reset() clears draft + preview state', async () => {
    const datasets = useDatasetsStore()
    datasets.draft.name = 'My export'
    datasets.draft.filters = FILTERS
    datasets.preview = RESULT
    datasets.previewError = 'oops'

    datasets.reset()

    expect(datasets.draft.name).toBe('')
    expect(datasets.draft.filters).toEqual([])
    expect(datasets.preview).toBeNull()
    expect(datasets.previewError).toBeNull()
  })
})
