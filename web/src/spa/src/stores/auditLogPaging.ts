import { computed, ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { AuditEvent, AuditEventVariant, AuditFacets, AuditPage } from '@/types/audit'

/** Rows per page of an audit log (the server allows up to 500). */
export const AUDIT_PAGE_SIZE = 100

/** Messages a paged log shows when its page cannot be loaded. */
export interface AuditLogMessages {
  unreachable: string
  failed: (status: number) => string
  unknown: string
}

/**
 * The state and actions of one paged audit log, shared by the study log
 * and the system log.
 *
 * The server filters, counts and pages the whole trail
 * (`AuditApiController`, `GET …/audit` and `GET …/audit/system`); this holds
 * the page shown, the filters, and the values the actor and subject filters
 * offer, which come from the whole log (`…/facets`) rather than from the
 * page. Changing a filter goes back to the first page.
 */
export function useAuditLogPaging(listPath: string, facetsPath: string, messages: AuditLogMessages) {
  const events = ref<AuditEvent[]>([])
  const totalCount = ref(0)
  const page = ref(0)
  const pageSize = ref(AUDIT_PAGE_SIZE)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const actorFilter = ref<string>('') // empty = all
  const variantFilter = ref<'all' | AuditEventVariant>('all')
  const subjectFilter = ref<string>('')
  /** Item OID: the rows about that item's values. */
  const itemFilter = ref<string>('')
  /** Days, `yyyy-MM-dd`, both inclusive (UTC, like the times shown). */
  const fromDate = ref<string>('')
  const toDate = ref<string>('')
  const facets = ref<AuditFacets>({ actors: [], subjects: [] })

  const visibleCount = computed(() => events.value.length)
  const pageCount = computed(() => Math.max(1, Math.ceil(totalCount.value / pageSize.value)))
  const hasPreviousPage = computed(() => page.value > 0)
  const hasNextPage = computed(() => (page.value + 1) * pageSize.value < totalCount.value)
  const hasFilters = computed(() =>
    actorFilter.value !== '' || variantFilter.value !== 'all' || subjectFilter.value !== ''
    || itemFilter.value.trim() !== '' || fromDate.value !== '' || toDate.value !== '')

  const actors = computed<string[]>(() => facets.value.actors)
  const subjects = computed<string[]>(() => facets.value.subjects)

  const groupedByDate = computed<{ date: string; events: AuditEvent[] }[]>(() => {
    const map = new Map<string, AuditEvent[]>()
    for (const e of events.value) {
      const date = e.occurredAt.slice(0, 10)
      const bucket = map.get(date)
      if (bucket) bucket.push(e)
      else map.set(date, [e])
    }
    return [...map.entries()]
      .sort((a, b) => (a[0] < b[0] ? 1 : -1))
      .map(([date, events]) => ({ date, events }))
  })

  /** The active filters as query parameters, as the list and the export take them. */
  function filterParams(): URLSearchParams {
    const params = new URLSearchParams()
    if (actorFilter.value) params.set('actor', actorFilter.value)
    if (variantFilter.value !== 'all') params.set('variant', variantFilter.value)
    if (subjectFilter.value) params.set('subjectId', subjectFilter.value)
    if (itemFilter.value.trim()) params.set('item', itemFilter.value.trim())
    if (fromDate.value) params.set('from', fromDate.value)
    if (toDate.value) params.set('to', toDate.value)
    return params
  }

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      const params = filterParams()
      params.set('page', String(page.value))
      params.set('pageSize', String(pageSize.value))
      const body = await apiGet<AuditPage>(`${listPath}?${params.toString()}`)
      events.value = body.events
      totalCount.value = body.totalCount
      page.value = body.page
      pageSize.value = body.pageSize
    } catch (e) {
      events.value = []
      totalCount.value = 0
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value = messages.unreachable
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? messages.failed(e.status)
      } else {
        error.value = e instanceof Error ? e.message : messages.unknown
      }
    } finally {
      isLoading.value = false
    }
  }

  /** The actor and subject choices; a failure leaves them empty, the log still loads. */
  async function loadFacets(): Promise<void> {
    try {
      facets.value = await apiGet<AuditFacets>(facetsPath)
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      facets.value = { actors: [], subjects: [] }
    }
  }

  /** Reload from the first page, after a filter changed. */
  async function applyFilters(): Promise<void> {
    page.value = 0
    await load()
  }

  async function goToPage(n: number): Promise<void> {
    page.value = Math.max(0, Math.min(n, pageCount.value - 1))
    await load()
  }

  async function nextPage(): Promise<void> {
    if (hasNextPage.value) await goToPage(page.value + 1)
  }

  async function previousPage(): Promise<void> {
    if (hasPreviousPage.value) await goToPage(page.value - 1)
  }

  async function clearFilters(): Promise<void> {
    actorFilter.value = ''
    variantFilter.value = 'all'
    subjectFilter.value = ''
    itemFilter.value = ''
    fromDate.value = ''
    toDate.value = ''
    await applyFilters()
  }

  function resetState(): void {
    events.value = []
    totalCount.value = 0
    page.value = 0
    pageSize.value = AUDIT_PAGE_SIZE
    isLoading.value = false
    error.value = null
    actorFilter.value = ''
    variantFilter.value = 'all'
    subjectFilter.value = ''
    itemFilter.value = ''
    fromDate.value = ''
    toDate.value = ''
    facets.value = { actors: [], subjects: [] }
  }

  return {
    events,
    totalCount,
    page,
    pageSize,
    isLoading,
    error,
    actorFilter,
    variantFilter,
    subjectFilter,
    itemFilter,
    fromDate,
    toDate,
    facets,
    visibleCount,
    pageCount,
    hasPreviousPage,
    hasNextPage,
    hasFilters,
    actors,
    subjects,
    groupedByDate,
    filterParams,
    load,
    loadFacets,
    applyFilters,
    goToPage,
    nextPage,
    previousPage,
    clearFilters,
    resetState,
  }
}
