/**
 * Phase E.6 — useModalityBaselinesStore spec.
 *
 * Pins three behaviours the panel relies on:
 *   1. `load(label, eye)` hits the right URL and caches the result.
 *   2. A second `load(label, eye)` call is a no-op (cache hit) unless
 *      `force=true` is passed by the retry button.
 *   3. Errors land in `errorByKey` (network + 4xx paths both covered);
 *      auth errors propagate up so the router guard can react.
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

// eslint-disable-next-line import/first
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
// eslint-disable-next-line import/first
import { useModalityBaselinesStore, baselinesKey } from '../modalityBaselines'
// eslint-disable-next-line import/first
import type { ModalityBaseline } from '@/types/baselines'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>

function makeBaseline(overrides: Partial<ModalityBaseline> = {}): ModalityBaseline {
  return {
    modalityCode: 'IOP',
    labelEn: 'Intraocular pressure',
    labelDe: 'Augeninnendruck',
    itemOid: 'I_IOP',
    dataType: 'numeric',
    unit: 'mmHg',
    global: { date: '2026-04-01', value: '14', observationCount: 8 },
    perStudy: { date: '2026-05-01', value: '15', observationCount: 3 },
    ...overrides,
  }
}

describe('useModalityBaselinesStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiGetMock.mockReset()
  })

  it('starts empty', () => {
    const store = useModalityBaselinesStore()
    expect(store.byKey.size).toBe(0)
    expect(store.isLoadingByKey.size).toBe(0)
    expect(store.errorByKey.size).toBe(0)
  })

  it('calls the correct URL on load() and caches the response', async () => {
    const baselines = [makeBaseline()]
    apiGetMock.mockResolvedValueOnce(baselines)

    const store = useModalityBaselinesStore()
    await store.load('M-001', 'OD')

    expect(apiGetMock).toHaveBeenCalledWith(
      '/pages/api/v1/subjects/M-001/eyes/OD/modality-baselines',
    )
    const key = baselinesKey('M-001', 'OD')
    expect(store.byKey.get(key)).toEqual(baselines)
    expect(store.isLoadingByKey.get(key)).toBeUndefined()
    expect(store.errorByKey.get(key) ?? null).toBeNull()
  })

  it('URL-encodes the subject label so labels with slashes / spaces survive', async () => {
    apiGetMock.mockResolvedValueOnce([])
    const store = useModalityBaselinesStore()
    await store.load('M 0/01', 'OS')
    expect(apiGetMock).toHaveBeenCalledWith(
      '/pages/api/v1/subjects/M%200%2F01/eyes/OS/modality-baselines',
    )
  })

  it('keys cache entries per (label, eye) — OD and OS are independent', async () => {
    apiGetMock.mockResolvedValueOnce([makeBaseline({ modalityCode: 'IOP-OD' })])
    apiGetMock.mockResolvedValueOnce([makeBaseline({ modalityCode: 'IOP-OS' })])

    const store = useModalityBaselinesStore()
    await store.load('M-001', 'OD')
    await store.load('M-001', 'OS')

    expect(store.byKey.get('M-001|OD')?.[0]?.modalityCode).toBe('IOP-OD')
    expect(store.byKey.get('M-001|OS')?.[0]?.modalityCode).toBe('IOP-OS')
    expect(apiGetMock).toHaveBeenCalledTimes(2)
  })

  it('is a no-op on a second load() for the same key (cache hit)', async () => {
    apiGetMock.mockResolvedValueOnce([makeBaseline()])
    const store = useModalityBaselinesStore()
    await store.load('M-001', 'OD')
    await store.load('M-001', 'OD')
    expect(apiGetMock).toHaveBeenCalledTimes(1)
  })

  it('re-fetches when force=true (retry button path)', async () => {
    apiGetMock.mockResolvedValueOnce([makeBaseline({ modalityCode: 'first' })])
    apiGetMock.mockResolvedValueOnce([makeBaseline({ modalityCode: 'second' })])

    const store = useModalityBaselinesStore()
    await store.load('M-001', 'OD')
    expect(store.byKey.get('M-001|OD')?.[0]?.modalityCode).toBe('first')

    await store.load('M-001', 'OD', true)
    expect(apiGetMock).toHaveBeenCalledTimes(2)
    expect(store.byKey.get('M-001|OD')?.[0]?.modalityCode).toBe('second')
  })

  it('surfaces a friendly message when the backend is unreachable', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiNetworkError('refused', null))
    const store = useModalityBaselinesStore()
    await store.load('M-001', 'OD')
    const key = baselinesKey('M-001', 'OD')
    expect(store.errorByKey.get(key)).toMatch(/Backend nicht erreichbar/)
    // Cache stays empty so the retry button is the only path forward.
    expect(store.byKey.has(key)).toBe(false)
  })

  it('surfaces the server message on a 4xx ApiError', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(400, 'No active study bound'))
    const store = useModalityBaselinesStore()
    await store.load('M-001', 'OD')
    expect(store.errorByKey.get('M-001|OD')).toBe('No active study bound')
  })

  it('rethrows 401/403 so the router-level guard can take over', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(401, 'Unauthenticated'))
    const store = useModalityBaselinesStore()
    await expect(store.load('M-001', 'OD')).rejects.toThrow(/Unauthenticated/)
  })

  it('clears the cache + flags on reset() (study-switch path)', async () => {
    apiGetMock.mockResolvedValueOnce([makeBaseline()])
    const store = useModalityBaselinesStore()
    await store.load('M-001', 'OD')

    store.reset()
    expect(store.byKey.size).toBe(0)
    expect(store.isLoadingByKey.size).toBe(0)
    expect(store.errorByKey.size).toBe(0)
  })
})
