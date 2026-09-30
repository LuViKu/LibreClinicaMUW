import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

/**
 * The audit logs page through the server's whole trail: the server filters,
 * counts and pages it (`GET …/audit`, `GET …/audit/system`), and the store
 * asks for one page with the filters the view shows. Until 2026-09-30 the
 * store loaded the newest 500 rows once and filtered those itself.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn() }
})

// eslint-disable-next-line import/first
import { apiGet, ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import { useAuditLogStore } from '../auditLog'
// eslint-disable-next-line import/first
import { useSystemAuditLogStore } from '../systemAuditLog'
// eslint-disable-next-line import/first
import type { AuditEvent, AuditPage } from '@/types/audit'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>

const EVENTS: AuditEvent[] = [
  { id: '3', occurredAt: '2026-05-30T13:42:08Z', variant: 'signed', actor: 'investigator_demo', title: 'Subject sign-off', subjectId: 'M-001' },
  { id: '2', occurredAt: '2026-05-30T09:08:14Z', variant: 'sdv', actor: 'monitor_demo', title: 'SDV verified', subjectId: 'M-002' },
  { id: '1', occurredAt: '2026-05-29T16:11:09Z', variant: 'data', actor: 'investigator_demo', title: 'CRF marked complete', subjectId: 'M-001' },
]

function pageOf(events: AuditEvent[], totalCount: number, page = 0, pageSize = 100): AuditPage {
  return { totalCount, page, pageSize, events }
}

/** The query of the n-th apiGet call, as URLSearchParams. */
function query(call = 0): { path: string; params: URLSearchParams } {
  const url = apiGetMock.mock.calls[call]![0] as string
  const [path, qs] = url.split('?')
  return { path: path!, params: new URLSearchParams(qs ?? '') }
}

describe('useAuditLogStore (server-paged)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiGetMock.mockReset()
  })

  it('starts empty', () => {
    const store = useAuditLogStore()
    expect(store.events).toEqual([])
    expect(store.totalCount).toBe(0)
    expect(store.hasNextPage).toBe(false)
  })

  it('loads the first page and takes the total from the server', async () => {
    apiGetMock.mockResolvedValueOnce(pageOf(EVENTS, 1234))
    const store = useAuditLogStore()
    await store.load()

    const { path, params } = query()
    expect(path).toBe('/pages/api/v1/audit')
    expect(params.get('page')).toBe('0')
    expect(params.get('pageSize')).toBe('100')
    expect(params.has('actor')).toBe(false)
    expect(store.events).toHaveLength(3)
    expect(store.visibleCount).toBe(3)
    expect(store.totalCount).toBe(1234)
    expect(store.pageCount).toBe(13)
    expect(store.hasNextPage).toBe(true)
    expect(store.hasPreviousPage).toBe(false)
  })

  it('sends every filter to the server and goes back to the first page', async () => {
    apiGetMock.mockResolvedValue(pageOf(EVENTS, 1234, 4))
    const store = useAuditLogStore()
    await store.goToPage(4)
    apiGetMock.mockClear()
    apiGetMock.mockResolvedValue(pageOf(EVENTS.slice(0, 1), 1))

    store.actorFilter = 'investigator_demo'
    store.variantFilter = 'signed'
    store.subjectFilter = 'M-001'
    store.itemFilter = ' I_HEIGHT_CM '
    store.fromDate = '2026-05-01'
    store.toDate = '2026-05-31'
    await store.applyFilters()

    const { params } = query()
    expect(params.get('page')).toBe('0')
    expect(params.get('actor')).toBe('investigator_demo')
    expect(params.get('variant')).toBe('signed')
    expect(params.get('subjectId')).toBe('M-001')
    expect(params.get('item')).toBe('I_HEIGHT_CM')
    expect(params.get('from')).toBe('2026-05-01')
    expect(params.get('to')).toBe('2026-05-31')
    expect(store.page).toBe(0)
    expect(store.totalCount).toBe(1)
    expect(store.hasFilters).toBe(true)
  })

  it('pages forward and back, and not past either end', async () => {
    apiGetMock.mockImplementation(async (url: string) => {
      const page = Number(new URLSearchParams(url.split('?')[1]).get('page'))
      return pageOf(EVENTS, 250, page)
    })
    const store = useAuditLogStore()
    await store.load()
    await store.nextPage()
    expect(query(1).params.get('page')).toBe('1')
    await store.nextPage()
    expect(query(2).params.get('page')).toBe('2')
    expect(store.hasNextPage).toBe(false)
    await store.nextPage()
    expect(apiGetMock).toHaveBeenCalledTimes(3)
    await store.previousPage()
    expect(query(3).params.get('page')).toBe('1')
    expect(store.hasPreviousPage).toBe(true)
  })

  it('clearFilters drops every filter and reloads the first page', async () => {
    apiGetMock.mockResolvedValue(pageOf(EVENTS, 3))
    const store = useAuditLogStore()
    store.actorFilter = 'monitor_demo'
    store.variantFilter = 'sdv'
    store.itemFilter = 'I_X'
    store.fromDate = '2026-01-01'
    await store.clearFilters()

    expect(store.hasFilters).toBe(false)
    const { params } = query()
    expect([...params.keys()].sort()).toEqual(['page', 'pageSize'])
  })

  it('offers the actors and subjects of the whole log, not only of the page', async () => {
    apiGetMock.mockResolvedValueOnce({ actors: ['monitor_demo', 'root', 'system'], subjects: ['M-001', 'M-404'] })
    const store = useAuditLogStore()
    await store.loadFacets()
    expect(apiGetMock).toHaveBeenCalledWith('/pages/api/v1/audit/facets')
    expect(store.actors).toEqual(['monitor_demo', 'root', 'system'])
    expect(store.subjects).toEqual(['M-001', 'M-404'])
  })

  it('a facets failure leaves the choices empty', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(500, 'boom', null))
    const store = useAuditLogStore()
    await store.loadFacets()
    expect(store.actors).toEqual([])
  })

  it('groups the page by day, newest first', async () => {
    apiGetMock.mockResolvedValueOnce(pageOf(EVENTS, 3))
    const store = useAuditLogStore()
    await store.load()
    expect(store.groupedByDate.map((g) => g.date)).toEqual(['2026-05-30', '2026-05-29'])
    expect(store.groupedByDate[0]!.events).toHaveLength(2)
  })

  it('surfaces a failed page as error and shows no rows', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(400, 'bad', { message: 'from and to are days in the form yyyy-MM-dd' }))
    const store = useAuditLogStore()
    await store.load()
    expect(store.error).toContain('yyyy-MM-dd')
    expect(store.events).toEqual([])
    expect(store.totalCount).toBe(0)
  })

  it('reset() clears the page, the filters and the choices', async () => {
    apiGetMock.mockResolvedValueOnce(pageOf(EVENTS, 3))
    const store = useAuditLogStore()
    await store.load()
    store.subjectFilter = 'M-001'
    store.reset()
    expect(store.events).toEqual([])
    expect(store.totalCount).toBe(0)
    expect(store.subjectFilter).toBe('')
    expect(store.page).toBe(0)
  })
})

describe('useSystemAuditLogStore (server-paged)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiGetMock.mockReset()
  })

  it('pages the system trail and its facets', async () => {
    apiGetMock.mockResolvedValueOnce(pageOf(EVENTS, 9000))
    apiGetMock.mockResolvedValueOnce({ actors: ['system'], subjects: [] })
    const store = useSystemAuditLogStore()
    store.subjectFilter = 'M-004'
    await store.applyFilters()
    await store.loadFacets()

    const { path, params } = query()
    expect(path).toBe('/pages/api/v1/audit/system')
    expect(params.get('subjectId')).toBe('M-004')
    expect(store.totalCount).toBe(9000)
    expect(apiGetMock).toHaveBeenLastCalledWith('/pages/api/v1/audit/system/facets')
    expect(store.actors).toEqual(['system'])
  })
})
