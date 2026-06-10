/**
 * Phase E hardening A4 — Vitest coverage for {@link request} reqId
 * propagation.
 *
 * Three core cases:
 *  1. Outgoing request carries a UUIDv4 `X-Request-Id` header.
 *  2. Server-echoed `X-Request-Id` lands on `ApiError.reqId`.
 *  3. Network failure → `ApiNetworkError.reqId` carries the client UUID.
 *
 * The id-generation fallback (jsdom without `crypto.randomUUID`) is
 * exercised implicitly because vitest's default jsdom environment may
 * or may not expose `randomUUID` depending on Node version; both paths
 * must produce a RFC4122 UUIDv4 string.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, ApiNetworkError, apiGet, apiPost } from '../client'

const UUID_V4_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function jsonResponse(
  status: number,
  body: unknown,
  extraHeaders: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...extraHeaders },
  })
}

describe('api/client reqId propagation (A4)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends an X-Request-Id header matching the UUIDv4 shape', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { ok: true }))
    vi.stubGlobal('fetch', fetchMock)

    await apiGet<{ ok: boolean }>('/pages/api/v1/me')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    const headers = init.headers as Record<string, string>
    const sentId = headers['X-Request-Id']
    expect(sentId).toBeDefined()
    expect(sentId).toMatch(UUID_V4_PATTERN)
  })

  it('captures the server-echoed X-Request-Id on ApiError.reqId', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(500, { message: 'boom' }, { 'X-Request-Id': 'server-echo' }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiPost('/pages/api/v1/subjects', { foo: 'bar' })).rejects.toMatchObject({
      name: 'ApiError',
      status: 500,
      reqId: 'server-echo',
    })
  })

  it('falls back to the client-generated id when the server omits the echo', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(400, { message: 'nope' }))
    vi.stubGlobal('fetch', fetchMock)

    let captured: ApiError | undefined
    try {
      await apiGet('/pages/api/v1/me')
    } catch (e) {
      captured = e as ApiError
    }
    expect(captured).toBeInstanceOf(ApiError)
    const sentId = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    // Echo absent → client should fall back to the id it sent.
    expect(captured!.reqId).toBe(sentId['X-Request-Id'])
    expect(captured!.reqId).toMatch(UUID_V4_PATTERN)
  })

  it('surfaces the client-generated id on ApiNetworkError when the network fails', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    vi.stubGlobal('fetch', fetchMock)

    let captured: ApiNetworkError | undefined
    try {
      await apiGet('/pages/api/v1/me')
    } catch (e) {
      captured = e as ApiNetworkError
    }
    expect(captured).toBeInstanceOf(ApiNetworkError)
    const sentId = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    expect(captured!.reqId).toBe(sentId['X-Request-Id'])
    expect(captured!.reqId).toMatch(UUID_V4_PATTERN)
  })

  it('generates a fresh UUIDv4 per request (ids do not collide)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, {})))
    await apiGet('/pages/api/v1/a')
    await apiGet('/pages/api/v1/b')
    const fetchMock = (globalThis.fetch as unknown) as ReturnType<typeof vi.fn>
    const id1 = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    const id2 = (fetchMock.mock.calls[1][1] as RequestInit).headers as Record<string, string>
    expect(id1['X-Request-Id']).not.toEqual(id2['X-Request-Id'])
  })
})
