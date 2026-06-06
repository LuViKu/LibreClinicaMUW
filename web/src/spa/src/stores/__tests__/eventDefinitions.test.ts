import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

/**
 * Phase E.6 — spec for the {@link useEventDefinitionsStore} lifecycle
 * actions (restore / lock / unlock). Pins:
 *   - the URLs each action calls (encoded study + sed OIDs);
 *   - in-place row swap on success, with restore-specific behavior
 *     where a row that was filtered out of an active-only list gets
 *     re-appended;
 *   - 403 / 401 propagation (re-throw, also surfaces on `error`);
 *   - generic-HTTP / network error surfacing on `error` (no throw,
 *     boolean return reflects failure).
 *
 * Backend cascade behavior (status flips on child rows) is exercised
 * by the controller-level MockMvc IT slice — the store contract is
 * purely about URL / state-management semantics.
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
import { apiPost, ApiError, ApiNetworkError } from '@/api/client'
// eslint-disable-next-line import/first
import { useEventDefinitionsStore } from '../eventDefinitions'
// eslint-disable-next-line import/first
import type { EventDefinition } from '@/types/eventDefinition'

const apiPostMock = apiPost as unknown as ReturnType<typeof vi.fn>

const STUDY = 'S_DEFAULTS1'
const OID = 'SE_V1'

function fixture(overrides: Partial<EventDefinition> = {}): EventDefinition {
  return {
    oid: OID,
    name: 'Visit 1',
    description: '',
    category: '',
    type: 'scheduled',
    repeating: false,
    ordinal: 1,
    status: 'available',
    ...overrides,
  }
}

describe('useEventDefinitionsStore.restore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiPostMock.mockReset()
  })

  it('POSTs the correct URL and swaps the matching row in place', async () => {
    const store = useEventDefinitionsStore()
    store.rows = [fixture({ status: 'removed' })] as never
    const restored = fixture({ status: 'available' })
    apiPostMock.mockResolvedValueOnce(restored)

    const ok = await store.restore(STUDY, OID)
    expect(ok).toBe(true)
    expect(apiPostMock).toHaveBeenCalledWith(
      `/pages/api/v1/studies/${STUDY}/event-definitions/${OID}/restore`,
      {},
    )
    expect(store.rows.length).toBe(1)
    expect(store.rows[0]?.status).toBe('available')
  })

  it('appends the row when it was missing from the local list', async () => {
    const store = useEventDefinitionsStore()
    store.rows = [fixture({ oid: 'SE_OTHER', name: 'Other' })] as never
    const restored = fixture({ status: 'available' })
    apiPostMock.mockResolvedValueOnce(restored)

    const ok = await store.restore(STUDY, OID)
    expect(ok).toBe(true)
    expect(store.rows.length).toBe(2)
    expect(store.rows.map((r) => r.oid)).toContain(OID)
  })

  it('re-throws ApiError on 403 and surfaces error message', async () => {
    const store = useEventDefinitionsStore()
    apiPostMock.mockRejectedValueOnce(new ApiError(403, 'Forbidden', { message: 'Forbidden' }))

    await expect(store.restore(STUDY, OID)).rejects.toBeInstanceOf(ApiError)
    expect(store.error).toBe('Forbidden')
  })

  it('surfaces 409 conflict on error (no throw, returns false)', async () => {
    const store = useEventDefinitionsStore()
    apiPostMock.mockRejectedValueOnce(new ApiError(409, 'Conflict', { message: 'Not removed' }))

    const ok = await store.restore(STUDY, OID)
    expect(ok).toBe(false)
    expect(store.error).toBe('Not removed')
  })

  it('surfaces network failures on error', async () => {
    const store = useEventDefinitionsStore()
    apiPostMock.mockRejectedValueOnce(new ApiNetworkError('Network down', new Error('boom')))

    const ok = await store.restore(STUDY, OID)
    expect(ok).toBe(false)
    expect(store.error).toMatch(/Backend nicht erreichbar/)
  })

  it('URL-encodes the OIDs', async () => {
    const store = useEventDefinitionsStore()
    apiPostMock.mockResolvedValueOnce(fixture())
    await store.restore('S/A', 'SE/B')
    expect(apiPostMock).toHaveBeenCalledWith(
      '/pages/api/v1/studies/S%2FA/event-definitions/SE%2FB/restore',
      {},
    )
  })
})

describe('useEventDefinitionsStore.lock', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiPostMock.mockReset()
  })

  it('POSTs the correct URL and swaps the row to locked', async () => {
    const store = useEventDefinitionsStore()
    store.rows = [fixture({ status: 'available' })] as never
    apiPostMock.mockResolvedValueOnce(fixture({ status: 'locked' }))

    const ok = await store.lock(STUDY, OID)
    expect(ok).toBe(true)
    expect(apiPostMock).toHaveBeenCalledWith(
      `/pages/api/v1/studies/${STUDY}/event-definitions/${OID}/lock`,
      {},
    )
    expect(store.rows[0]?.status).toBe('locked')
  })

  it('re-throws on 403 and surfaces the message', async () => {
    const store = useEventDefinitionsStore()
    apiPostMock.mockRejectedValueOnce(
      new ApiError(403, 'Forbidden', { message: 'Only sysadmins may lock' }),
    )
    await expect(store.lock(STUDY, OID)).rejects.toBeInstanceOf(ApiError)
    expect(store.error).toBe('Only sysadmins may lock')
  })

  it('returns false + sets error on 409 already-locked', async () => {
    const store = useEventDefinitionsStore()
    apiPostMock.mockRejectedValueOnce(new ApiError(409, 'Conflict', { message: 'Already locked' }))

    const ok = await store.lock(STUDY, OID)
    expect(ok).toBe(false)
    expect(store.error).toBe('Already locked')
  })
})

describe('useEventDefinitionsStore.unlock', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiPostMock.mockReset()
  })

  it('POSTs the correct URL and swaps the row to available', async () => {
    const store = useEventDefinitionsStore()
    store.rows = [fixture({ status: 'locked' })] as never
    apiPostMock.mockResolvedValueOnce(fixture({ status: 'available' }))

    const ok = await store.unlock(STUDY, OID)
    expect(ok).toBe(true)
    expect(apiPostMock).toHaveBeenCalledWith(
      `/pages/api/v1/studies/${STUDY}/event-definitions/${OID}/unlock`,
      {},
    )
    expect(store.rows[0]?.status).toBe('available')
  })

  it('returns false + sets error on 409 not-locked', async () => {
    const store = useEventDefinitionsStore()
    apiPostMock.mockRejectedValueOnce(new ApiError(409, 'Conflict', { message: 'Not locked' }))

    const ok = await store.unlock(STUDY, OID)
    expect(ok).toBe(false)
    expect(store.error).toBe('Not locked')
  })

  it('re-throws on 403', async () => {
    const store = useEventDefinitionsStore()
    apiPostMock.mockRejectedValueOnce(new ApiError(403, 'Forbidden', { message: 'Forbidden' }))
    await expect(store.unlock(STUDY, OID)).rejects.toBeInstanceOf(ApiError)
  })
})
