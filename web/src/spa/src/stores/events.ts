import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type { ScheduleEventRequest, StudyEvent, StudyEventStatus } from '@/types/event'

/**
 * Phase E.4 M11 — Study-event scheduling store.
 *
 * Wraps `GET /pages/api/v1/events` (cross-subject list) and
 * `POST /pages/api/v1/events` (schedule a new event). The schedule
 * view binds against `filtered` for the table and calls `schedule()`
 * from the form. Filter state mirrors the legacy "Manage Events"
 * subject-event-status dropdown.
 *
 * No mock loader — this milestone introduces the store fresh
 * post-mock-removal, so there's no MOCK_* fixture to delete.
 */
export const useEventsStore = defineStore('events', () => {
  const events = ref<StudyEvent[]>([])
  const isLoading = ref(false)
  const isScheduling = ref(false)
  const error = ref<string | null>(null)

  const query = ref('')
  const statusFilter = ref<'all' | StudyEventStatus>('all')
  const subjectFilter = ref<string>('') // empty = all

  const filtered = computed<StudyEvent[]>(() => {
    const q = query.value.trim().toLowerCase()
    return events.value.filter((e) => {
      if (q) {
        const blob = `${e.subjectId} ${e.eventLabel} ${e.location ?? ''}`.toLowerCase()
        if (!blob.includes(q)) return false
      }
      if (statusFilter.value !== 'all' && e.status !== statusFilter.value) return false
      if (subjectFilter.value && e.subjectId !== subjectFilter.value) return false
      return true
    })
  })

  const totalCount = computed(() => events.value.length)
  const visibleCount = computed(() => filtered.value.length)

  /** All distinct subject labels seen — drives the subject filter dropdown. */
  const subjects = computed<string[]>(() => {
    const set = new Set<string>()
    for (const e of events.value) set.add(e.subjectId)
    return [...set].sort()
  })

  /** All distinct event-definition OIDs seen — drives the definition picker. */
  const eventDefinitions = computed<{ oid: string; label: string; repeating: boolean }[]>(() => {
    const map = new Map<string, { oid: string; label: string; repeating: boolean }>()
    for (const e of events.value) {
      if (!map.has(e.eventDefinitionOid)) {
        map.set(e.eventDefinitionOid, {
          oid: e.eventDefinitionOid,
          label: e.eventLabel,
          repeating: e.repeating,
        })
      }
    }
    return [...map.values()].sort((a, b) => a.label.localeCompare(b.label))
  })

  function clearFilters() {
    query.value = ''
    statusFilter.value = 'all'
    subjectFilter.value = ''
  }

  async function load(_studyOid?: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      events.value = await apiGet<StudyEvent[]>('/pages/api/v1/events')
    } catch (e) {
      events.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Events können nicht geladen werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden der Events (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Events.'
      }
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Schedule a new event. On success the returned row is prepended
   * to `events` so the table reflects the new state without a
   * reload round-trip.
   */
  async function schedule(input: ScheduleEventRequest): Promise<StudyEvent | null> {
    isScheduling.value = true
    error.value = null
    try {
      const created = await apiPost<StudyEvent>('/pages/api/v1/events', input)
      events.value = [created, ...events.value]
      return created
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Event kann nicht angelegt werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Anlegen fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Anlegen.'
      }
      return null
    } finally {
      isScheduling.value = false
    }
  }

  return {
    events,
    isLoading,
    isScheduling,
    error,
    query,
    statusFilter,
    subjectFilter,
    filtered,
    totalCount,
    visibleCount,
    subjects,
    eventDefinitions,
    clearFilters,
    load,
    schedule,
  }
})
