import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

/**
 * The visit definitions held by the store belong to one study. A list loaded
 * for another study is never reused (it showed another study's visits, or
 * none, in the schedule dialog), and a failed load is retried.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn() }
})

// eslint-disable-next-line import/first
import { apiGet, ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import { useEventDefinitionsStore } from '../eventDefinitions'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>

const def = (oid: string) => ({
  oid, name: oid, description: '', category: '', type: 'scheduled', repeating: false, ordinal: 1, status: 'available',
})

describe('eventDefinitions.ensure', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiGetMock.mockReset()
  })

  it('loads once per study and again for another study', async () => {
    apiGetMock.mockImplementation(async (path: string) => (path.includes('S_HAE') ? [def('SE_HAE_V1')] : [def('SE_NAMD_V1')]))
    const store = useEventDefinitionsStore()

    await store.ensure('S_NAMD')
    await store.ensure('S_NAMD')
    expect(apiGetMock).toHaveBeenCalledTimes(1)
    expect(store.loadedFor).toBe('S_NAMD')

    await store.ensure('S_HAE')
    expect(apiGetMock).toHaveBeenCalledTimes(2)
    expect(store.rows.map((r) => r.oid)).toEqual(['SE_HAE_V1'])
    expect(store.loadedFor).toBe('S_HAE')
  })

  it('retries after a failed load instead of keeping the empty list', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(409, 'Conflict', { message: 'got a site' }))
    apiGetMock.mockResolvedValueOnce([def('SE_HAE_V1')])
    const store = useEventDefinitionsStore()

    await store.ensure('S_HAE')
    expect(store.rows).toEqual([])
    expect(store.error).toBeTruthy()
    expect(store.loadedFor).toBeNull()

    await store.ensure('S_HAE')
    expect(store.rows.map((r) => r.oid)).toEqual(['SE_HAE_V1'])
    expect(store.error).toBeNull()
  })

  it('forgets the study on reset', async () => {
    apiGetMock.mockResolvedValue([def('SE_HAE_V1')])
    const store = useEventDefinitionsStore()
    await store.ensure('S_HAE')
    store.reset()
    expect(store.loadedFor).toBeNull()
    await store.ensure('S_HAE')
    expect(apiGetMock).toHaveBeenCalledTimes(2)
  })
})
