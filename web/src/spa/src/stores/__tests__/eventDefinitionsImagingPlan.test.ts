import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

/**
 * DR-034 — the store's side of the visit imaging plan: the URLs, the cache
 * per event definition, and that a failed save reports false and leaves the
 * cache alone.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPut: vi.fn(),
  }
})

// eslint-disable-next-line import/first
import { apiGet, apiPut, ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import { useEventDefinitionsStore } from '../eventDefinitions'
// eslint-disable-next-line import/first
import type { ImagingPlanEntry } from '@/types/eventDefinition'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const apiPutMock = apiPut as unknown as ReturnType<typeof vi.fn>

const STUDY = 'S_DEFAULTS1'
const URL = '/pages/api/v1/studies/S_DEFAULTS1/event-definitions/7/imaging-plan'

const OCT: ImagingPlanEntry = {
  modalityId: 1,
  code: 'OCT',
  labelDe: 'OCT',
  labelEn: 'OCT',
  device: 'spectralis',
  kindsAccepted: 'e2e',
  requirement: 'required',
  laterality: 'OU',
  tasks: ['fluid'],
}

describe('useEventDefinitionsStore imaging plan', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiGetMock.mockReset()
    apiPutMock.mockReset()
  })

  it('loads the plan from the sub-resource and caches it per definition', async () => {
    apiGetMock.mockResolvedValueOnce({ entries: [OCT] })
    const store = useEventDefinitionsStore()
    const entries = await store.loadImagingPlan(STUDY, 7)
    expect(apiGetMock).toHaveBeenCalledWith(URL)
    expect(entries).toEqual([OCT])
    expect(store.imagingPlanByDef[7]).toEqual([OCT])
  })

  it('treats a malformed body as an empty plan', async () => {
    apiGetMock.mockResolvedValueOnce({})
    const store = useEventDefinitionsStore()
    expect(await store.loadImagingPlan(STUDY, 7)).toEqual([])
  })

  it('PUTs the write shape and caches what the backend echoes back', async () => {
    apiPutMock.mockResolvedValueOnce({ entries: [OCT] })
    const store = useEventDefinitionsStore()
    const ok = await store.saveImagingPlan(STUDY, 7, [
      { modalityId: 1, requirement: 'required', laterality: 'OU', tasks: ['fluid'] },
    ])
    expect(ok).toBe(true)
    expect(apiPutMock).toHaveBeenCalledWith(URL, {
      entries: [{ modalityId: 1, requirement: 'required', laterality: 'OU', tasks: ['fluid'] }],
    })
    expect(store.imagingPlanByDef[7]).toEqual([OCT])
  })

  it('reports a rejected save as false, surfaces the message and keeps the cache', async () => {
    apiGetMock.mockResolvedValueOnce({ entries: [OCT] })
    apiPutMock.mockRejectedValueOnce(new ApiError(400, 'bad', { message: 'Modality 9 is not an active entry' }))
    const store = useEventDefinitionsStore()
    await store.loadImagingPlan(STUDY, 7)
    const ok = await store.saveImagingPlan(STUDY, 7, [
      { modalityId: 9, requirement: 'optional', laterality: null, tasks: [] },
    ])
    expect(ok).toBe(false)
    expect(store.error).toContain('Modality 9')
    expect(store.imagingPlanByDef[7]).toEqual([OCT])
  })

  it('reset clears the cached plans', async () => {
    apiGetMock.mockResolvedValueOnce({ entries: [OCT] })
    const store = useEventDefinitionsStore()
    await store.loadImagingPlan(STUDY, 7)
    store.reset()
    expect(store.imagingPlanByDef).toEqual({})
  })
})
