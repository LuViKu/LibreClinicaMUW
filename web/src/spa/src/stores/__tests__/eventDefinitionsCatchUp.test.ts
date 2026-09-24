import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

/**
 * DR-035 — the store's side of applying a plan to already-filed scans:
 * preview and run are both POSTs to the plan's catch-up sub-resource (the
 * preview to its /preview, so no GET can reach code that starts analyses),
 * and a failure comes back as null with the message on `error`.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
  }
})

// eslint-disable-next-line import/first
import { apiGet, apiPost, ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import { useEventDefinitionsStore } from '../eventDefinitions'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const apiPostMock = apiPost as unknown as ReturnType<typeof vi.fn>

const URL = '/pages/api/v1/studies/S_DEFAULTS1/event-definitions/7/imaging-plan/catch-up'
const COUNTS = { scans: 3, attached: 2, started: 4, failed: 0, dryRun: true, dispatcherAvailable: true }

describe('useEventDefinitionsStore imaging plan catch-up', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiGetMock.mockReset()
    apiPostMock.mockReset()
  })

  it('previews with a POST to /preview, never a GET', async () => {
    apiPostMock.mockResolvedValueOnce(COUNTS)
    const store = useEventDefinitionsStore()
    expect(await store.previewImagingPlanCatchUp('S_DEFAULTS1', 7)).toEqual(COUNTS)
    expect(apiPostMock).toHaveBeenCalledWith(`${URL}/preview`, {})
    expect(apiGetMock).not.toHaveBeenCalled()
  })

  it('runs with a POST', async () => {
    apiPostMock.mockResolvedValueOnce({ ...COUNTS, dryRun: false })
    const store = useEventDefinitionsStore()
    const r = await store.runImagingPlanCatchUp('S_DEFAULTS1', 7)
    expect(r?.dryRun).toBe(false)
    expect(apiPostMock).toHaveBeenCalledWith(URL, {})
  })

  it('reports a refused run as null and surfaces the message', async () => {
    apiPostMock.mockRejectedValueOnce(new ApiError(409, 'locked', { message: 'Study is locked' }))
    const store = useEventDefinitionsStore()
    expect(await store.runImagingPlanCatchUp('S_DEFAULTS1', 7)).toBeNull()
    expect(store.error).toContain('Study is locked')
  })
})
