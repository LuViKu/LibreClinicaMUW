/**
 * Phase E hardening B3 — Vitest coverage for the single-retry policy on
 * {@link request}.
 *
 * Contract:
 *   - On `ApiNetworkError` (fetch threw), a `GET` retries ONCE after a
 *     500 ms backoff.
 *   - All other methods (POST/PUT/DELETE/PATCH) rethrow immediately.
 *   - `ApiError` (server reachable, error response) is never retried —
 *     retry triggers off the fetch-throw catch path only.
 *
 * Why this matters: a retried clinical save against a flaky network is
 * the worst possible failure mode (duplicate rows, partial writes).
 * Idempotent reads are safe to retry; mutations are not.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, ApiNetworkError, apiGet, apiPost } from '../client'

// Phase E hardening B4 added a lazy `await import('@/stores/connection')`
// + `useConnectionStore().markOffline()` call inside the ApiNetworkError
// catch path. Under vi.useFakeTimers() the dynamic import's microtask
// queue doesn't advance through `runAllTimersAsync` reliably, which
// would time out the GET-retry test. Stubbing the module keeps the
// import synchronous-equivalent — the retry path is what B3's test
// actually exercises, B4 has its own dedicated tests for the store.
vi.mock('@/stores/connection', () => ({
  useConnectionStore: () => ({ markOffline: vi.fn(), markOnline: vi.fn() }),
}))

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('api/client single-retry policy (B3)', () => {
  beforeEach(() => {
    // setTimeout(500) makes the test slow otherwise; fake timers + flush
    // would work but complicate the async wiring — instead we stub the
    // global setTimeout via vi.useFakeTimers + runAllTimersAsync.
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('apiGet retries once on ApiNetworkError and succeeds on the second attempt', async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }))
    vi.stubGlobal('fetch', fetchMock)

    const pending = apiGet<{ ok: boolean }>('/pages/api/v1/me')
    // Advance the 500 ms backoff timer so the retry can proceed.
    await vi.runAllTimersAsync()
    const value = await pending

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(value).toEqual({ ok: true })
  })

  it('apiPost does NOT retry on ApiNetworkError', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiPost('/pages/api/v1/subjects', { foo: 'bar' })).rejects.toBeInstanceOf(
      ApiNetworkError,
    )
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('apiGet does NOT retry on ApiError (server reachable, error response)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(500, { message: 'boom' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiGet('/pages/api/v1/me')).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
