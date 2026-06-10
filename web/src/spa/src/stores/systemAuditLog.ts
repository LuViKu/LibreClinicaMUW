import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { AuditEvent, AuditEventVariant } from '@/types/audit'

/**
 * Phase E hardening B — System-wide audit-log store.
 *
 * Backs the {@link SystemAuditLogView}. Hydrates from
 * `GET /pages/api/v1/audit/system` (the sysadmin-only adapter that
 * bypasses the per-study endpoint's {@code is_user_visible=true}
 * filter so OPERATION_FAILED(61) + JOB_FAILED(62) §11.10(e) rows are
 * surfaced for compliance review).
 *
 * Mirrors {@link useAuditLogStore} byte-for-byte except for:
 *   - the URL (`/audit/system` vs `/audit`),
 *   - no `exportXlsx` action (XLSX export is deferred per the
 *     plan — the per-study audit-log already carries one and the
 *     sysadmin surface ships read-only first).
 *
 * Forbidden / unauthorized errors are re-thrown so the global error
 * handler + the router-guard can react (an Investigator who
 * accidentally lands here gets bounced home rather than seeing a
 * forever-loading state).
 */
export const useSystemAuditLogStore = defineStore('systemAuditLog', () => {
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

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      events.value = await apiGet<AuditEvent[]>('/pages/api/v1/audit/system')
    } catch (e) {
      events.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — System-Audit-Protokoll kann nicht geladen werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value =
          body?.message ?? `Fehler beim Laden des System-Audit-Protokolls (HTTP ${e.status}).`
      } else {
        error.value =
          e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden des System-Audit-Protokolls.'
      }
    } finally {
      isLoading.value = false
    }
  }

  function reset() {
    events.value = []
    isLoading.value = false
    error.value = null
    actorFilter.value = ''
    variantFilter.value = 'all'
    subjectFilter.value = ''
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
    reset,
  }
})
