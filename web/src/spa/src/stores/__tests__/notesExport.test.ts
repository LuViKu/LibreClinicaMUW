import { beforeEach, describe, expect, it, vi, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useNotesStore } from '../notes'

/**
 * Phase E.6 — covers the SPA's `exportCsv()` action:
 *  - Default filter set (status=open) omits the synthetic 'open'
 *    aggregation from the wire (the backend understands single SPA
 *    status names only).
 *  - A concrete status filter (e.g. 'new') IS forwarded.
 *  - Subject-scoped export includes the subjectId query param.
 *  - assignedTo only ships when `onlyAssignedToMe` is on.
 */

interface FetchCall {
  url: string
  init?: RequestInit
}

function stubBrowserDownload() {
  // @ts-expect-error jsdom polyfill
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:fake')
  // @ts-expect-error jsdom polyfill
  globalThis.URL.revokeObjectURL = vi.fn()
}

describe('useNotesStore.exportCsv', () => {
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

  function okCsv(filename = 'discrepancies_S_DEMO_20260606.csv'): Response {
    return new Response(new Blob(['id,type\r\n'], { type: 'text/csv' }), {
      status: 200,
      headers: {
        'content-type': 'text/csv; charset=utf-8',
        'content-disposition': `attachment; filename="${filename}"`,
      },
    })
  }

  it('omits the synthetic "open" status from the query string', async () => {
    mockFetch(okCsv())
    const store = useNotesStore()
    // Default statusFilter is 'open' which is a synthetic aggregation.
    await store.exportCsv()
    const url = new URL(calls[0].url, 'http://localhost')
    expect(url.pathname).toBe('/LibreClinica/pages/api/v1/discrepancies/export.csv')
    expect(url.search).toBe('')
    expect(store.exportError).toBeNull()
  })

  it('forwards a concrete status filter as a query param', async () => {
    mockFetch(okCsv())
    const store = useNotesStore()
    store.statusFilter = 'new'
    await store.exportCsv()
    const url = new URL(calls[0].url, 'http://localhost')
    expect(url.searchParams.get('status')).toBe('new')
  })

  it('forwards subjectId when called from a subject-scoped view', async () => {
    mockFetch(okCsv())
    const store = useNotesStore()
    await store.exportCsv({ subjectId: 'M-001' })
    const url = new URL(calls[0].url, 'http://localhost')
    expect(url.searchParams.get('subjectId')).toBe('M-001')
  })

  it('only ships assignedTo when "only mine" is on', async () => {
    mockFetch(okCsv())
    const store = useNotesStore()
    store.me = 'monitor_demo'
    // off → no assignedTo
    await store.exportCsv()
    expect(new URL(calls[0].url, 'http://localhost').searchParams.get('assignedTo'))
      .toBeNull()
    // on → assignedTo = me
    store.onlyAssignedToMe = true
    await store.exportCsv()
    expect(new URL(calls[1].url, 'http://localhost').searchParams.get('assignedTo'))
      .toBe('monitor_demo')
  })
})
