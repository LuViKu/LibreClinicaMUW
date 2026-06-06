import { beforeEach, describe, expect, it, vi, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuditLogStore } from '../auditLog'

/**
 * Phase E.6 — covers the SPA's `exportXlsx()` action:
 *  - GET hits the right URL with the active filter chips forwarded
 *    as query params.
 *  - 401 routes into the store's auth-redirect path (re-thrown).
 *  - Server-side 500 surfaces as `exportError` without clobbering
 *    the main `error` ref used for list-load failures.
 */

interface FetchCall {
  url: string
  init?: RequestInit
}

function stubBrowserDownload() {
  // Vitest's jsdom env doesn't implement `URL.createObjectURL` /
  // `URL.revokeObjectURL`; stub them so the helper doesn't blow up.
  // `document.createElement('a').click()` exists in jsdom so the
  // anchor side-effect is a no-op (no real download is triggered).
  // @ts-expect-error jsdom polyfill
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:fake')
  // @ts-expect-error jsdom polyfill
  globalThis.URL.revokeObjectURL = vi.fn()
}

describe('useAuditLogStore.exportXlsx', () => {
  let calls: FetchCall[]
  let fetchSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    setActivePinia(createPinia())
    calls = []
    stubBrowserDownload()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  function mockFetch(response: Response | Error) {
    fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: typeof input === 'string' ? input : input.toString(), init })
      if (response instanceof Error) throw response
      return response
    })
    // @ts-expect-error global swap for the test
    globalThis.fetch = fetchSpy
  }

  function okBinary(filename = 'audit_S_DEMO_20260606.xlsx'): Response {
    return new Response(new Blob(['fake-xlsx-bytes']), {
      status: 200,
      headers: {
        'content-type':
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'content-disposition': `attachment; filename="${filename}"`,
      },
    })
  }

  it('forwards active filter chips as query params', async () => {
    mockFetch(okBinary())
    const store = useAuditLogStore()
    store.actorFilter = 'monitor_demo'
    store.variantFilter = 'admin'
    store.subjectFilter = 'M-001'

    await store.exportXlsx()

    expect(calls).toHaveLength(1)
    const url = new URL(calls[0].url, 'http://localhost')
    expect(url.pathname).toBe('/LibreClinica/pages/api/v1/audit/export.xlsx')
    expect(url.searchParams.get('actor')).toBe('monitor_demo')
    expect(url.searchParams.get('variant')).toBe('admin')
    expect(url.searchParams.get('subjectId')).toBe('M-001')
    expect(store.isExporting).toBe(false)
    expect(store.exportError).toBeNull()
  })

  it('omits query params when no filters are set', async () => {
    mockFetch(okBinary())
    const store = useAuditLogStore()
    await store.exportXlsx()
    const url = new URL(calls[0].url, 'http://localhost')
    expect(url.search).toBe('')
  })

  it('surfaces a backend 500 as exportError without touching error', async () => {
    mockFetch(
      new Response(JSON.stringify({ message: 'Failed to render workbook' }), {
        status: 500,
        headers: { 'content-type': 'application/json' },
      }),
    )
    const store = useAuditLogStore()
    store.error = 'previous list error'
    await store.exportXlsx()
    expect(store.exportError).toBe('Failed to render workbook')
    expect(store.error).toBe('previous list error')
    expect(store.isExporting).toBe(false)
  })

  it('re-throws 401 so the auth store can redirect to /login', async () => {
    mockFetch(
      new Response(JSON.stringify({ message: 'Not authenticated' }), {
        status: 401,
        headers: { 'content-type': 'application/json' },
      }),
    )
    const store = useAuditLogStore()
    await expect(store.exportXlsx()).rejects.toMatchObject({ status: 401 })
  })
})
