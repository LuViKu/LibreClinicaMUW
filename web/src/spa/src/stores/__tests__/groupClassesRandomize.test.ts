import { beforeEach, describe, expect, it, vi, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useGroupClassesStore } from '../groupClasses'

/**
 * 2026-07-02 — v1 randomization store action.
 *
 * <p>Covers the SPA-side of {@code POST /api/v1/studies/{oid}/group-classes/{id}/randomize}:
 *
 * <ul>
 *   <li>URL construction — {@code /pages/…} prefix + oid encoding.</li>
 *   <li>Method is POST + empty body.</li>
 *   <li>Response passes through as {@link RandomizeResult}.</li>
 * </ul>
 */

interface FetchCall {
  url: string
  init?: RequestInit
}

describe('useGroupClassesStore.randomize', () => {
  let calls: FetchCall[]
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    setActivePinia(createPinia())
    calls = []
    globalThis.fetch = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(url), init })
      return new Response(JSON.stringify({
        groupId: 42, groupName: 'AI_SHOWN', seed: 'a'.repeat(64),
        source: 'RANDOMIZED_UNIFORM', meta: null,
        groupClassId: 2, groupClassName: 'nAMD Arm',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('POSTs to the /randomize endpoint with the oid encoded', async () => {
    const store = useGroupClassesStore()
    const result = await store.randomize('S_RIS 2026', 2)
    expect(calls.length).toBe(1)
    // encodeURIComponent turns the space into %20.
    expect(calls[0].url).toContain('/pages/api/v1/studies/S_RIS%202026/group-classes/2/randomize')
    expect(calls[0].init?.method).toBe('POST')
    expect(result.groupId).toBe(42)
    expect(result.groupName).toBe('AI_SHOWN')
    expect(result.seed).toHaveLength(64)
    expect(result.source).toBe('RANDOMIZED_UNIFORM')
  })

  it('propagates ApiError so the caller can surface field errors', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({
      message: 'Randomization is not enabled for this study',
    }), { status: 409, headers: { 'Content-Type': 'application/json' } })) as typeof fetch
    const store = useGroupClassesStore()
    await expect(store.randomize('S_RIS', 2)).rejects.toMatchObject({
      status: 409,
    })
  })
})
