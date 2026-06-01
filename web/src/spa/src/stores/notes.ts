import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type { DiscrepancyNote, NoteStatus, NoteType } from '@/types/note'

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
  const error = ref<string | null>(null)

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
    description: string
    assignedTo?: string | null
  }): Promise<DiscrepancyNote | null> {
    isSubmitting.value = true
    error.value = null
    try {
      const created = await apiPost<DiscrepancyNote>('/pages/api/v1/discrepancies', {
        subjectId: input.subjectId,
        itemOid: input.itemOid,
        description: input.description,
        assignedTo: input.assignedTo ?? null,
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

  return {
    rows,
    isLoading,
    isSubmitting,
    error,
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
    clearFilters,
    load,
    add,
  }
})
