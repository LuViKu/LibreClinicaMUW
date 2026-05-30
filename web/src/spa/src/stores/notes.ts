import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { DiscrepancyNote, NoteStatus, NoteType } from '@/types/note'

/**
 * Phase E.6 — Discrepancy-notes store.
 *
 * Backs the NotesDiscrepanciesView. Mock-hydrated; the planned adapter
 * lives at `GET /pages/api/v1/discrepancies` per api-surface.md row 7.
 *
 * Filter state mirrors the legacy session-scoped JSP context: status,
 * type, free text, only-assigned-to-me. The "assigned-to-me" flag uses
 * the currentUser from a future auth store; for now we pass it in via
 * the `me` ref.
 */
export const useNotesStore = defineStore('notes', () => {
  const rows = ref<DiscrepancyNote[]>([])
  const isLoading = ref(false)
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
      // TODO(E.4): apiGet<DiscrepancyNote[]>('/pages/api/v1/discrepancies?...').
      rows.value = await loadMock()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error loading discrepancies'
    } finally {
      isLoading.value = false
    }
  }

  return {
    rows,
    isLoading,
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
  }
})

/* -------------------------------------------------------------------------- */
/* Mock loader                                                                */
/* -------------------------------------------------------------------------- */

async function loadMock(): Promise<DiscrepancyNote[]> {
  await new Promise((resolve) => setTimeout(resolve, 30))
  return MOCK
}

const MOCK: DiscrepancyNote[] = [
  { id: 'n-001', type: 'query',             status: 'new',                 subjectId: 'M-001', itemOid: 'date_einverst',    description: 'Source document shows 05-Oct-2020 — please verify and correct if needed.', assignedTo: 'user_demo',    daysOpen: 2, lastActivityAt: '2026-05-28T14:02:33Z' },
  { id: 'n-002', type: 'query',             status: 'updated',             subjectId: 'M-001', itemOid: 'weight_kg',        description: 'Out-of-range value 155 — confirmed correct by site.', assignedTo: 'monitor_demo', daysOpen: 5, lastActivityAt: '2026-05-25T09:11:00Z' },
  { id: 'n-003', type: 'query',             status: 'resolution-proposed', subjectId: 'M-002', itemOid: 'consent_signed',   description: 'Annotated and reconciled with paper form.', assignedTo: 'monitor_demo', daysOpen: 7, lastActivityAt: '2026-05-23T10:30:00Z' },
  { id: 'n-004', type: 'failed-validation', status: 'new',                 subjectId: 'M-002', itemOid: 'vials_dispensed',  description: 'Must be 0–240 — entered 250.', assignedTo: 'user_demo',    daysOpen: 3, lastActivityAt: '2026-05-27T15:00:00Z' },
  { id: 'n-005', type: 'annotation',        status: 'not-applicable',      subjectId: 'M-001', itemOid: 'ekg_normal',       description: 'EKG-Bericht abgelegt unter ID 4421.', assignedTo: null,          daysOpen: 0, lastActivityAt: '2026-05-29T11:00:00Z' },
  { id: 'n-006', type: 'reason-for-change', status: 'not-applicable',      subjectId: 'M-003', itemOid: 'enrolment_date',   description: 'Corrected after CRF completion: transposition typo.', assignedTo: null,          daysOpen: 0, lastActivityAt: '2026-05-30T08:00:00Z' },
  { id: 'n-007', type: 'query',             status: 'closed',              subjectId: 'M-003', itemOid: 'height_cm',        description: 'Reconciled with source — closed.', assignedTo: null,          daysOpen: 0, lastActivityAt: '2026-05-22T12:00:00Z' },
]
