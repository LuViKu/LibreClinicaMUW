import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { EventStatus, Subject } from '@/types/subject'

/**
 * Phase E.5 — Subjects store.
 *
 * Backs the SubjectMatrixView. Until the E.4 backend adapter at
 * `GET /pages/api/v1/subjects` ships (gated on the E.0 dispatcher fix),
 * the store hydrates from in-memory mock data shaped exactly like the
 * planned API response. When the adapter lands, swap the `loadMock`
 * call in `load()` for `apiGet<Subject[]>(...)` and nothing in the
 * consuming view changes.
 *
 * Filter state lives in the store so navigating away from the matrix
 * and back keeps the user's filter context (the existing JSP also does
 * this via session-scoped state).
 */
export const useSubjectsStore = defineStore('subjects', () => {
  const rows = ref<Subject[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  // Filter state — persisted across navigation.
  const query = ref('')
  const statusFilter = ref<'all' | 'open-events' | 'all-events-complete' | 'signed'>('all')
  const onlyWithQueries = ref(false)

  const filtered = computed<Subject[]>(() => {
    const q = query.value.trim().toLowerCase()
    return rows.value.filter((subject) => {
      if (q && !subject.id.toLowerCase().includes(q) && !(subject.secondaryId ?? '').toLowerCase().includes(q)) {
        return false
      }
      if (onlyWithQueries.value && subject.openQueries === 0) return false

      switch (statusFilter.value) {
        case 'open-events':
          return subject.events.some((e) => e.status === 'scheduled' || e.status === 'in-progress' || e.status === 'not-scheduled')
        case 'all-events-complete':
          return subject.events.every((e) => e.status === 'complete' || e.status === 'signed' || e.status === 'locked')
        case 'signed':
          return subject.signed
        case 'all':
        default:
          return true
      }
    })
  })

  const totalCount = computed(() => rows.value.length)
  const visibleCount = computed(() => filtered.value.length)

  async function load(_siteOid?: string) {
    isLoading.value = true
    error.value = null
    try {
      // TODO(E.4): replace with `await apiGet<Subject[]>(siteOid ? ...)`.
      rows.value = await loadMock()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error loading subjects'
    } finally {
      isLoading.value = false
    }
  }

  function clearFilters() {
    query.value = ''
    statusFilter.value = 'all'
    onlyWithQueries.value = false
  }

  return {
    // state
    rows,
    isLoading,
    error,
    query,
    statusFilter,
    onlyWithQueries,
    // derived
    filtered,
    totalCount,
    visibleCount,
    // actions
    load,
    clearFilters,
  }
})

/**
 * Mock data loader. The shape matches the planned
 * `GET /pages/api/v1/subjects?siteOid=…` response exactly.
 */
async function loadMock(): Promise<Subject[]> {
  // Pretend the network takes a tick — gives the loading state a chance
  // to render in dev so layout regressions are caught.
  await new Promise((resolve) => setTimeout(resolve, 30))
  return MOCK_SUBJECTS
}

const EVENT_LABELS = ['V1 Inclusion', 'V2 Day 30', 'V3 Day 90'] as const
const EVENT_OIDS  = ['SE_V1_INCLUSION', 'SE_V2_DAY30', 'SE_V3_DAY90'] as const

function row(
  id: string,
  secondaryId: string | null,
  gender: 'F' | 'M',
  yearOfBirth: number,
  enrolledOn: string,
  groupLabel: string | null,
  statuses: [EventStatus, EventStatus, EventStatus],
  openQueriesPerEvent: [number, number, number],
  signed: boolean,
): Subject {
  return {
    id,
    secondaryId,
    siteOid: 'TDS0004',
    siteLabel: 'München',
    gender,
    yearOfBirth,
    groupLabel,
    enrolledOn,
    signed,
    openQueries: openQueriesPerEvent.reduce((a, b) => a + b, 0),
    events: statuses.map((status, i) => ({
      eventDefinitionOid: EVENT_OIDS[i],
      label: EVENT_LABELS[i],
      status,
      openQueries: openQueriesPerEvent[i] ?? 0,
    })),
  }
}

const MOCK_SUBJECTS: Subject[] = [
  row('M-001', null,         'F', 1962, '2020-10-06', 'Arm A', ['complete', 'complete', 'in-progress'], [1, 1, 0], false),
  row('M-002', null,         'M', 1955, '2020-10-09', 'Arm B', ['complete', 'in-progress', 'not-scheduled'], [0, 2, 0], false),
  row('M-003', null,         'F', 1949, '2020-10-15', 'Arm A', ['complete', 'complete', 'complete'], [0, 0, 0], true),
  row('M-004', '04-MUW',     'M', 1971, '2020-11-02', 'Arm A', ['in-progress', 'not-scheduled', 'not-scheduled'], [3, 0, 0], false),
  row('M-005', null,         'F', 1980, '2020-11-12', 'Arm B', ['complete', 'complete', 'scheduled'], [0, 0, 0], false),
  row('M-006', null,         'M', 1958, '2020-12-01', 'Arm B', ['signed',  'signed',  'signed'],     [0, 0, 0], true),
  row('M-007', null,         'F', 1972, '2021-01-15', 'Arm A', ['complete', 'in-progress', 'not-scheduled'], [0, 1, 0], false),
]
