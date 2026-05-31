import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { EventStatus, Gender, Subject } from '@/types/subject'

/**
 * Phase E.5 + E.4 — Subjects store.
 *
 * Calls `GET /pages/api/v1/subjects` (adapter shipped in E.4 slice #1).
 * The backend currently returns identity columns only (id, secondaryId,
 * gender, yearOfBirth, group, enrolled, site); per-event status cells,
 * sign-off state, and open-query counts ship in the follow-up adapter.
 * The store reuses the SPA `Subject` shape verbatim — empty `events: []`
 * + `signed: false` + `openQueries: 0` are normal until that lands.
 *
 * Fallback policy:
 *  - In dev (Vite proxy reaches the backend) the real call is preferred.
 *  - If the call fails with a network error (backend not running, dev
 *    proxy mis-configured) we fall back to the mock fixture so the
 *    matrix still renders for offline UX work.
 *  - Set `VITE_USE_MOCK_API=true` in `.env.local` to bypass the network
 *    entirely (useful when iterating on the view without a backend).
 *  - Auth errors (401/403) are NOT fallback-eligible — they propagate
 *    so the router-level guard can redirect to /login.
 *
 * Filter state lives in the store so navigating away from the matrix
 * and back keeps the user's filter context (the existing JSP also does
 * this via session-scoped state).
 */
const USE_MOCK_API = import.meta.env.VITE_USE_MOCK_API === 'true'
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
      if (USE_MOCK_API) {
        rows.value = await loadMock()
        return
      }
      try {
        rows.value = await apiGet<Subject[]>('/pages/api/v1/subjects')
      } catch (e) {
        if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
          // Let the router-level auth guard handle these — propagate so
          // the calling view doesn't silently render a fallback list.
          throw e
        }
        if (e instanceof ApiNetworkError) {
          // Backend unreachable — fall back to mock so the SPA keeps
          // working for offline UX iteration. Surface a soft error so
          // the user knows the data is stale.
          rows.value = await loadMock()
          error.value = 'Backend nicht erreichbar — Demo-Daten werden angezeigt.'
          return
        }
        if (e instanceof ApiError) {
          // 4xx (e.g. 400 — no active study bound) and 5xx land here.
          // Fall back to mock so the user sees *something* while we
          // surface the underlying message.
          rows.value = await loadMock()
          error.value = e.message
          return
        }
        throw e
      }
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

  /**
   * Phase E.5.2 — enrol a new subject.
   *
   * Local-first: validates the input against the existing rows
   * (subject-id uniqueness) and the shape constraints from
   * `validateAddSubject` below, then optimistically appends a Subject
   * with `not-scheduled` event cells. Returns the new Subject so the
   * caller can navigate or chain into Schedule Event.
   *
   * When the E.4 adapter at `POST /pages/api/v1/subjects` lands, swap
   * the optimistic append for an awaited `apiPost<Subject>` and remove
   * the local id-generation — the backend will own the id allocation.
   */
  async function add(input: AddSubjectInput): Promise<Subject> {
    const errors = validateAddSubject(input, rows.value)
    if (errors.length > 0) {
      const message = errors.map((e) => e.message).join('; ')
      throw new AddSubjectValidationError(message, errors)
    }

    isLoading.value = true
    error.value = null
    try {
      // TODO(E.4): replace with apiPost<Subject>('/pages/api/v1/subjects', input).
      await new Promise((resolve) => setTimeout(resolve, 30))

      const subject: Subject = {
        id: input.id.trim(),
        secondaryId: input.secondaryId?.trim() || null,
        siteOid: input.siteOid,
        siteLabel: input.siteLabel,
        gender: input.gender,
        yearOfBirth: input.yearOfBirth ?? null,
        groupLabel: input.groupLabel ?? null,
        enrolledOn: input.enrolledOn,
        signed: false,
        openQueries: 0,
        events: EVENT_LABELS.map((label, i) => ({
          eventDefinitionOid: EVENT_OIDS[i]!,
          label,
          status: 'not-scheduled',
          openQueries: 0,
        })),
      }
      rows.value = [...rows.value, subject]
      return subject
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Unknown error adding subject'
      error.value = msg
      throw e
    } finally {
      isLoading.value = false
    }
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
    add,
  }
})

/** Form payload from the Add Subject view. */
export interface AddSubjectInput {
  id: string
  secondaryId?: string | null
  siteOid: string
  siteLabel: string
  gender: Gender
  yearOfBirth?: number | null
  groupLabel?: string | null
  /** ISO `YYYY-MM-DD`. */
  enrolledOn: string
}

export type AddSubjectErrorField =
  | 'id'
  | 'secondaryId'
  | 'enrolledOn'
  | 'gender'
  | 'yearOfBirth'

export interface AddSubjectError {
  field: AddSubjectErrorField
  message: string
}

export class AddSubjectValidationError extends Error {
  constructor(message: string, public readonly errors: AddSubjectError[]) {
    super(message)
    this.name = 'AddSubjectValidationError'
  }
}

/**
 * Local validation that mirrors the `/AddNewSubject` servlet's checks:
 *  - Subject ID required + unique within the site.
 *  - Secondary ID may not contain obvious PHI tokens (the legacy
 *    servlet leaves this to a study-config rule; we keep a soft
 *    client-side check matching the mockup's red ErrorText).
 *  - Enrolment date must be present + not in the future.
 *  - Gender must be one of the allowed codes.
 *  - Year of birth must be plausible (1900–current year).
 *
 * The backend remains authoritative — the SPA's validation is for UX
 * feedback only. Per DR-008 the server-side rules engine is still the
 * source of truth.
 */
export function validateAddSubject(
  input: AddSubjectInput,
  existing: ReadonlyArray<Subject>,
  options: { today?: string } = {},
): AddSubjectError[] {
  const errors: AddSubjectError[] = []
  const today = options.today ?? new Date().toISOString().slice(0, 10)

  const id = input.id?.trim() ?? ''
  if (!id) errors.push({ field: 'id', message: 'Subject ID is required.' })
  else if (id.length > 30) errors.push({ field: 'id', message: 'Subject ID is too long (max 30 characters).' })
  else if (existing.some((s) => s.id.toLowerCase() === id.toLowerCase()))
    errors.push({ field: 'id', message: `Subject ID "${id}" already exists at this site.` })

  const secondary = input.secondaryId?.trim() ?? ''
  if (secondary && /[0-9]{8,}/.test(secondary))
    errors.push({ field: 'secondaryId', message: 'Secondary ID must not contain an 8+ digit run — risks PHI exposure.' })

  if (!input.enrolledOn) errors.push({ field: 'enrolledOn', message: 'Enrolment date is required.' })
  else if (input.enrolledOn > today)
    errors.push({ field: 'enrolledOn', message: 'Enrolment date must not be in the future.' })

  if (!input.gender) errors.push({ field: 'gender', message: 'Gender is required.' })
  else if (!(['F', 'M', 'O', 'U'] as const).includes(input.gender))
    errors.push({ field: 'gender', message: `"${input.gender}" is not a valid gender code.` })

  if (input.yearOfBirth != null) {
    const year = Number(input.yearOfBirth)
    const thisYear = Number(today.slice(0, 4))
    if (!Number.isInteger(year) || year < 1900 || year > thisYear)
      errors.push({ field: 'yearOfBirth', message: `Year of birth must be between 1900 and ${thisYear}.` })
  }

  return errors
}

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
