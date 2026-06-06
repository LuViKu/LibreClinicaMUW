import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiDelete, apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  ScheduleEventRequest,
  StudyEvent,
  StudyEventStatus,
  UpdateEventRequest,
} from '@/types/event'

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
  /**
   * Phase E.6 restore-quickwins — when true, `load()` requests
   * `?includeRemoved=true` and the schedule view can surface
   * soft-deleted (`removed`) events with the Restore action.
   */
  const showRemoved = ref(false)

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
      const path = showRemoved.value
        ? '/pages/api/v1/events?includeRemoved=true'
        : '/pages/api/v1/events'
      events.value = await apiGet<StudyEvent[]>(path)
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

  /**
   * Phase E A4 — edit an existing study event. The backend
   * (`PUT /api/v1/events/{id}`) accepts a partial body where each
   * field is optional (omitted = unchanged). Returns a discriminated
   * union mirroring A2's `updateSubject` shape so the form view can
   * light up per-field errors.
   *
   * <p>Role-gated to Investigator / CRC / DM / Admin; Monitor + RA
   * are forbidden. State guard: signed / locked events refuse the
   * edit with 409.
   *
   * <p>On success the in-memory event row is replaced by id.
   */
  async function updateEvent(
    eventId: string,
    body: UpdateEventRequest,
  ): Promise<{ ok: true; event: StudyEvent }
              | { ok: false; message: string }> {
    try {
      const refreshed = await apiPut<StudyEvent>(
        `/pages/api/v1/events/${encodeURIComponent(eventId)}`,
        body,
      )
      const idx = events.value.findIndex((e) => e.id === eventId)
      if (idx >= 0) {
        events.value = [
          ...events.value.slice(0, idx),
          refreshed,
          ...events.value.slice(idx + 1),
        ]
      }
      return { ok: true, event: refreshed }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const errBody = e.body as { message?: string } | null
        error.value = errBody?.message ?? `Bearbeiten nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiError) {
        const errBody = e.body as { message?: string } | null
        return { ok: false, message: errBody?.message ?? `Bearbeiten fehlgeschlagen (HTTP ${e.status}).` }
      }
      if (e instanceof ApiNetworkError) {
        return { ok: false,
                 message: 'Backend nicht erreichbar — Bearbeiten fehlgeschlagen. Bitte später erneut versuchen.' }
      }
      return { ok: false,
               message: e instanceof Error ? e.message : 'Unbekannter Fehler beim Bearbeiten.' }
    }
  }

  /**
   * Phase E A4 — cancel (soft-delete) a study event. The backend
   * (`DELETE /api/v1/events/{id}`) cascades to nested event_crfs +
   * item_data as AUTO_DELETED. Role-gated to DM / Admin only;
   * Investigators must escalate.
   *
   * <p>Returns 204 from the backend on success; the in-memory event
   * row is removed locally.
   */
  async function cancelEvent(eventId: string): Promise<boolean> {
    try {
      await apiDelete<void>(`/pages/api/v1/events/${encodeURIComponent(eventId)}`)
      events.value = events.value.filter((e) => e.id !== eventId)
      return true
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Stornieren nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Stornieren fehlgeschlagen. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Stornieren fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Stornieren.'
      }
      return false
    }
  }

  /**
   * Phase E.6 restore-quickwins — restore a soft-deleted study event.
   * Backend (`POST /api/v1/events/{id}/restore`) flips DELETED →
   * AVAILABLE and cascades AUTO_DELETED event_crfs + item_data back
   * to AVAILABLE. Returns the restored {@link StudyEvent} so the
   * row's status flips in place — no full reload needed.
   *
   * <p>Role-gated to DM / Admin only. Investigators must escalate.
   */
  async function restoreEvent(eventId: string): Promise<boolean> {
    try {
      const restored = await apiPost<StudyEvent>(
        `/pages/api/v1/events/${encodeURIComponent(eventId)}/restore`,
        {},
      )
      // Replace the in-memory row so the SPA reflects the new status
      // without a full reload. If the schedule view is filtering out
      // removed events the freshly-restored row stays visible.
      events.value = events.value.map((e) => (e.id === eventId ? restored : e))
      return true
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Wiederherstellen nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Wiederherstellen fehlgeschlagen. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Wiederherstellen fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Wiederherstellen.'
      }
      return false
    }
  }

  /**
   * Phase E.6 — clear every piece of study-scoped state so the store
   * doesn't carry events from study A into study B. Called by
   * {@link useAuthStore.pickStudy} before re-bootstrapping.
   */
  function reset() {
    events.value = []
    isLoading.value = false
    isScheduling.value = false
    error.value = null
    query.value = ''
    statusFilter.value = 'all'
    subjectFilter.value = ''
    showRemoved.value = false
  }

  return {
    events,
    isLoading,
    isScheduling,
    error,
    query,
    statusFilter,
    subjectFilter,
    showRemoved,
    filtered,
    totalCount,
    visibleCount,
    subjects,
    eventDefinitions,
    clearFilters,
    load,
    schedule,
    updateEvent,
    cancelEvent,
    restoreEvent,
    reset,
  }
})
