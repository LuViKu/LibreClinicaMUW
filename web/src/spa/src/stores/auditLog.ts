import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { AuditEvent, AuditEventVariant } from '@/types/audit'

/**
 * Phase E.6 + E.4 M10 — Audit-log store.
 *
 * Backs the StudyAuditLogView. Hydrates from
 * `GET /pages/api/v1/audit?actor=…&variant=…&subjectId=…` (the M10
 * adapter). Filters can be applied either server-side via the URL
 * params or client-side over the cached `events` list; the store
 * keeps client-side filters as the default UX so dropdown changes
 * don't trigger a round-trip per keypress.
 *
 * Mock removal — per the polished-jumping-swan plan's hard-removal
 * policy: the previous `loadMock()` helper + 7-row MOCK_EVENTS
 * fixture are deleted in this PR. If the backend is unreachable
 * the store sets `error` so the view can render an explicit
 * message rather than silently displaying stale demo data.
 */
export const useAuditLogStore = defineStore('auditLog', () => {
  const events = ref<AuditEvent[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const actorFilter = ref<string>('') // empty = all
  const variantFilter = ref<'all' | AuditEventVariant>('all')
  const subjectFilter = ref<string>('')

  const filtered = computed<AuditEvent[]>(() => {
    return events.value.filter((e) => {
      if (actorFilter.value && e.actor !== actorFilter.value) return false
      if (variantFilter.value !== 'all' && e.variant !== variantFilter.value) return false
      if (subjectFilter.value && e.subjectId !== subjectFilter.value) return false
      return true
    })
  })

  const totalCount = computed(() => events.value.length)
  const visibleCount = computed(() => filtered.value.length)

  const actors = computed<string[]>(() => {
    const set = new Set<string>()
    for (const e of events.value) set.add(e.actor)
    return [...set].sort()
  })

  const subjects = computed<string[]>(() => {
    const set = new Set<string>()
    for (const e of events.value) if (e.subjectId) set.add(e.subjectId)
    return [...set].sort()
  })

  const groupedByDate = computed<{ date: string; events: AuditEvent[] }[]>(() => {
    const map = new Map<string, AuditEvent[]>()
    for (const e of filtered.value) {
      const date = e.occurredAt.slice(0, 10)
      const bucket = map.get(date)
      if (bucket) bucket.push(e)
      else map.set(date, [e])
    }
    return [...map.entries()]
      .sort((a, b) => (a[0] < b[0] ? 1 : -1))
      .map(([date, events]) => ({ date, events }))
  })

  function clearFilters() {
    actorFilter.value = ''
    variantFilter.value = 'all'
    subjectFilter.value = ''
  }

  async function load(_studyOid?: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      events.value = await apiGet<AuditEvent[]>('/pages/api/v1/audit')
    } catch (e) {
      events.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Audit-Log kann nicht geladen werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden des Audit-Logs (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden des Audit-Logs.'
      }
    } finally {
      isLoading.value = false
    }
  }

  return {
    events,
    isLoading,
    error,
    actorFilter,
    variantFilter,
    subjectFilter,
    filtered,
    totalCount,
    visibleCount,
    actors,
    subjects,
    groupedByDate,
    clearFilters,
    load,
  }
})
