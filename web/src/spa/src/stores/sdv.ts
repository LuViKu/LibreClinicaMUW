import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { SdvRow, SdvRequirement, SdvStatus } from '@/types/sdv'

/**
 * Phase E.6 — SDV (Source Data Verification) store.
 *
 * Backs the SdvView. Holds:
 *   - the row set (mock-loaded; backend swap is a one-line change per
 *     the E.4 inventory)
 *   - filter state (status, requirement, free text, only-with-queries)
 *   - the selection set used by the bulk-verify action
 *
 * The bulk-verify action flips selected rows to `verified`, clears
 * the selection, and re-runs the derived filter; in production it will
 * POST `/pages/api/v1/sdv/verify` with the eventCrfOid list and reload
 * the page section. The action is optimistic — on a real failure the
 * adapter contract will return the rejected oids so we can revert.
 */
export const useSdvStore = defineStore('sdv', () => {
  const rows = ref<SdvRow[]>([])
  const isLoading = ref(false)
  const isVerifying = ref(false)
  const error = ref<string | null>(null)

  // Filter state — persisted across navigation, matching the legacy
  // session-scoped filter context.
  const query = ref('')
  const statusFilter = ref<'all' | SdvStatus>('all')
  const requirementFilter = ref<'all' | SdvRequirement>('all')
  const onlyWithQueries = ref(false)

  /** v-model selection — Set of eventCrfOid strings. */
  const selected = ref<Set<string>>(new Set())

  const filtered = computed<SdvRow[]>(() => {
    const q = query.value.trim().toLowerCase()
    return rows.value.filter((row) => {
      if (q) {
        const blob = `${row.subjectId} ${row.crfName} ${row.eventLabel}`.toLowerCase()
        if (!blob.includes(q)) return false
      }
      if (statusFilter.value !== 'all' && row.status !== statusFilter.value) return false
      if (requirementFilter.value !== 'all' && row.requirement !== requirementFilter.value) return false
      if (onlyWithQueries.value && row.openQueries === 0) return false
      return true
    })
  })

  const totalCount = computed(() => rows.value.length)
  const visibleCount = computed(() => filtered.value.length)

  const verifiableCount = computed(() =>
    filtered.value.filter((r) => r.status === 'pending').length,
  )

  /** All visible rows that are eligible to be flipped to verified. */
  const verifiableInView = computed(() =>
    filtered.value.filter((r) => r.status === 'pending').map((r) => r.eventCrfOid),
  )

  const selectedCount = computed(() => selected.value.size)

  const allVerifiableSelected = computed(() => {
    if (verifiableInView.value.length === 0) return false
    return verifiableInView.value.every((oid) => selected.value.has(oid))
  })

  async function load(_siteOid?: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      // TODO(E.4): apiGet<SdvRow[]>(`/pages/api/v1/sdv${siteOid ? `?siteOid=${siteOid}` : ''}`).
      rows.value = await loadMock()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error loading SDV rows'
    } finally {
      isLoading.value = false
    }
  }

  function toggle(eventCrfOid: string) {
    const row = rows.value.find((r) => r.eventCrfOid === eventCrfOid)
    if (!row || row.status !== 'pending') return
    if (selected.value.has(eventCrfOid)) selected.value.delete(eventCrfOid)
    else selected.value.add(eventCrfOid)
    // Force reactivity (Sets are not deeply tracked).
    selected.value = new Set(selected.value)
  }

  function toggleAllInView() {
    if (allVerifiableSelected.value) {
      verifiableInView.value.forEach((oid) => selected.value.delete(oid))
    } else {
      verifiableInView.value.forEach((oid) => selected.value.add(oid))
    }
    selected.value = new Set(selected.value)
  }

  function clearSelection() {
    selected.value = new Set()
  }

  function clearFilters() {
    query.value = ''
    statusFilter.value = 'all'
    requirementFilter.value = 'all'
    onlyWithQueries.value = false
  }

  /** Optimistic bulk-verify. Returns the count of rows flipped. */
  async function verifySelected(): Promise<number> {
    const targets = [...selected.value]
    if (targets.length === 0) return 0
    isVerifying.value = true
    try {
      // TODO(E.4): apiPost('/pages/api/v1/sdv/verify', { eventCrfOids: targets }).
      await new Promise((resolve) => setTimeout(resolve, 40))
      const now = new Date().toISOString()
      for (const oid of targets) {
        const row = rows.value.find((r) => r.eventCrfOid === oid)
        if (row && row.status === 'pending') {
          row.status = 'verified'
          row.lastUpdatedAt = now
        }
      }
      const flipped = targets.length
      clearSelection()
      return flipped
    } finally {
      isVerifying.value = false
    }
  }

  return {
    rows,
    isLoading,
    isVerifying,
    error,
    query,
    statusFilter,
    requirementFilter,
    onlyWithQueries,
    selected,
    filtered,
    totalCount,
    visibleCount,
    verifiableCount,
    verifiableInView,
    selectedCount,
    allVerifiableSelected,
    load,
    toggle,
    toggleAllInView,
    clearSelection,
    clearFilters,
    verifySelected,
  }
})

/* -------------------------------------------------------------------------- */
/* Mock loader                                                                */
/* -------------------------------------------------------------------------- */

async function loadMock(): Promise<SdvRow[]> {
  await new Promise((resolve) => setTimeout(resolve, 30))
  return MOCK_ROWS
}

const MOCK_ROWS: SdvRow[] = [
  { eventCrfOid: 'EC_M001_V1_DEMO', subjectId: 'M-001', siteLabel: 'München', eventLabel: 'V1 Inclusion', eventStartDate: '2020-10-06', crfName: 'Demographics v1.0', crfLanguage: 'de', status: 'pending',  requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-10-06T15:42:08Z' },
  { eventCrfOid: 'EC_M002_V1_DEMO', subjectId: 'M-002', siteLabel: 'München', eventLabel: 'V1 Inclusion', eventStartDate: '2020-10-09', crfName: 'Demographics v1.0', crfLanguage: 'de', status: 'query',    requirement: 'required-100', openQueries: 1, lastUpdatedAt: '2020-10-09T11:08:14Z' },
  { eventCrfOid: 'EC_M002_V1_CMED', subjectId: 'M-002', siteLabel: 'München', eventLabel: 'V1 Inclusion', eventStartDate: '2020-10-09', crfName: 'cmed v1.0',          crfLanguage: 'de', status: 'pending',  requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-10-09T11:14:02Z' },
  { eventCrfOid: 'EC_M003_V1_DEMO', subjectId: 'M-003', siteLabel: 'Wien',    eventLabel: 'V1 Inclusion', eventStartDate: '2020-10-15', crfName: 'Demographics v1.0', crfLanguage: 'de', status: 'locked',   requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-11-20T08:12:00Z' },
  { eventCrfOid: 'EC_M004_V1_DEMO', subjectId: 'M-004', siteLabel: 'Wien',    eventLabel: 'V1 Inclusion', eventStartDate: '2020-11-02', crfName: 'Demographics v1.0', crfLanguage: 'de', status: 'pending',  requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-11-02T16:30:11Z' },
  { eventCrfOid: 'EC_M005_V2_VIT',  subjectId: 'M-005', siteLabel: 'München', eventLabel: 'V2 Day 30',    eventStartDate: '2020-12-12', crfName: 'Vitals v1.0',       crfLanguage: 'de', status: 'pending',  requirement: 'required-partial', openQueries: 0, lastUpdatedAt: '2020-12-12T10:01:00Z' },
  { eventCrfOid: 'EC_M005_V2_AE',   subjectId: 'M-005', siteLabel: 'München', eventLabel: 'V2 Day 30',    eventStartDate: '2020-12-12', crfName: 'Adverse Events v1', crfLanguage: 'de', status: 'verified', requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-12-15T14:00:00Z' },
  { eventCrfOid: 'EC_M001_V2_VIT',  subjectId: 'M-001', siteLabel: 'München', eventLabel: 'V2 Day 30',    eventStartDate: '2020-11-05', crfName: 'Vitals v1.0',       crfLanguage: 'de', status: 'pending',  requirement: 'required-100', openQueries: 0, lastUpdatedAt: '2020-11-05T09:30:00Z' },
  { eventCrfOid: 'EC_M001_V2_AE',   subjectId: 'M-001', siteLabel: 'München', eventLabel: 'V2 Day 30',    eventStartDate: '2020-11-05', crfName: 'Adverse Events v1', crfLanguage: 'de', status: 'query',    requirement: 'required-100', openQueries: 2, lastUpdatedAt: '2020-11-07T13:22:00Z' },
  { eventCrfOid: 'EC_M001_V3_FU',   subjectId: 'M-001', siteLabel: 'München', eventLabel: 'V3 Day 90',    eventStartDate: '2021-01-15', crfName: 'Follow-up v1.0',    crfLanguage: 'de', status: 'pending',  requirement: 'not-required', openQueries: 0, lastUpdatedAt: '2021-01-15T11:00:00Z' },
]
