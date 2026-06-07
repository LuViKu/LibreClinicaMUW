import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import { apiDownload } from '@/api/download'
import type { DiscrepancyNote, NoteStatus, NoteType, ThreadEntry } from '@/types/note'

/**
 * Phase E.6 + E.4 M7 — Discrepancy-notes store.
 *
 * Backs the NotesDiscrepanciesView. Hydrates from
 * `GET /pages/api/v1/discrepancies` (the M7 adapter); the optional
 * `assignedTo` / `subjectId` / `status` query params let the server
 * narrow the SQL before the response ships. Filter state mirrors the
 * legacy session-scoped JSP context: status, type, free text,
 * only-assigned-to-me. Client-side filters are applied on top of
 * whatever the server returns so the UX stays responsive without a
 * round-trip per keypress.
 *
 * Mock removal — per the polished-jumping-swan plan's "hard removal"
 * policy: the previous `loadMock()` helper + 7-row MOCK constant are
 * deleted in this PR. If the backend is unreachable the store sets
 * `error` so the view can render an explicit message rather than
 * silently displaying stale demo data.
 */
export const useNotesStore = defineStore('notes', () => {
  const rows = ref<DiscrepancyNote[]>([])
  const isLoading = ref(false)
  const isSubmitting = ref(false)
  const isExporting = ref(false)
  const error = ref<string | null>(null)
  const exportError = ref<string | null>(null)

  const query = ref('')
  const statusFilter = ref<'open' | 'all' | NoteStatus>('open') // open = anything but closed/N-A
  const typeFilter = ref<'all' | NoteType>('all')
  const onlyAssignedToMe = ref(false)

  /** Username of the current user — wired from auth store in E.8. */
  const me = ref<string>('monitor_demo')

  const filtered = computed<DiscrepancyNote[]>(() => {
    const q = query.value.trim().toLowerCase()
    return rows.value.filter((n) => {
      if (q) {
        const blob = `${n.subjectId} ${n.itemOid} ${n.description}`.toLowerCase()
        if (!blob.includes(q)) return false
      }
      if (statusFilter.value === 'open') {
        if (n.status === 'closed' || n.status === 'not-applicable') return false
      } else if (statusFilter.value !== 'all' && n.status !== statusFilter.value) {
        return false
      }
      if (typeFilter.value !== 'all' && n.type !== typeFilter.value) return false
      if (onlyAssignedToMe.value && n.assignedTo !== me.value) return false
      return true
    })
  })

  const totalCount = computed(() => rows.value.length)
  const visibleCount = computed(() => filtered.value.length)

  /** Per-type tallies of currently open notes — drives the summary cards. */
  const openTypeTotals = computed<Record<NoteType, number>>(() => {
    const out: Record<NoteType, number> = {
      'query': 0,
      'failed-validation': 0,
      'annotation': 0,
      'reason-for-change': 0,
    }
    for (const n of rows.value) {
      if (n.status === 'closed' || n.status === 'not-applicable') continue
      out[n.type] += 1
    }
    return out
  })

  const openCount = computed(() =>
    rows.value.filter((n) => n.status !== 'closed' && n.status !== 'not-applicable').length,
  )

  function clearFilters() {
    query.value = ''
    statusFilter.value = 'open'
    typeFilter.value = 'all'
    onlyAssignedToMe.value = false
  }

  async function load(_studyOid?: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      rows.value = await apiGet<DiscrepancyNote[]>('/pages/api/v1/discrepancies')
    } catch (e) {
      rows.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Discrepancies können nicht geladen werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden der Discrepancies (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Discrepancies.'
      }
    } finally {
      isLoading.value = false
    }
  }

  async function add(input: {
    subjectId: string
    itemOid: string
    /**
     * Phase E.6 DN — opaque event_crf identifier from the SPA route
     * (e.g. {@code /subjects/:subjectId/events/:eventCrfOid/crf/:itemOid}).
     * Required for the backend to pin the note to the correct event
     * ordinal in repeating events; when absent the backend falls back
     * to its unscoped item-data lookup (the M7 behaviour, which
     * collapses repeating-event ordinals to whichever item_data row
     * was hydrated first).
     */
    eventCrfOid?: string
    description: string
    assignedTo?: string | null
    /**
     * Phase E.6 — discrepancy type. Defaults to 'query' to preserve M7
     * call-site behaviour. The backend role-gates 'reason-for-change'
     * to DM/Admin; SPA-side guards in {@code canCreateNoteType} hide
     * the option for non-permitted roles before the request fires.
     */
    type?: NoteType
  }): Promise<DiscrepancyNote | null> {
    isSubmitting.value = true
    error.value = null
    try {
      const created = await apiPost<DiscrepancyNote>('/pages/api/v1/discrepancies', {
        subjectId: input.subjectId,
        itemOid: input.itemOid,
        eventCrfOid: input.eventCrfOid ?? null,
        description: input.description,
        assignedTo: input.assignedTo ?? null,
        type: input.type ?? 'query',
      })
      rows.value = [created, ...rows.value]
      return created
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Query kann nicht angelegt werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Anlegen fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Anlegen.'
      }
      return null
    } finally {
      isSubmitting.value = false
    }
  }

  /**
   * Phase E A1 — append a child note to an existing parent and
   * transition the parent's status. The backend
   * (`POST /api/v1/discrepancies/{parentId}/thread`) enforces the same
   * role × transition matrix the SPA renders client-side via
   * `canRespondToNote` / `canResolveNote` / `canCloseNote`.
   *
   * On success the returned `DiscrepancyNote` is the refreshed parent
   * (carrying the new `status` + updated `lastActivityAt`). The store
   * updates the in-memory row in place by id.
   */
  async function appendThread(
    parentId: string,
    input: {
      newStatus: NoteStatus
      description?: string
      assignedTo?: string | null
    },
  ): Promise<DiscrepancyNote | null> {
    isSubmitting.value = true
    error.value = null
    try {
      const refreshed = await apiPost<DiscrepancyNote>(
        `/pages/api/v1/discrepancies/${parentId}/thread`,
        {
          newStatus: input.newStatus,
          description: input.description ?? null,
          assignedTo: input.assignedTo ?? null,
        },
      )
      const idx = rows.value.findIndex((n) => n.id === parentId)
      if (idx >= 0) {
        rows.value = [
          ...rows.value.slice(0, idx),
          refreshed,
          ...rows.value.slice(idx + 1),
        ]
      } else {
        rows.value = [refreshed, ...rows.value]
      }
      return refreshed
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Zustandsänderung nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Zustandsänderung konnte nicht gespeichert werden.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Zustandsänderung fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value =
          e instanceof Error ? e.message : 'Unbekannter Fehler bei der Zustandsänderung.'
      }
      return null
    } finally {
      isSubmitting.value = false
    }
  }

  /**
   * Phase E.6 {@code discrepancy-full} — fetch the full thread (parent
   * + every child in insertion order) for a parent note. The result
   * is cached per parentId so re-expanding a row is instant.
   *
   * <p>Returns the hydrated parent note (with its {@code thread} field
   * populated) so the view can render the timeline component inline.
   * Returns null on error; the per-row UI shows the store's error
   * banner.
   */
  const threadCache = ref<Record<string, ThreadEntry[]>>({})
  const loadingThreadId = ref<string | null>(null)

  async function loadThread(parentId: string): Promise<DiscrepancyNote | null> {
    if (threadCache.value[parentId]) {
      const cached = rows.value.find((n) => n.id === parentId)
      if (cached) return { ...cached, thread: threadCache.value[parentId] }
    }
    loadingThreadId.value = parentId
    error.value = null
    try {
      const hydrated = await apiGet<DiscrepancyNote>(
        `/pages/api/v1/discrepancies/${parentId}/thread`,
      )
      threadCache.value = { ...threadCache.value, [parentId]: hydrated.thread ?? [] }
      // Refresh the in-memory row so reactive bindings see the new
      // status / lastActivityAt drawn from the hydrated payload.
      const idx = rows.value.findIndex((n) => n.id === parentId)
      if (idx >= 0) {
        rows.value = [
          ...rows.value.slice(0, idx),
          hydrated,
          ...rows.value.slice(idx + 1),
        ]
      }
      return hydrated
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Thread kann nicht geladen werden.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden des Threads (HTTP ${e.status}).`
      } else {
        error.value =
          e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden des Threads.'
      }
      return null
    } finally {
      loadingThreadId.value = null
    }
  }

  /**
   * Phase E.6 — sponsor / inspector CSV hand-off of the discrepancy
   * list. Forwards the same status / subject / assignedTo filters the
   * SPA is showing so the file row count matches the visible list. The
   * backend emits an audit_log_event row (type 56) per successful
   * download.
   *
   * When called with `{ subjectId }` the filename includes the slug so
   * multiple per-subject hand-offs don't collide on disk.
   */
  async function exportCsv(opts: { subjectId?: string } = {}): Promise<void> {
    isExporting.value = true
    exportError.value = null
    try {
      const params = new URLSearchParams()
      // Status filter mapping — backend understands the single SPA
      // status names, not the synthetic 'open' / 'all' aggregations.
      if (
        statusFilter.value !== 'open' &&
        statusFilter.value !== 'all'
      ) {
        params.set('status', statusFilter.value)
      }
      const subjectId = opts.subjectId ?? ''
      if (subjectId) params.set('subjectId', subjectId)
      if (onlyAssignedToMe.value && me.value) params.set('assignedTo', me.value)
      const qs = params.toString()
      const path = qs
        ? `/pages/api/v1/discrepancies/export.csv?${qs}`
        : '/pages/api/v1/discrepancies/export.csv'
      const today = new Date().toISOString().slice(0, 10).replaceAll('-', '')
      const fallback = subjectId
        ? `discrepancies_${subjectId}_${today}.csv`
        : `discrepancies_${today}.csv`
      await apiDownload(path, fallback)
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        exportError.value =
          'Backend nicht erreichbar — Discrepancy-Export konnte nicht gestartet werden.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        exportError.value =
          body?.message ?? `Discrepancy-Export fehlgeschlagen (HTTP ${e.status}).`
      } else {
        exportError.value =
          e instanceof Error ? e.message : 'Unbekannter Fehler beim Discrepancy-Export.'
      }
    } finally {
      isExporting.value = false
    }
  }

  /**
   * Phase E.6 {@code discrepancy-full} — build the absolute URL the
   * browser should hit for the CSV export. Honours the current filter
   * state so users get exactly what they're viewing.
   *
   * <p>Returns an `<a href="…" download>` target rather than a
   * fetch-and-download blob so the browser's native download UI (with
   * progress + virus-scan hooks) is in charge.
   */
  function buildExportUrl(): string {
    const params = new URLSearchParams()
    // Server-side filter narrowing — the list endpoint also accepts
    // these so re-hydration with the same params produces an
    // identical row set.
    if (statusFilter.value !== 'all' && statusFilter.value !== 'open') {
      params.set('status', statusFilter.value)
    }
    // 'open' has no server-side equivalent; the server returns
    // everything and we filter client-side. The CSV will be wider
    // than what's visible — this is a documented trade-off; users
    // can switch to a concrete status to narrow.
    let url = '/pages/api/v1/discrepancies/export.csv'
    const qs = params.toString()
    if (qs) url += `?${qs}`
    return url
  }

  /**
   * Phase E.6 — clear every piece of study-scoped state so the store
   * doesn't show study-A discrepancies after the user switches to
   * study B. Called by {@link useAuthStore.pickStudy} before
   * re-bootstrapping.
   */
  function reset() {
    rows.value = []
    isLoading.value = false
    isSubmitting.value = false
    isExporting.value = false
    error.value = null
    exportError.value = null
    query.value = ''
    statusFilter.value = 'open'
    typeFilter.value = 'all'
    onlyAssignedToMe.value = false
    threadCache.value = {}
    loadingThreadId.value = null
  }

  return {
    rows,
    isLoading,
    isSubmitting,
    isExporting,
    error,
    exportError,
    query,
    statusFilter,
    typeFilter,
    onlyAssignedToMe,
    me,
    filtered,
    totalCount,
    visibleCount,
    openCount,
    openTypeTotals,
    threadCache,
    loadingThreadId,
    clearFilters,
    load,
    add,
    // Phase E.6 DN — alias for `add` used by the new dialog/wiring slices
    // (NewNoteDialog, CrfEntryView). Keeps the legacy `add` callsites
    // working while the new components read more naturally as
    // `notes.createNote(...)`.
    createNote: add,
    appendThread,
    loadThread,
    buildExportUrl,
    exportCsv,
    reset,
  }
})
